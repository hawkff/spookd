package app.morphe.extension.instagram.patches.download.gallery;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.media.MediaDataSource;
import android.media.MediaMetadataRetriever;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.morphe.extension.instagram.constants.UI;
import app.morphe.extension.instagram.patches.dm.InboxLock;
import app.morphe.extension.instagram.patches.dm.vault.MediaVault;
import app.morphe.extension.instagram.patches.dm.vault.PlainMedia;
import app.morphe.extension.instagram.patches.dm.vault.VaultViewer;
import app.morphe.extension.instagram.patches.dm.vault.ViewerMedia;
import app.morphe.extension.instagram.settings.SettingsStatus;
import app.morphe.extension.instagram.settings.preference.widgets.InstagramPreferenceStyle;
import app.morphe.extension.shared.Logger;

/**
 * The private gallery, built in code inside the app's modal activity: downloads by tab, and stored
 * DM media in the DMs tab. The UI runs on the main thread; listing, cleanup and deletes run on one
 * background thread, thumbnails on a small pool.
 */
public final class PrivateGalleryActivity {
    private static final int COLUMNS = 3;
    // Serial, so a delete runs before the reload that follows it.
    private static final Executor JOBS = Executors.newSingleThreadExecutor();
    private static final GalleryTab[] TABS = GalleryTab.values();
    private static final String[] TAB_TITLES = {"piko_gallery_tab_posts", "piko_gallery_tab_reels",
            "piko_gallery_tab_stories", "piko_gallery_tab_dms"};

    private PrivateGalleryActivity() {
    }

    /**
     * Main thread, from the launch dispatch. Fails closed: below Android 11 or without a secure
     * screen lock only the screen-lock notice shows; otherwise one credential prompt, and
     * cancelling it closes the screen.
     */
    public static void open(Activity activity) {
        try {
            activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            Controller controller = new Controller(activity);
            if (!Gallery.canOpen(activity)) {
                controller.showNotice();
                return;
            }
            InboxLock.confirmViewerWithCredential(activity, () -> {
                try {
                    if (!activity.isFinishing() && !activity.isDestroyed()) controller.setup();
                } catch (Exception e) {
                    Logger.printException(() -> "PrivateGalleryActivity setup failed", e);
                    activity.finish();
                }
            });
        } catch (Exception e) {
            Logger.printException(() -> "PrivateGalleryActivity.open failed", e);
            activity.finish();
        }
    }

    /** One tile: a gallery download, or a stored DM vault item. */
    private static final class Item {
        final long id;
        final boolean vault;
        /** photo, video, audio or voice. */
        final String kind;
        final long time;
        final long size;
        final Long durationMs;
        /** Null for vault items. */
        final File file;

        Item(GalleryStore.Entry entry) {
            id = entry.id;
            vault = false;
            kind = entry.item.kind;
            time = entry.savedAt;
            size = entry.sizeBytes;
            durationMs = entry.durationMs;
            file = entry.file;
        }

        Item(MediaVault.Item stored) {
            id = stored.id;
            vault = true;
            kind = stored.kind;
            time = stored.msgTimestamp;
            size = stored.sizeBytes;
            durationMs = null;
            file = null;
        }

        String key() {
            return (vault ? "v" : "d") + id;
        }

        boolean photo() {
            return GalleryItem.PHOTO.equals(kind);
        }

        boolean video() {
            return GalleryItem.VIDEO.equals(kind);
        }
    }

    private static final class Tile {
        final View view;
        final ImageView image;
        final Item item;
        boolean loaded;

        Tile(View view, ImageView image, Item item) {
            this.view = view;
            this.image = image;
            this.item = item;
        }
    }

    /** Runs on the jobs thread: applies the cleanup when asked, then lists every tab; DMs merges the vault by time. */
    private static Map<GalleryTab, List<Item>> load(boolean prune) {
        Map<GalleryTab, List<Item>> out = new EnumMap<>(GalleryTab.class);
        for (GalleryTab tab : TABS) out.put(tab, new ArrayList<>());
        try {
            if (prune) Gallery.prune();
            GalleryStore store = GalleryStore.get();
            for (GalleryTab tab : TABS) {
                for (GalleryStore.Entry entry : store.list(tab)) out.get(tab).add(new Item(entry));
            }
        } catch (Exception e) {
            Logger.printException(() -> "Private gallery: cannot list downloads", e);
        }
        if (SettingsStatus.dmMediaVault) {
            try {
                List<Item> dms = out.get(GalleryTab.DMS);
                for (MediaVault.Item stored : MediaVault.get().storedItems()) dms.add(new Item(stored));
                dms.sort((a, b) -> Long.compare(b.time, a.time));
            } catch (Exception e) {
                Logger.printException(() -> "Private gallery: cannot list DM media", e);
            }
        }
        return out;
    }

