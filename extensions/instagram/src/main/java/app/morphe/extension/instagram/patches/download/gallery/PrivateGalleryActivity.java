package app.morphe.extension.instagram.patches.download.gallery;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.media.MediaDataSource;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
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

import app.morphe.extension.crimera.legacydownloader.DownloadRequest;
import app.morphe.extension.crimera.legacydownloader.MediaDownloader;
import app.morphe.extension.instagram.constants.Constants;
import app.morphe.extension.instagram.constants.UI;
import app.morphe.extension.instagram.patches.dm.InboxLock;
import app.morphe.extension.instagram.patches.dm.vault.MediaVault;
import app.morphe.extension.instagram.patches.dm.vault.PlainMedia;
import app.morphe.extension.instagram.patches.dm.vault.VaultViewer;
import app.morphe.extension.instagram.patches.dm.vault.ViewerMedia;
import app.morphe.extension.instagram.patches.download.DownloadUtils;
import app.morphe.extension.instagram.settings.SettingsStatus;
import app.morphe.extension.instagram.settings.preference.widgets.InstagramPreferenceStyle;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * The private gallery, built in code inside the app's modal activity: downloads by tab, and stored
 * DM media in the DMs tab. The UI runs on the main thread; listing, cleanup, deletes and share copies
 * run on one background thread, thumbnails on a small pool, exports on the download writer's thread.
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
        /** Export name and subfolder, as phone-storage mode names the file. */
        final String fileName;
        final String subFolder;
        final String link;
        final String ownerUsername;

        Item(GalleryStore.Entry entry) {
            id = entry.id;
            vault = false;
            kind = entry.item.kind;
            time = entry.savedAt;
            size = entry.sizeBytes;
            durationMs = entry.durationMs;
            file = entry.file;
            fileName = entry.fileName;
            subFolder = entry.subFolder;
            link = entry.item.link;
            ownerUsername = entry.item.ownerUsername;
        }

        Item(MediaVault.Item stored) {
            id = stored.id;
            vault = true;
            kind = stored.kind;
            time = stored.msgTimestamp;
            size = stored.sizeBytes;
            durationMs = null;
            file = null;
            fileName = GalleryActions.dmFileName(stored.itemId, stored.id, stored.mime, stored.kind);
            subFolder = Constants.DEFAULT_DM_FOLDER;
            link = null;
            ownerUsername = stored.senderUsername;
        }

        /** Includes the download's file, which an overwrite replaces, so a cached thumbnail never outlives it. */
        String key() {
            return vault ? "v" + id : "d" + id + "/" + file.getName();
        }

        boolean photo() {
            return GalleryItem.PHOTO.equals(kind);
        }

        boolean video() {
            return GalleryItem.VIDEO.equals(kind);
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
        private Map<GalleryTab, List<Item>> items = new EnumMap<>(GalleryTab.class);
        private GalleryTab tab = GalleryTab.POSTS;
        private TextView[] tabViews;
        private TextView summary;
        private TextView exportAll;
        private FrameLayout content;
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

            LinearLayout header = new LinearLayout(activity);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            summary = new TextView(activity);
            summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            summary.setTextColor(InstagramPreferenceStyle.secondaryTextColor());
            summary.setPadding(dp(16), dp(4), dp(16), dp(8));
            header.addView(summary, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            exportAll = new TextView(activity);
            exportAll.setText(str("piko_gallery_export_all"));
            exportAll.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            exportAll.setTypeface(null, Typeface.BOLD);
            exportAll.setTextColor(InstagramPreferenceStyle.primaryTextColor());
            exportAll.setPadding(dp(16), dp(4), dp(16), dp(8));
            exportAll.setVisibility(View.GONE);
            exportAll.setOnClickListener(v -> confirmExportAll());
            header.addView(exportAll);
            root.addView(header);

            content = new FrameLayout(activity);
            root.addView(content, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
            updateTabs();
            watchLifecycle();
            Context app = activity.getApplicationContext();
            JOBS.execute(() -> GalleryActions.clearShared(app));
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
            content.removeAllViews();
            List<Item> list = items.get(tab);
            boolean empty = list == null || list.isEmpty();
            exportAll.setVisibility(empty ? View.GONE : View.VISIBLE);
            if (empty) {
                content.addView(message(str("piko_gallery_empty")));
                return;
            }
            int gap = dp(2);
            GridView grid = new GridView(activity);
            grid.setNumColumns(COLUMNS);
            grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
            grid.setHorizontalSpacing(gap);
            grid.setVerticalSpacing(gap);
            grid.setPadding(gap, gap, gap, gap);
            grid.setClipToPadding(false);
            grid.setAdapter(new TileAdapter(list));
            grid.setOnItemClickListener((parent, view, position, id) ->
                    VaultViewer.showFor(activity, viewerSource(list.get(position))));
            grid.setOnItemLongClickListener((parent, view, position, id) -> {
                showOptions(list.get(position));
                return true;
            });
            content.addView(grid, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        }

        /** Builds only the visible tiles and reuses them while scrolling. */
        private final class TileAdapter extends BaseAdapter {
            private final List<Item> list;

            TileAdapter(List<Item> list) {
                this.list = list;
            }

            @Override
            public int getCount() {
                return list.size();
            }

            @Override
            public Object getItem(int position) {
                return list.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                Tile tile = convertView instanceof Tile ? (Tile) convertView : new Tile();
                tile.bind(list.get(position));
                return tile;
            }
        }

        /** A square tile: thumbnail, an audio glyph, and a play and duration badge. */
        private final class Tile extends FrameLayout {
            private final ImageView image = new ImageView(activity);
            private final TextView note = new TextView(activity);
            private final TextView badge = new TextView(activity);

            Tile() {
                super(activity);
                setBackgroundColor(themed("igds_color_secondary_background", 0xFF262626));
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                addView(image, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
                note.setText("♪");
                note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32);
                note.setTextColor(InstagramPreferenceStyle.secondaryTextColor());
                note.setGravity(Gravity.CENTER);
                addView(note, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
                badge.setTextColor(0xFFFFFFFF);
                badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                badge.setPadding(dp(4), 0, dp(4), 0);
                badge.setBackgroundColor(0x99000000);
                FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
                badgeParams.gravity = Gravity.BOTTOM | Gravity.END;
                badgeParams.setMargins(0, 0, dp(4), dp(4));
                addView(badge, badgeParams);
            }

            void bind(Item item) {
                setContentDescription(str(item.photo() ? "piko_media_photo"
                        : item.video() ? "piko_media_video" : "piko_gallery_audio"));
                note.setVisibility(item.photo() || item.video() ? View.GONE : View.VISIBLE);
                String duration = item.durationMs == null ? "" : DateUtils.formatElapsedTime(item.durationMs / 1000);
                String badgeText = item.video() ? ("▶ " + duration).trim() : duration;
                badge.setText(badgeText);
                badge.setVisibility(badgeText.isEmpty() ? View.GONE : View.VISIBLE);
                image.setImageBitmap(null);
                thumbs.load(item, image);
            }

            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                super.onMeasure(widthMeasureSpec, widthMeasureSpec);
            }
        }

        /** Opens the item for the viewer on its loader thread. */
        private static Callable<VaultViewer.Opened> viewerSource(Item item) {
            if (!item.vault) return () -> new VaultViewer.Opened(new PlainMedia(item.file), item.kind);
            return () -> {
                MediaVault.Stored stored = MediaVault.get().open(item.id);
                return stored == null ? null : new VaultViewer.Opened(stored.file, stored.kind);
            };
        }

        /** Main thread. Open post and Open profile show only when the item knows its link or owner. */
        private void showOptions(Item item) {
            List<CharSequence> labels = new ArrayList<>();
            List<Runnable> actions = new ArrayList<>();
            labels.add(str("piko_gallery_export"));
            actions.add(() -> export(item));
            labels.add(str("piko_gallery_share"));
            actions.add(() -> share(item));
            labels.add(str("piko_delete"));
            actions.add(() -> confirmDelete(item));
            if (!TextUtils.isEmpty(item.link)) {
                labels.add(str("piko_gallery_open_post"));
                actions.add(() -> view(item.link));
            }
            if (!TextUtils.isEmpty(item.ownerUsername)) {
                labels.add(str("piko_gallery_open_profile"));
                actions.add(() -> view(GalleryActions.profileLink(item.ownerUsername)));
            }
            new AlertDialog.Builder(InstagramPreferenceStyle.dialogContext(activity))
                    .setItems(labels.toArray(new CharSequence[0]), (d, which) -> actions.get(which).run())
                    .show();
        }

        /** The download writer copies the item into the download folder, or asks for the folder first. */
        private static DownloadRequest exportRequest(Item item) {
            DownloadRequest request = DownloadUtils.createDownloadRequest(null, item.subFolder, item.fileName, null);
            request.localSource = () -> GalleryActions.open(item.vault, item.id, item.file);
            return request;
        }

        private void export(Item item) {
            try {
                new MediaDownloader(activity).enqueue(exportRequest(item));
            } catch (Exception e) {
                failed("export " + item.key(), e);
            }
        }

        private void confirmExportAll() {
            List<Item> list = items.get(tab);
            if (list == null || list.isEmpty()) return;
            new AlertDialog.Builder(InstagramPreferenceStyle.dialogContext(activity))
                    .setMessage(str("piko_gallery_export_all_confirm", list.size()))
                    .setPositiveButton(str("piko_gallery_export"), (d, which) -> {
                        try {
                            List<DownloadRequest> requests = new ArrayList<>();
                            for (Item item : list) requests.add(exportRequest(item));
                            GalleryActions.exportAll(activity, requests);
                        } catch (Exception e) {
                            failed("export all", e);
                        }
                    })
                    .setNegativeButton(str("piko_cancel"), null)
                    .show();
        }

        /** Copies on the jobs thread, then offers the copy to other apps from the main thread. */
        private void share(Item item) {
            Context app = activity.getApplicationContext();
            JOBS.execute(() -> {
                Uri uri;
                try {
                    uri = GalleryActions.shareCopy(app, item.fileName, item.vault, item.id, item.file);
                } catch (Exception e) {
                    failed("share " + item.key(), e);
                    return;
                }
                ui.post(() -> {
                    if (closed) return;
                    try {
                        GalleryActions.share(activity, uri, GalleryActions.mimeType(item.fileName, item.kind));
                    } catch (Exception e) {
                        failed("share " + item.key(), e);
                    }
                });
            });
        }

        private void view(String url) {
            try {
                GalleryActions.view(activity, url);
            } catch (Exception e) {
                failed("open " + url, e);
            }
        }

        /** Any thread. */
        private static void failed(String action, Exception e) {
            Logger.printException(() -> "Private gallery: cannot " + action, e);
            Utils.showToastShort(str("piko_gallery_action_failed"));
        }

        private void confirmDelete(Item item) {
            new AlertDialog.Builder(InstagramPreferenceStyle.dialogContext(activity))
                    .setMessage(str("piko_gallery_delete_confirm"))
                    .setPositiveButton(str("piko_delete"), (d, which) -> delete(item))
                    .setNegativeButton(str("piko_cancel"), null)
                    .show();
        }

        /** A download is deleted only while it is still the file shown; either way the screen reloads. */
        private void delete(Item item) {
            JOBS.execute(() -> {
                try {
                    if (item.vault) MediaVault.get().delete(item.id);
                    else GalleryStore.get().delete(item.id, item.file);
                } catch (Exception e) {
                    Logger.printException(() -> "Private gallery: cannot delete " + item.key(), e);
                }
                ui.post(() -> {
                    if (!closed) reload(false);
                });
            });
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
                    // A tile scrolled away and reused meanwhile no longer wants this one; a stale read only decodes once more.
                    if (!key.equals(view.getTag())) return;
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
                File cached = item.vault ? null : GalleryStore.get().thumb(item.file.getName());
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
                // The store writes it only while the item still exists, so a delete meanwhile leaves none.
                if (bitmap != null && cached != null) {
                    try {
                        GalleryStore.get().writeThumb(item.id, item.file,
                                out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out));
                    } catch (Exception e) {
                        Logger.printInfo(() -> "Private gallery: thumbnail not cached for " + item.key(), e);
                    }
                }
                return bitmap;
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