    private static final class Controller {
        private final Activity activity;
        private final Handler ui = new Handler(Looper.getMainLooper());
        private final Runnable sweep = this::sweepVisible;
        private final List<Tile> tiles = new ArrayList<>();
        private Map<GalleryTab, List<Item>> items = new EnumMap<>(GalleryTab.class);
        private GalleryTab tab = GalleryTab.POSTS;
        private TextView[] tabViews;
        private TextView summary;
        private FrameLayout content;
        private ScrollView scroll;
        private ThumbLoader thumbs;
        private float density;
        private int tileSize;
        private int loads;
        private boolean closed;

        Controller(Activity activity) {
            this.activity = activity;
            density = activity.getResources().getDisplayMetrics().density;
        }

        void showNotice() {
            buildRoot().addView(message(str("piko_vault_needs_screen_lock")),
                    new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        }

        void setup() {
            int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
            tileSize = (screenWidth - dp(2) * (COLUMNS + 1)) / COLUMNS;
            thumbs = new ThumbLoader();
            LinearLayout root = buildRoot();

            LinearLayout tabsRow = new LinearLayout(activity);
            tabsRow.setOrientation(LinearLayout.HORIZONTAL);
            tabViews = new TextView[TABS.length];
            for (int i = 0; i < TABS.length; i++) {
                GalleryTab target = TABS[i];
                TextView view = new TextView(activity);
                view.setText(str(TAB_TITLES[i]));
                view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                view.setGravity(Gravity.CENTER);
                view.setPadding(dp(4), dp(12), dp(4), dp(12));
                view.setOnClickListener(v -> select(target));
                tabsRow.addView(view, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
                tabViews[i] = view;
            }
            root.addView(tabsRow);

            summary = new TextView(activity);
            summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            summary.setTextColor(InstagramPreferenceStyle.secondaryTextColor());
            summary.setPadding(dp(16), dp(4), dp(16), dp(8));
            root.addView(summary);

            content = new FrameLayout(activity);
            content.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                    updateTileSize(right - left));
            root.addView(content, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
            updateTabs();
            watchLifecycle();
            reload(true);
        }

        /** Toolbar, insets and back handling; sets the content view and returns the column to fill. */
        private LinearLayout buildRoot() {
            LinearLayout root = new LinearLayout(activity);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundColor(InstagramPreferenceStyle.backgroundColor());
            InstagramPreferenceStyle.applySystemBarStyle(activity);

            LinearLayout toolbar = new LinearLayout(activity);
            toolbar.setBaselineAligned(false);
            toolbar.setBackgroundColor(InstagramPreferenceStyle.backgroundColor());
            ImageView back = new ImageView(activity);
            UI.setThemedIcon(back, UI.DRAWABLE_ARROW_BACK);
            back.setOnClickListener(v -> activity.finish());
            TextView title = new TextView(activity);
            title.setText(str("piko_private_gallery"));
            title.setTextColor(InstagramPreferenceStyle.primaryTextColor());
            InstagramPreferenceStyle.applyToolbarLayout(activity, toolbar, back, title, false);
            toolbar.addView(back);
            toolbar.addView(title);
            root.addView(toolbar);

            root.setOnApplyWindowInsetsListener((v, insets) -> {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
                return insets;
            });
            if (activity instanceof androidx.activity.ComponentActivity) {
                try {
                    ((androidx.activity.ComponentActivity) activity).getOnBackPressedDispatcher().addCallback(
                            (androidx.lifecycle.LifecycleOwner) activity,
                            new androidx.activity.OnBackPressedCallback(true) {
                                @Override
                                public void handleOnBackPressed() {
                                    activity.finish();
                                }
                            });
                } catch (Throwable ignored) {
                }
            }
            activity.setContentView(root);
            return root;
        }

        /** Leaving the app or the screen ends it, so nothing shown outlives a stop; a rotation keeps it. */
        private void watchLifecycle() {
            Application app = activity.getApplication();
            app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityCreated(Activity a, Bundle b) {}
                @Override public void onActivityStarted(Activity a) {}
                @Override public void onActivityResumed(Activity a) {}
                @Override public void onActivityPaused(Activity a) {}
                @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}

                @Override
                public void onActivityStopped(Activity a) {
                    if (a != activity || a.isChangingConfigurations()) return;
                    close();
                    a.finish();
                }

                @Override
                public void onActivityDestroyed(Activity a) {
                    if (a != activity) return;
                    app.unregisterActivityLifecycleCallbacks(this);
                    close();
                }
            });
        }

        private void close() {
            VaultViewer.close(activity);
            if (closed) return;
            closed = true;
            ui.removeCallbacksAndMessages(null);
            tiles.clear();
            content.removeAllViews();
            thumbs.shutdown();
        }

        private void reload(boolean prune) {
            int generation = ++loads;
            JOBS.execute(() -> {
                Map<GalleryTab, List<Item>> loaded = load(prune);
                ui.post(() -> {
                    if (!closed && generation == loads) show(loaded);
                });
            });
        }

        private void show(Map<GalleryTab, List<Item>> loaded) {
            items = loaded;
            int count = 0;
            long bytes = 0;
            for (List<Item> list : loaded.values()) {
                for (Item item : list) {
                    count++;
                    bytes += item.size;
                }
            }
            summary.setText(String.format(Locale.getDefault(), str("piko_gallery_summary"), count,
                    Formatter.formatShortFileSize(activity, bytes)));
            if (tab == GalleryTab.DMS && !dmsShown()) tab = GalleryTab.POSTS;
            updateTabs();
            rebuildContent();
        }

        // DM downloads exist without the vault patch, so their rows keep the tab.
        private boolean dmsShown() {
            List<Item> dms = items.get(GalleryTab.DMS);
            return SettingsStatus.dmMediaVault || (dms != null && !dms.isEmpty());
        }

        private void select(GalleryTab target) {
            if (tab == target) return;
            tab = target;
            updateTabs();
            rebuildContent();
        }

        private void updateTabs() {
            for (int i = 0; i < TABS.length; i++) {
                boolean selected = TABS[i] == tab;
                TextView view = tabViews[i];
                view.setVisibility(TABS[i] != GalleryTab.DMS || dmsShown() ? View.VISIBLE : View.GONE);
                view.setSelected(selected);
                view.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
                view.setTextColor(selected ? InstagramPreferenceStyle.primaryTextColor()
                        : InstagramPreferenceStyle.secondaryTextColor());
            }
        }

        private void rebuildContent() {
            tiles.clear();
            ui.removeCallbacks(sweep);
            content.removeAllViews();
            scroll = null;
            List<Item> list = items.get(tab);
            if (list == null || list.isEmpty()) {
                content.addView(message(str("piko_gallery_empty")));
                return;
            }
            int gap = dp(2);
            LinearLayout body = new LinearLayout(activity);
            body.setOrientation(LinearLayout.VERTICAL);
            LinearLayout row = null;
            for (int i = 0; i < list.size(); i++) {
                if (i % COLUMNS == 0) {
                    row = new LinearLayout(activity);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    params.topMargin = gap;
                    params.leftMargin = gap;
                    body.addView(row, params);
                }
                row.addView(buildTile(list.get(i), gap));
            }
            scroll = new ScrollView(activity);
            scroll.addView(body);
            content.addView(scroll, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            scroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> scheduleSweep());
            scroll.getViewTreeObserver().addOnGlobalLayoutListener(this::scheduleSweep);
            scheduleSweep();
        }

        private View buildTile(Item item, int gap) {
            FrameLayout tile = new FrameLayout(activity);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(tileSize, tileSize);
            params.rightMargin = gap;
            tile.setLayoutParams(params);
            tile.setBackgroundColor(themed("igds_color_secondary_background", 0xFF262626));
            tile.setContentDescription(str(item.photo() ? "piko_media_photo"
                    : item.video() ? "piko_media_video" : "piko_gallery_audio"));

            ImageView image = new ImageView(activity);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            tile.addView(image, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

            if (!item.photo() && !item.video()) {
                TextView note = new TextView(activity);
                note.setText("♪");
                note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32);
                note.setTextColor(InstagramPreferenceStyle.secondaryTextColor());
                note.setGravity(Gravity.CENTER);
                tile.addView(note, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            }
            String duration = item.durationMs == null ? "" : DateUtils.formatElapsedTime(item.durationMs / 1000);
            String badgeText = item.video() ? ("▶ " + duration).trim() : duration;
            if (!badgeText.isEmpty()) {
                TextView badge = new TextView(activity);
                badge.setText(badgeText);
                badge.setTextColor(0xFFFFFFFF);
                badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                badge.setPadding(dp(4), 0, dp(4), 0);
                badge.setBackgroundColor(0x99000000);
                FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
                badgeParams.gravity = Gravity.BOTTOM | Gravity.END;
                badgeParams.setMargins(0, 0, dp(4), dp(4));
                tile.addView(badge, badgeParams);
            }

            tile.setOnClickListener(v -> VaultViewer.showFor(activity, viewerSource(item)));
            tile.setOnLongClickListener(v -> {
                showOptions(item);
                return true;
            });
            tiles.add(new Tile(tile, image, item));
            return tile;
        }

        /** Opens the item for the viewer on its loader thread. */
        private static Callable<VaultViewer.Opened> viewerSource(Item item) {
            if (!item.vault) return () -> new VaultViewer.Opened(new PlainMedia(item.file), item.kind);
            return () -> {
                MediaVault.Stored stored = MediaVault.get().open(item.id);
                return stored == null ? null : new VaultViewer.Opened(stored.file, stored.kind);
            };
        }

        private void showOptions(Item item) {
            new AlertDialog.Builder(InstagramPreferenceStyle.dialogContext(activity))
                    .setItems(new CharSequence[]{str("piko_delete")}, (d, which) -> confirmDelete(item))
                    .show();
        }

        private void confirmDelete(Item item) {
            new AlertDialog.Builder(InstagramPreferenceStyle.dialogContext(activity))
                    .setMessage(str("piko_gallery_delete_confirm"))
                    .setPositiveButton(str("piko_delete"), (d, which) -> delete(item))
                    .setNegativeButton(str("piko_cancel"), null)
                    .show();
        }

        private void delete(Item item) {
            JOBS.execute(() -> {
                try {
                    if (item.vault) MediaVault.get().delete(item.id);
                    else GalleryStore.get().delete(item.id);
                } catch (Exception e) {
                    Logger.printException(() -> "Private gallery: cannot delete " + item.key(), e);
                }
                ui.post(() -> {
                    if (!closed) reload(false);
                });
            });
        }

        private void scheduleSweep() {
            ui.removeCallbacks(sweep);
            ui.post(sweep);
        }

        private void updateTileSize(int width) {
            int size = (width - dp(2) * (COLUMNS + 1)) / COLUMNS;
            if (size <= 0 || size == tileSize) return;
            tileSize = size;
            for (Tile tile : tiles) {
                LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) tile.view.getLayoutParams();
                params.width = size;
                params.height = size;
                tile.view.setLayoutParams(params);
            }
            scheduleSweep();
        }

        /** Loads thumbnails of the tiles within a screen of the visible area and drops the others. */
        private void sweepVisible() {
            if (closed || scroll == null || tiles.isEmpty()) return;
            int top = scroll.getScrollY();
            int height = scroll.getHeight();
            if (height == 0) return;
            int from = top - height;
            int to = top + height * 2;
            for (Tile tile : tiles) {
                int tileTop = topWithinScroll(tile.view);
                boolean wanted = tileTop >= 0 && tileTop + tileSize >= from && tileTop <= to;
                if (wanted && !tile.loaded) {
                    tile.loaded = true;
                    thumbs.load(tile.item, tile.image);
                } else if (!wanted && tile.loaded) {
                    tile.loaded = false;
                    tile.image.setImageBitmap(null);
                    tile.image.setTag(null);
                }
            }
        }

        private int topWithinScroll(View view) {
            int offset = 0;
            View node = view;
            while (node != null && node != scroll) {
                offset += node.getTop();
                if (!(node.getParent() instanceof View)) return -1;
                node = (View) node.getParent();
            }
            return node == scroll ? offset : -1;
        }

        private TextView message(CharSequence text) {
            TextView view = new TextView(activity);
            view.setText(text);
            view.setTextColor(InstagramPreferenceStyle.secondaryTextColor());
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            view.setGravity(Gravity.CENTER);
            view.setPadding(dp(24), dp(24), dp(24), dp(24));
            view.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            return view;
        }

        private int dp(int value) {
            return (int) (value * density);
        }

        private static int themed(String attr, int fallback) {
            try {
                return UI.getThemedColour(attr);
            } catch (Throwable t) {
                return fallback;
            }
        }

        /** Download thumbnails are cached as JPEG; DM thumbnails stay in memory so no plaintext of them reaches disk. */
        private final class ThumbLoader {
            private final ExecutorService pool = Executors.newFixedThreadPool(3);
            private final File dir = new File(activity.getCacheDir(), "piko_gallery_thumbs");
            private final LruCache<String, Bitmap> cache =
                    new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 8192)) {
                        @Override
                        protected int sizeOf(String key, Bitmap bitmap) {
                            return bitmap.getByteCount() / 1024;
                        }
                    };

            void shutdown() {
                pool.shutdownNow();
                cache.evictAll();
            }

            /** Main thread. */
            void load(Item item, ImageView view) {
                String key = item.key();
                view.setTag(key);
                Bitmap hit = cache.get(key);
                if (hit != null) {
                    view.setImageBitmap(hit);
                    return;
                }
                if (pool.isShutdown()) return;
                int size = Math.max(1, tileSize);
                pool.execute(() -> {
                    Bitmap bitmap;
                    try {
                        bitmap = thumbnail(item, size);
                    } catch (Exception e) {
                        Logger.printInfo(() -> "Private gallery: no thumbnail for " + key, e);
                        return;
                    } catch (OutOfMemoryError e) {
                        return;
                    }
                    if (bitmap == null) return;
                    cache.put(key, bitmap);
                    ui.post(() -> {
                        if (!closed && key.equals(view.getTag())) view.setImageBitmap(bitmap);
                    });
                });
            }

            /** Pool thread. */
            private Bitmap thumbnail(Item item, int size) throws Exception {
                if (!item.photo() && !item.video()) return null;
                File cached = item.vault ? null : new File(dir, item.id + ".jpg");
                if (cached != null && cached.isFile()) {
                    Bitmap disk = BitmapFactory.decodeFile(cached.getPath());
                    if (disk != null) return disk;
                }
                ViewerMedia media;
                if (item.vault) {
                    MediaVault.Stored stored = MediaVault.get().open(item.id);
                    if (stored == null) return null;
                    media = stored.file;
                } else {
                    media = new PlainMedia(item.file);
                }
                Bitmap bitmap;
                try {
                    bitmap = item.photo() ? photo(media, size) : frame(media, size);
                } finally {
                    media.close();
                }
                if (bitmap != null && cached != null) writeJpeg(cached, bitmap);
                return bitmap;
            }

            private void writeJpeg(File file, Bitmap bitmap) {
                try {
                    if (!dir.isDirectory() && !dir.mkdirs()) return;
                    try (FileOutputStream out = new FileOutputStream(file)) {
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out);
                    }
                } catch (Exception e) {
                    file.delete();
                }
            }
        }

        private static Bitmap photo(ViewerMedia media, int size) {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(media.openStream(), null, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= size && bounds.outHeight / (sample * 2) >= size) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            return BitmapFactory.decodeStream(media.openStream(), null, options);
        }

        private static Bitmap frame(ViewerMedia media, int size) {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(new Source(media));
                Bitmap frame = retriever.getFrameAtTime(0);
                if (frame == null) return null;
                float scale = (float) size / Math.min(frame.getWidth(), frame.getHeight());
                if (scale >= 1) return frame;
                Bitmap scaled = Bitmap.createScaledBitmap(frame, Math.max(1, Math.round(frame.getWidth() * scale)),
                        Math.max(1, Math.round(frame.getHeight() * scale)), true);
                if (scaled != frame) frame.recycle();
                return scaled;
            } finally {
                try {
                    retriever.release();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** Feeds the retriever bytes straight from the media; the caller closes the media. */
    private static final class Source extends MediaDataSource {
        private final ViewerMedia media;

        Source(ViewerMedia media) {
            this.media = media;
        }

        @Override
        public int readAt(long position, byte[] buffer, int offset, int size) throws IOException {
            return media.readAt(position, buffer, offset, size);
        }

        @Override
        public long getSize() {
            return media.length();
        }

        @Override
        public void close() {
        }
    }
}
