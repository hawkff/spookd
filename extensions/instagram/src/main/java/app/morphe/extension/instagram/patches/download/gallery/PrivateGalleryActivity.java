package app.morphe.extension.instagram.patches.download.gallery;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaDataSource;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntConsumer;

import app.morphe.extension.crimera.legacydownloader.DownloadRequest;
import app.morphe.extension.crimera.legacydownloader.MediaDownloader;
import app.morphe.extension.instagram.constants.Constants;
import app.morphe.extension.instagram.constants.UI;
import app.morphe.extension.instagram.patches.dm.InboxLock;
import app.morphe.extension.instagram.patches.dm.vault.MediaVault;
import app.morphe.extension.instagram.patches.dm.vault.PlainMedia;
import app.morphe.extension.instagram.patches.dm.vault.VaultViewer;
import app.morphe.extension.instagram.patches.dm.vault.ViewerMedia;
import app.morphe.extension.instagram.patches.download.AudioPageDownload;
import app.morphe.extension.instagram.patches.download.DownloadUtils;
import app.morphe.extension.instagram.patches.download.gallery.picker.GalleryPicker;
import app.morphe.extension.instagram.settings.SettingsStatus;
import app.morphe.extension.instagram.settings.preference.widgets.InstagramPreferenceStyle;
import app.morphe.extension.instagram.settings.preference.widgets.InstagramSearchStyle;
import app.morphe.extension.instagram.settings.preference.widgets.SpookdType;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.ui.SelectionCheck;

/**
 * The private gallery, built in code inside the app's modal activity: downloads by tab, and stored
 * DM media in the DMs tab. The UI runs on the main thread; listing, cleanup, deletes, stars and share
 * copies run on one background thread, thumbnails on a small pool, exports on the download writer's thread.
 */
public final class PrivateGalleryActivity {
    // Serial, so a delete runs before the reload that follows it.
    private static final Executor JOBS = Executors.newSingleThreadExecutor();
    private static final String DMS = "dms";
    /** Null is All; the rest are {@link GalleryStore} sources. */
    private static final String[] SOURCES = {null, "posts", "reels", "stories", DMS, GalleryStore.SOURCE_UNKNOWN};
    private static final String[] TAB_TITLES = {"spookd_all", "spookd_gallery_tab_posts", "spookd_gallery_tab_reels",
            "spookd_gallery_tab_stories", "spookd_gallery_tab_dms", "spookd_unknown"};
    private static final String[] SORTS = {GalleryStore.SORT_NEWEST, GalleryStore.SORT_OLDEST,
            GalleryStore.SORT_SIZE, GalleryStore.SORT_USERNAME};
    private static final String[] SORT_TITLES = {"spookd_gallery_sort_newest", "spookd_gallery_sort_oldest",
            "spookd_gallery_sort_size", "spookd_gallery_sort_username"};
    private static final int MIN_COLUMNS = 2;
    private static final int MAX_COLUMNS = 5;
    // Its own file, so the layout stays the same on every account of this device.
    private static final String VIEW_PREFS = "piko_gallery_view";
    private static final Map<Activity, Controller> controllers = new WeakHashMap<>();

    private PrivateGalleryActivity() {
    }

    /** Main thread, from the activity's Back dispatch: true when Back only ended a selection. */
    public static boolean handleBackPressed(Activity activity) {
        Controller controller = controllers.get(activity);
        if (controller == null || !controller.selecting) return false;
        controller.cancelSelection();
        return true;
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
        /** A {@link GalleryStore} source. */
        final String source;
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
        /** String resource of the view-once or view-twice mark; null for none. */
        final String mark;
        /** Main thread once shown. */
        boolean favorite;

        Item(GalleryStore.Entry entry) {
            id = entry.id;
            vault = false;
            source = entry.source;
            kind = entry.item.kind;
            time = entry.savedAt;
            size = entry.sizeBytes;
            durationMs = entry.durationMs;
            file = entry.file;
            fileName = entry.fileName;
            subFolder = entry.subFolder;
            link = entry.item.link;
            ownerUsername = entry.item.ownerUsername;
            mark = null;
            favorite = entry.favorite;
        }

        Item(MediaVault.Item stored, boolean favorite) {
            id = stored.id;
            vault = true;
            source = DMS;
            kind = stored.kind;
            time = stored.msgTimestamp;
            size = stored.sizeBytes;
            durationMs = null;
            file = null;
            fileName = GalleryActions.dmFileName(stored.itemId, stored.id, stored.mime, stored.kind);
            subFolder = Constants.DEFAULT_DM_FOLDER;
            link = null;
            ownerUsername = stored.senderUsername;
            mark = MediaVault.markResource(stored.viewMode);
            this.favorite = favorite;
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

        boolean unknownOwner() {
            return TextUtils.isEmpty(ownerUsername);
        }

        /** The filters {@link GalleryStore#list} applies in SQL, for vault items. */
        boolean matches(String kind, boolean favoritesOnly, String username) {
            return (kind == null || kind.equals(this.kind)) && (!favoritesOnly || favorite)
                    && (username.isEmpty() || (!unknownOwner()
                    && ownerUsername.toLowerCase(Locale.ROOT).contains(username)));
        }
    }

    /** The order {@link GalleryStore#list} sorts in, for lists that merge vault items. */
    private static Comparator<Item> order(String sort) {
        if (GalleryStore.SORT_OLDEST.equals(sort)) return (a, b) -> Long.compare(a.time, b.time);
        if (GalleryStore.SORT_SIZE.equals(sort)) return (a, b) -> Long.compare(b.size, a.size);
        if (GalleryStore.SORT_USERNAME.equals(sort)) {
            return (a, b) -> {
                if (a.unknownOwner() != b.unknownOwner()) return a.unknownOwner() ? 1 : -1;
                int byName = a.unknownOwner() ? 0
                        : String.CASE_INSENSITIVE_ORDER.compare(a.ownerUsername, b.ownerUsername);
                return byName != 0 ? byName : Long.compare(b.time, a.time);
            };
        }
        return (a, b) -> Long.compare(b.time, a.time);
    }

    /** What one load found: the items the view shows, and the totals of everything saved. */
    private static final class Loaded {
        final List<Item> items = new ArrayList<>();
        long count;
        long bytes;
        boolean dmDownloads;
        boolean unknownDownloads;
    }

    /**
     * Runs on the jobs thread: applies the cleanup when asked, then lists what the view shows. A null
     * {@code source} is All; vault items are matched and merged here, downloads in SQL.
     */
    private static Loaded load(boolean prune, String source, String kind, boolean favoritesOnly, String username,
                               String sort) {
        Loaded out = new Loaded();
        try {
            if (prune) Gallery.prune();
            GalleryStore store = GalleryStore.get();
            long[] totals = store.totals();
            out.count = totals[0];
            out.bytes = totals[1];
            out.dmDownloads = store.has(DMS);
            out.unknownDownloads = store.has(GalleryStore.SOURCE_UNKNOWN);
            for (GalleryStore.Entry entry : store.list(source, kind, favoritesOnly, username, sort)) {
                out.items.add(new Item(entry));
            }
        } catch (Exception e) {
            Logger.printException(() -> "Private gallery: cannot list downloads", e);
        }
        if (SettingsStatus.dmMediaVault) {
            try {
                boolean shown = source == null || DMS.equals(source);
                Set<Long> favorites = Collections.emptySet();
                if (shown) {
                    try {
                        favorites = GalleryStore.get().favoriteVaultIds();
                    } catch (Exception e) {
                        Logger.printException(() -> "Private gallery: cannot read DM media stars", e);
                    }
                }
                boolean merged = false;
                // shortcut: reads every stored row per load, bounded by the vault's retention and size cap; page it if either grows.
                for (MediaVault.Item stored : MediaVault.get().storedItems()) {
                    out.count++;
                    out.bytes += stored.sizeBytes;
                    if (!shown) continue;
                    Item item = new Item(stored, favorites.contains(stored.id));
                    if (!item.matches(kind, favoritesOnly, username)) continue;
                    out.items.add(item);
                    merged = true;
                }
                if (merged) out.items.sort(order(sort));
            } catch (Exception e) {
                Logger.printException(() -> "Private gallery: cannot list DM media", e);
            }
        }
        return out;
    }

    private static final class Controller {
        private final Activity activity;
        /** Opened by Instagram's media picker: a tap returns that photo or video. */
        private final boolean picking;
        private final boolean photosOnly;
        private final Handler ui = new Handler(Looper.getMainLooper());
        private final Runnable searchReload = () -> reload(false);
        private List<Item> list = Collections.emptyList();
        private boolean dmDownloads;
        private boolean unknownDownloads;
        private String summaryText = "";
        /** Null is All. */
        private String source;
        private String kind;
        private boolean favoritesOnly;
        private String username = "";
        private String sort;
        private int columns;
        private boolean listView;
        private boolean overlays;
        private SharedPreferences viewPrefs;
        // Identity, so a reload, which makes new items, starts a new selection.
        private final Set<Item> selected = new HashSet<>();
        private boolean selecting;
        private TextView[] tabViews;
        private TextView summary;
        private TextView exportAll;
        private EditText searchField;
        private View controls;
        private View selectionBar;
        private TextView filterAction;
        private TextView favoriteAction;
        private FrameLayout content;
        private GridView grid;
        private BaseAdapter adapter;
        private ThumbLoader thumbs;
        private float density;
        private int tileSize;
        private int loads;
        private boolean closed;
        private boolean sharing; // main thread only
        private String photoLabel;
        private String videoLabel;
        private String audioLabel;
        private String unknownLabel;
        private String[] tabLabels;

        Controller(Activity activity) {
            this.activity = activity;
            Intent intent = activity.getIntent();
            picking = intent != null && intent.getBooleanExtra(GalleryPicker.EXTRA_PICK, false);
            // Back, a cancelled lock or leaving the app return this until an item is picked.
            if (picking) activity.setResult(pickResultCode(null), pickResult(null));
            photosOnly = intent != null && intent.getBooleanExtra(GalleryPicker.EXTRA_PHOTOS_ONLY, false);
            density = activity.getResources().getDisplayMetrics().density;
        }

        void showNotice() {
            buildRoot().addView(message(str("spookd_vault_needs_screen_lock")),
                    new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        }

        void setup() {
            controllers.put(activity, this);
            viewPrefs = activity.getSharedPreferences(VIEW_PREFS, Context.MODE_PRIVATE);
            columns = Math.max(MIN_COLUMNS, Math.min(MAX_COLUMNS, viewPrefs.getInt("columns", 3)));
            listView = viewPrefs.getBoolean("list", false);
            overlays = viewPrefs.getBoolean("overlays", true);
            String savedSort = viewPrefs.getString("sort", GalleryStore.SORT_NEWEST);
            sort = Arrays.asList(SORTS).contains(savedSort) ? savedSort : GalleryStore.SORT_NEWEST;
            photoLabel = str("spookd_media_photo");
            videoLabel = str("spookd_media_video");
            audioLabel = str("spookd_gallery_audio");
            unknownLabel = str("spookd_unknown");
            tabLabels = new String[SOURCES.length];
            for (int i = 0; i < SOURCES.length; i++) tabLabels[i] = str(TAB_TITLES[i]);
            updateTileSize();
            thumbs = new ThumbLoader();
            LinearLayout root = buildRoot();

            LinearLayout tabsRow = new LinearLayout(activity);
            tabsRow.setOrientation(LinearLayout.HORIZONTAL);
            tabViews = new TextView[SOURCES.length];
            for (int i = 0; i < SOURCES.length; i++) {
                String target = SOURCES[i];
                TextView view = new TextView(activity);
                view.setText(tabLabels[i]);
                SpookdType.apply(view, SpookdType.LABEL);
                view.setGravity(Gravity.CENTER);
                view.setSingleLine(true);
                view.setEllipsize(TextUtils.TruncateAt.END);
                view.setPadding(dp(2), dp(12), dp(2), dp(12));
                view.setOnClickListener(v -> select(target));
                tabsRow.addView(view, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
                tabViews[i] = view;
            }
            root.addView(tabsRow);

            controls = buildControls();
            root.addView(controls);
            selectionBar = buildSelectionBar();
            selectionBar.setVisibility(View.GONE);
            root.addView(selectionBar);

            LinearLayout header = new LinearLayout(activity);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            summary = new TextView(activity);
            SpookdType.apply(summary, SpookdType.SECONDARY);
            summary.setTextColor(InstagramPreferenceStyle.secondaryTextColor());
            summary.setPadding(dp(16), dp(4), dp(16), dp(8));
            header.addView(summary, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            exportAll = new TextView(activity);
            exportAll.setText(str("spookd_gallery_export_all"));
            SpookdType.apply(exportAll, SpookdType.LABEL);
            exportAll.setTextColor(InstagramPreferenceStyle.primaryTextColor());
            exportAll.setPadding(dp(16), dp(4), dp(16), dp(8));
            exportAll.setVisibility(View.GONE);
            exportAll.setOnClickListener(v -> {
                if (selecting) toggleAll();
                else confirmExportAll();
            });
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
            back.setOnClickListener(v -> {
                if (selecting) cancelSelection();
                else activity.finish();
            });
            TextView title = new TextView(activity);
            title.setText(str("spookd_private_gallery"));
            title.setTextColor(InstagramPreferenceStyle.primaryTextColor());
            InstagramPreferenceStyle.applyToolbarLayout(activity, toolbar, back, title);
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
                                    if (selecting) cancelSelection();
                                    else activity.finish();
                                }
                            });
                } catch (Throwable ignored) {
                }
            }
            activity.setContentView(root);
            return root;
        }

        /** Username search, then Sort, Filter and View. */
        private View buildControls() {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), 0, dp(4), 0);
            EditText search = new EditText(activity);
            searchField = search;
            search.setSingleLine(true);
            search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
            search.setHint(str("spookd_search_username"));
            search.setHintTextColor(InstagramPreferenceStyle.secondaryTextColor());
            search.setTextColor(InstagramPreferenceStyle.primaryTextColor());
            SpookdType.apply(search, SpookdType.BODY);
            GradientDrawable background = new GradientDrawable();
            background.setColor(InstagramPreferenceStyle.searchBackgroundColor());
            background.setCornerRadius(dp(12));
            search.setBackground(background);
            search.setPadding(dp(12), dp(8), dp(12), dp(8));
            InstagramSearchStyle.applyInputStyle(search);
            search.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
                @Override public void afterTextChanged(Editable s) {
                    InstagramSearchStyle.removeBackgroundSpans(s);
                    String typed = s.toString().trim().toLowerCase(Locale.ROOT);
                    if (typed.startsWith("@")) typed = typed.substring(1);
                    if (typed.equals(username)) return;
                    username = typed;
                    // Typing reloads once it pauses, not per letter.
                    ui.removeCallbacks(searchReload);
                    ui.postDelayed(searchReload, 250);
                }
            });
            row.addView(search, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            row.addView(action(str("spookd_gallery_sort"), null, v -> chooseSort()));
            filterAction = action(str("spookd_gallery_filter"), null, v -> chooseFilter());
            row.addView(filterAction);
            row.addView(action(str("spookd_gallery_view"), null, v -> chooseView()));
            return row;
        }

        /** Done, star, Share, Delete and More for the selected items. */
        private View buildSelectionBar() {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(action(str("spookd_gallery_done"), null, v -> cancelSelection()), weighted());
            favoriteAction = action("☆", str("spookd_gallery_favorite"), v -> {
                List<Item> items = selection();
                setFavorite(items, !allFavorite(items));
            });
            row.addView(favoriteAction, weighted());
            row.addView(action(str("spookd_gallery_share"), null, v -> share(selection())), weighted());
            row.addView(action(str("spookd_delete"), null, v -> confirmDelete(selection())), weighted());
            row.addView(action(str("spookd_gallery_more"), null, v -> showOptions(selection())), weighted());
            return row;
        }

        private LinearLayout.LayoutParams weighted() {
            return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        }

        private TextView action(String text, String description, View.OnClickListener click) {
            TextView view = new TextView(activity);
            view.setText(text);
            view.setContentDescription(description);
            SpookdType.apply(view, SpookdType.LABEL);
            view.setTextColor(InstagramPreferenceStyle.primaryTextColor());
            view.setGravity(Gravity.CENTER);
            view.setSingleLine(true);
            view.setEllipsize(TextUtils.TruncateAt.END);
            view.setMinHeight(dp(SpookdType.MIN_TOUCH));
            view.setPadding(dp(8), 0, dp(8), 0);
            view.setOnClickListener(click);
            return view;
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
                    controllers.remove(a);
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
            ui.removeCallbacks(searchReload);
            String source = this.source;
            String kind = this.kind;
            boolean favoritesOnly = this.favoritesOnly;
            String username = this.username;
            String sort = this.sort;
            JOBS.execute(() -> {
                Loaded loaded = load(prune, source, kind, favoritesOnly, username, sort);
                ui.post(() -> {
                    if (!closed && generation == loads) show(loaded);
                });
            });
        }

        private void show(Loaded loaded) {
            if (picking) loaded.items.removeIf(item -> !pickable(item.kind, photosOnly));
            list = loaded.items;
            dmDownloads = loaded.dmDownloads;
            unknownDownloads = loaded.unknownDownloads;
            selected.clear();
            selecting = false;
            summaryText = String.format(Locale.getDefault(), str("spookd_gallery_summary"), loaded.count,
                    Formatter.formatShortFileSize(activity, loaded.bytes));
            if (source != null && !shown(source)) {
                source = null;
                updateTabs();
                reload(false);
                return;
            }
            updateTabs();
            rebuildContent();
        }

        // DM downloads exist without the vault patch, so their rows keep the tab.
        private boolean shown(String tab) {
            if (DMS.equals(tab)) return SettingsStatus.dmMediaVault || dmDownloads;
            return !GalleryStore.SOURCE_UNKNOWN.equals(tab) || unknownDownloads;
        }

        private void select(String target) {
            if (Objects.equals(source, target)) return;
            source = target;
            updateTabs();
            reload(false);
        }

        private void updateTabs() {
            for (int i = 0; i < SOURCES.length; i++) {
                boolean selected = Objects.equals(SOURCES[i], source);
                TextView view = tabViews[i];
                view.setVisibility(SOURCES[i] == null || shown(SOURCES[i]) ? View.VISIBLE : View.GONE);
                view.setSelected(selected);
                view.setTypeface(SpookdType.typeface(activity, selected ? 600 : 400));
                view.setTextColor(selected ? InstagramPreferenceStyle.primaryTextColor()
                        : InstagramPreferenceStyle.secondaryTextColor());
            }
        }

        private void updateTileSize() {
            int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
            tileSize = (screenWidth - dp(2) * (columns + 1)) / columns;
        }

        private void rebuildContent() {
            content.removeAllViews();
            grid = null;
            adapter = null;
            updateSelection();
            if (list.isEmpty()) {
                boolean filtered = kind != null || favoritesOnly || !username.isEmpty();
                content.addView(message(str(filtered ? "spookd_gallery_no_matches" : "spookd_gallery_empty")));
                return;
            }
            int gap = listView ? 0 : dp(2);
            GridView view = new GridView(activity);
            view.setNumColumns(listView ? 1 : columns);
            view.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
            view.setHorizontalSpacing(gap);
            view.setVerticalSpacing(gap);
            view.setPadding(gap, gap, gap, gap);
            view.setClipToPadding(false);
            adapter = new TileAdapter();
            view.setAdapter(adapter);
            view.setOnItemClickListener((parent, v, position, id) -> {
                Item item = list.get(position);
                if (picking) pick(item);
                else if (selecting) toggleSelected(item);
                else showViewer(item);
            });
            view.setOnItemLongClickListener((parent, v, position, id) -> {
                if (picking) return false;
                startSelection(list.get(position));
                return true;
            });
            if (!listView) watchPinch(view);
            grid = view;
            content.addView(view, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        }

        /** Spreading two fingers shows fewer, larger tiles; pinching shows more. */
        private void watchPinch(GridView view) {
            ScaleGestureDetector pinch = new ScaleGestureDetector(activity,
                    new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        private float scale;

                        @Override
                        public boolean onScaleBegin(ScaleGestureDetector detector) {
                            scale = 1f;
                            return true;
                        }

                        @Override
                        public boolean onScale(ScaleGestureDetector detector) {
                            scale *= detector.getScaleFactor();
                            if (scale > 1.25f && columns > MIN_COLUMNS) {
                                setColumns(columns - 1);
                                scale = 1f;
                            } else if (scale < 0.8f && columns < MAX_COLUMNS) {
                                setColumns(columns + 1);
                                scale = 1f;
                            }
                            return true;
                        }
                    });
            pinch.setQuickScaleEnabled(false);
            boolean[] cancelled = {false};
            view.setOnTouchListener((v, event) -> {
                pinch.onTouchEvent(event);
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) cancelled[0] = false;
                if (!pinch.isInProgress()) return cancelled[0];
                // The grid would scroll, or take the held fingers for a long press, during the pinch.
                if (!cancelled[0]) {
                    MotionEvent cancel = MotionEvent.obtain(event);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    v.onTouchEvent(cancel);
                    cancel.recycle();
                    cancelled[0] = true;
                }
                return true;
            });
        }

        private void setColumns(int count) {
            columns = count;
            viewPrefs.edit().putInt("columns", count).apply();
            updateTileSize();
            if (grid == null) return;
            int first = grid.getFirstVisiblePosition();
            grid.setNumColumns(count);
            grid.setSelection(first);
        }

        /** Main thread: one tap per choice; a check marks what is on. */
        private void choose(String[] labels, boolean[] checked, IntConsumer pick) {
            CharSequence[] shown = new CharSequence[labels.length];
            for (int i = 0; i < labels.length; i++) shown[i] = (checked[i] ? "✓ " : "") + labels[i];
            new AlertDialog.Builder(InstagramPreferenceStyle.dialogContext(activity))
                    .setItems(shown, (d, which) -> pick.accept(which))
                    .show();
        }

        private void chooseSort() {
            String[] labels = new String[SORTS.length];
            boolean[] checked = new boolean[SORTS.length];
            for (int i = 0; i < SORTS.length; i++) {
                labels[i] = str(SORT_TITLES[i]);
                checked[i] = SORTS[i].equals(sort);
            }
            choose(labels, checked, which -> {
                sort = SORTS[which];
                viewPrefs.edit().putString("sort", sort).apply();
                reload(false);
            });
        }

        private void chooseFilter() {
            String[] labels = {str("spookd_gallery_all_types"), str("spookd_gallery_photos"),
                    str("spookd_gallery_videos"), str("spookd_gallery_favorites_only")};
            boolean[] checked = {kind == null, GalleryItem.PHOTO.equals(kind), GalleryItem.VIDEO.equals(kind),
                    favoritesOnly};
            choose(labels, checked, which -> {
                if (which == 3) favoritesOnly = !favoritesOnly;
                else kind = which == 1 ? GalleryItem.PHOTO : which == 2 ? GalleryItem.VIDEO : null;
                int active = (kind == null ? 0 : 1) + (favoritesOnly ? 1 : 0);
                filterAction.setText(active == 0 ? str("spookd_gallery_filter")
                        : str("spookd_gallery_filter") + " (" + active + ")");
                reload(false);
            });
        }

        private void chooseView() {
            int choices = MAX_COLUMNS - MIN_COLUMNS + 1;
            String[] labels = new String[choices + 2];
            boolean[] checked = new boolean[choices + 2];
            for (int i = 0; i < choices; i++) {
                labels[i] = str("spookd_gallery_columns", MIN_COLUMNS + i);
                checked[i] = !listView && columns == MIN_COLUMNS + i;
            }
            labels[choices] = str("spookd_gallery_list");
            checked[choices] = listView;
            labels[choices + 1] = str("spookd_gallery_overlays");
            checked[choices + 1] = overlays;
            choose(labels, checked, which -> {
                if (which == choices + 1) {
                    overlays = !overlays;
                    viewPrefs.edit().putBoolean("overlays", overlays).apply();
                    if (adapter != null) adapter.notifyDataSetChanged();
                    return;
                }
                listView = which == choices;
                viewPrefs.edit().putBoolean("list", listView).apply();
                if (!listView) setColumns(MIN_COLUMNS + which);
                rebuildContent();
            });
        }

        private void startSelection(Item item) {
            if (!selecting) {
                selecting = true;
                searchField.clearFocus();
                InputMethodManager keyboard = (InputMethodManager)
                        activity.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (keyboard != null) keyboard.hideSoftInputFromWindow(searchField.getWindowToken(), 0);
            }
            selected.add(item);
            updateSelection();
        }

        private void toggleSelected(Item item) {
            if (!selected.remove(item)) selected.add(item);
            if (selected.isEmpty()) selecting = false;
            updateSelection();
        }

        private void toggleAll() {
            if (selected.size() == list.size()) {
                selected.clear();
                selecting = false;
            } else {
                selected.addAll(list);
            }
            updateSelection();
        }

        private void cancelSelection() {
            selected.clear();
            selecting = false;
            updateSelection();
        }

        /** The selected items in the order shown. */
        private List<Item> selection() {
            List<Item> out = new ArrayList<>();
            for (Item item : list) {
                if (selected.contains(item)) out.add(item);
            }
            return out;
        }

        private static boolean allFavorite(List<Item> items) {
            for (Item item : items) {
                if (!item.favorite) return false;
            }
            return !items.isEmpty();
        }

        /** Shows the selection bar while selecting, else the controls, and redraws the tiles. */
        private void updateSelection() {
            controls.setVisibility(selecting ? View.GONE : View.VISIBLE);
            selectionBar.setVisibility(selecting ? View.VISIBLE : View.GONE);
            exportAll.setVisibility(list.isEmpty() || picking ? View.GONE : View.VISIBLE);
            if (selecting) {
                summary.setText(str("spookd_selected_count", selected.size()));
                exportAll.setText(str(selected.size() == list.size()
                        ? "spookd_download_deselect_all" : "spookd_download_select_all"));
                boolean starred = allFavorite(selection());
                favoriteAction.setText(starred ? "★" : "☆");
                favoriteAction.setContentDescription(str(starred
                        ? "spookd_gallery_unfavorite" : "spookd_gallery_favorite"));
            } else {
                summary.setText(summaryText);
                exportAll.setText(str("spookd_gallery_export_all"));
            }
            if (adapter != null) adapter.notifyDataSetChanged();
        }

        /** Builds only the visible tiles or rows and reuses them while scrolling. */
        private final class TileAdapter extends BaseAdapter {
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
                Item item = list.get(position);
                if (listView) {
                    Row row = convertView instanceof Row ? (Row) convertView : new Row();
                    row.bind(item);
                    return row;
                }
                Tile tile = convertView instanceof Tile ? (Tile) convertView : new Tile();
                tile.bind(item);
                return tile;
            }
        }

        private String kindLabel(Item item) {
            return item.photo() ? photoLabel : item.video() ? videoLabel : audioLabel;
        }

        private String ownerLabel(Item item) {
            return item.unknownOwner() ? unknownLabel : "@" + item.ownerUsername;
        }

        private String sourceLabel(Item item) {
            return tabLabels[Arrays.asList(SOURCES).indexOf(item.source)];
        }

        private SelectionCheck selectionCheck(int tint) {
            Context themed = InstagramPreferenceStyle.dialogContext(activity);
            TypedArray attributes = themed.obtainStyledAttributes(new int[]{android.R.attr.listChoiceIndicatorMultiple});
            try {
                SelectionCheck check = new SelectionCheck(themed, attributes.getDrawable(0));
                check.setScaleType(ImageView.ScaleType.FIT_CENTER);
                check.setImageTintList(ColorStateList.valueOf(tint));
                return check;
            } finally {
                attributes.recycle();
            }
        }

        private TextView overlayText(int role) {
            TextView view = new TextView(activity);
            view.setTextColor(Color.WHITE);
            SpookdType.apply(view, role);
            view.setShadowLayer(3, 0, 1, Color.BLACK);
            view.setSingleLine(true);
            view.setEllipsize(TextUtils.TruncateAt.END);
            return view;
        }

        private TextView badge() {
            TextView view = new TextView(activity);
            view.setTextColor(Color.WHITE);
            SpookdType.apply(view, SpookdType.CAPTION);
            view.setPadding(dp(4), 0, dp(4), 0);
            view.setBackgroundColor(0x99000000);
            view.setSingleLine(true);
            view.setEllipsize(TextUtils.TruncateAt.END);
            return view;
        }

        private FrameLayout.LayoutParams corner(int gravity) {
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, gravity);
            params.setMargins(dp(4), dp(4), dp(4), 0);
            return params;
        }

        /**
         * A square tile: thumbnail and audio glyph; source and play, duration and mark badges at the top,
         * each up to half the width; the owner and a star on their own line at the bottom.
         */
        private final class Tile extends FrameLayout {
            private final ImageView image = new ImageView(activity);
            private final TextView note = new TextView(activity);
            private final TextView label = overlayText(SpookdType.CAPTION);
            private final TextView sourceBadge = badge();
            private final TextView badge = badge();
            private final TextView star = overlayText(SpookdType.LABEL);
            private final SelectionCheck check = selectionCheck(Color.WHITE);

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
                LinearLayout bottom = new LinearLayout(activity);
                bottom.setGravity(Gravity.CENTER_VERTICAL);
                bottom.setPadding(dp(4), 0, dp(4), dp(2));
                bottom.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
                star.setText("★");
                bottom.addView(star);
                addView(bottom, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
                addView(sourceBadge, corner(Gravity.TOP | Gravity.START));
                addView(badge, corner(Gravity.TOP | Gravity.END));
                FrameLayout.LayoutParams checkParams = new FrameLayout.LayoutParams(dp(32), dp(32),
                        Gravity.TOP | Gravity.START);
                checkParams.setMargins(dp(4), dp(4), 0, 0);
                addView(check, checkParams);
            }

            void bind(Item item) {
                String what = kindLabel(item);
                if (item.mark != null) what = what + ", " + str(item.mark);
                setContentDescription(what + ", " + ownerLabel(item));
                note.setVisibility(item.photo() || item.video() ? View.GONE : View.VISIBLE);
                String duration = item.durationMs == null ? "" : DateUtils.formatElapsedTime(item.durationMs / 1000);
                String badgeText = item.video() ? ("▶ " + duration).trim() : duration;
                if (item.mark != null) badgeText = (str(item.mark) + " " + badgeText).trim();
                int half = Math.max(1, tileSize / 2 - dp(4));
                badge.setMaxWidth(half);
                badge.setText(badgeText);
                badge.setVisibility(badgeText.isEmpty() ? View.GONE : View.VISIBLE);
                sourceBadge.setMaxWidth(half);
                sourceBadge.setText(sourceLabel(item));
                // Only All mixes sources, and the selection check takes this corner.
                sourceBadge.setVisibility(overlays && source == null && !selecting ? View.VISIBLE : View.GONE);
                label.setText(ownerLabel(item));
                label.setVisibility(overlays ? View.VISIBLE : View.INVISIBLE);
                star.setVisibility(item.favorite ? View.VISIBLE : View.GONE);
                check.setVisibility(selecting ? View.VISIBLE : View.GONE);
                check.setChecked(selected.contains(item));
                image.setAlpha(selecting && selected.contains(item) ? 0.6f : 1f);
                image.setImageBitmap(null);
                thumbs.load(item, image);
            }

            @Override
            public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(info);
                info.setCheckable(selecting);
                info.setChecked(selecting && check.isChecked());
            }

            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                super.onMeasure(widthMeasureSpec, widthMeasureSpec);
            }
        }

        /** A list row: thumbnail, owner, and source, kind, date, size and length. */
        private final class Row extends LinearLayout {
            private final SelectionCheck check = selectionCheck(InstagramPreferenceStyle.primaryTextColor());
            private final ImageView image = new ImageView(activity);
            private final TextView name = new TextView(activity);
            private final TextView details = new TextView(activity);
            private final TextView star = new TextView(activity);

            Row() {
                super(activity);
                setOrientation(HORIZONTAL);
                setGravity(Gravity.CENTER_VERTICAL);
                setPadding(dp(SpookdType.SPACE_L), dp(SpookdType.SPACE_S), dp(SpookdType.SPACE_L),
                        dp(SpookdType.SPACE_S));
                addView(check, new LayoutParams(dp(32), dp(32)));
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                image.setBackgroundColor(themed("igds_color_secondary_background", 0xFF262626));
                LayoutParams imageParams = new LayoutParams(dp(56), dp(56));
                imageParams.setMarginEnd(dp(SpookdType.SPACE_M));
                addView(image, imageParams);
                LinearLayout texts = new LinearLayout(activity);
                texts.setOrientation(VERTICAL);
                SpookdType.apply(name, SpookdType.BODY);
                name.setTextColor(InstagramPreferenceStyle.primaryTextColor());
                name.setSingleLine(true);
                name.setEllipsize(TextUtils.TruncateAt.END);
                SpookdType.apply(details, SpookdType.SECONDARY);
                details.setTextColor(InstagramPreferenceStyle.secondaryTextColor());
                details.setMaxLines(2);
                details.setEllipsize(TextUtils.TruncateAt.END);
                texts.addView(name);
                texts.addView(details);
                addView(texts, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
                star.setText("★");
                SpookdType.apply(star, SpookdType.LABEL);
                star.setTextColor(InstagramPreferenceStyle.primaryTextColor());
                star.setPadding(dp(SpookdType.SPACE_S), 0, 0, 0);
                addView(star);
            }

            void bind(Item item) {
                name.setText(ownerLabel(item));
                StringBuilder text = new StringBuilder(sourceLabel(item)).append(" · ").append(kindLabel(item));
                if (item.durationMs != null) {
                    text.append(" · ").append(DateUtils.formatElapsedTime(item.durationMs / 1000));
                }
                if (item.mark != null) text.append(" · ").append(str(item.mark));
                text.append(" · ").append(DateUtils.formatDateTime(activity, item.time,
                        DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH));
                text.append(" · ").append(Formatter.formatShortFileSize(activity, item.size));
                details.setText(text);
                star.setVisibility(item.favorite ? View.VISIBLE : View.GONE);
                check.setVisibility(selecting ? View.VISIBLE : View.GONE);
                check.setChecked(selected.contains(item));
                image.setImageBitmap(null);
                thumbs.load(item, image);
            }

            @Override
            public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(info);
                info.setCheckable(selecting);
                info.setChecked(selecting && check.isChecked());
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

        /** Main thread. The viewer's button stars or unstars the item. */
        private void showViewer(Item item) {
            VaultViewer.showFor(activity, viewerSource(item),
                    () -> item.favorite ? "★ " + str("spookd_gallery_unfavorite") : "☆ " + str("spookd_gallery_favorite"),
                    () -> setFavorite(Collections.singletonList(item), !item.favorite));
        }

        /** Main thread; the stars are written on the jobs thread, before any reload that follows. */
        private void setFavorite(List<Item> items, boolean favorite) {
            if (items.isEmpty()) return;
            for (Item item : items) item.favorite = favorite;
            JOBS.execute(() -> {
                try {
                    GalleryStore store = GalleryStore.get();
                    for (Item item : items) store.setFavorite(item.vault, item.id, favorite);
                } catch (Exception e) {
                    failed("star", e);
                }
            });
            // Either way the shown favorites change: a star restored in the viewer brings its item back.
            if (favoritesOnly) reload(false);
            else updateSelection();
        }

        /** Main thread. Open post and Open profile show only for one item that knows its link or owner. */
        private void showOptions(List<Item> items) {
            if (items.isEmpty()) return;
            Item item = items.get(0);
            List<CharSequence> labels = new ArrayList<>();
            List<Runnable> actions = new ArrayList<>();
            labels.add(str("spookd_gallery_export"));
            actions.add(() -> export(items));
            if (items.size() == 1 && !TextUtils.isEmpty(item.link)) {
                labels.add(str(AudioPageDownload.isAudioPageLink(item.link)
                        ? "spookd_gallery_open_audio_page" : "spookd_gallery_open_post"));
                actions.add(() -> view(item.link));
            }
            if (items.size() == 1 && !item.unknownOwner()) {
                labels.add(str("spookd_gallery_open_profile"));
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

        private void export(List<Item> items) {
            try {
                if (items.size() == 1) {
                    new MediaDownloader(activity).enqueue(exportRequest(items.get(0)));
                    return;
                }
                List<DownloadRequest> requests = new ArrayList<>();
                for (Item item : items) requests.add(exportRequest(item));
                GalleryActions.exportAll(activity, requests);
            } catch (Exception e) {
                failed("export", e);
            }
        }

        private void confirmExportAll() {
            List<Item> items = list;
            if (items.isEmpty()) return;
            new AlertDialog.Builder(InstagramPreferenceStyle.dialogContext(activity))
                    .setMessage(str("spookd_gallery_export_all_confirm", items.size()))
                    .setPositiveButton(str("spookd_gallery_export"), (d, which) -> {
                        try {
                            List<DownloadRequest> requests = new ArrayList<>();
                            for (Item item : items) requests.add(exportRequest(item));
                            GalleryActions.exportAll(activity, requests);
                        } catch (Exception e) {
                            failed("export all", e);
                        }
                    })
                    .setNegativeButton(str("spookd_cancel"), null)
                    .show();
        }

        /** Copies on the jobs thread, then offers the copies to other apps from the main thread. */
        private void share(List<Item> items) {
            // One share at a time: the next share clears the copies the previous chooser still offers.
            if (sharing || items.isEmpty()) return;
            sharing = true;
            Context app = activity.getApplicationContext();
            JOBS.execute(() -> {
                ArrayList<Uri> uris = new ArrayList<>();
                List<String> types = new ArrayList<>();
                try {
                    File dir = GalleryActions.clearShared(app);
                    for (Item item : items) {
                        uris.add(GalleryActions.shareCopy(app, dir, item.fileName, item.vault, item.id, item.file));
                        types.add(GalleryActions.mimeType(item.fileName, item.kind));
                    }
                } catch (Exception e) {
                    ui.post(() -> sharing = false);
                    failed("share", e);
                    return;
                }
                ui.post(() -> {
                    sharing = false;
                    if (closed) return;
                    try {
                        GalleryActions.share(activity, uris, GalleryActions.commonMimeType(types));
                    } catch (Exception e) {
                        failed("share", e);
                    }
                });
            });
        }

        /**
         * Copies the item on the jobs thread into the share folder, then returns its URI to the picker
         * from the main thread. The copy stays for an hour, so Instagram's later import still finds it.
         */
        private void pick(Item item) {
            if (sharing) return;
            sharing = true;
            Context app = activity.getApplicationContext();
            JOBS.execute(() -> {
                Uri uri;
                try {
                    File dir = new File(app.getCacheDir(), GalleryActions.SHARE_DIR);
                    GalleryActions.clearShared(dir, System.currentTimeMillis(), true);
                    if (!GalleryActions.fitsPickBudget(GalleryActions.pickBytes(dir), item.size)) {
                        ui.post(() -> sharing = false);
                        Utils.showToastShort(str("spookd_gallery_pick_full"));
                        return;
                    }
                    uri = GalleryActions.pickCopy(app, dir, item.fileName, item.vault, item.id, item.file);
                } catch (Exception e) {
                    ui.post(() -> sharing = false);
                    failed("pick", e);
                    return;
                }
                ui.post(() -> {
                    sharing = false;
                    if (closed) return;
                    activity.setResult(pickResultCode(uri), pickResult(uri));
                    activity.finish();
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
            Utils.showToastShort(str("spookd_gallery_action_failed"));
        }

        private void confirmDelete(List<Item> items) {
            if (items.isEmpty()) return;
            new AlertDialog.Builder(InstagramPreferenceStyle.dialogContext(activity))
                    .setMessage(str("spookd_gallery_delete_selected_confirm", items.size()))
                    .setPositiveButton(str("spookd_delete"), (d, which) -> delete(items))
                    .setNegativeButton(str("spookd_cancel"), null)
                    .show();
        }

        /** A download is deleted only while it is still the file shown; either way the screen reloads. */
        private void delete(List<Item> items) {
            JOBS.execute(() -> {
                for (Item item : items) {
                    try {
                        if (item.vault) {
                            MediaVault.get().delete(item.id);
                            GalleryStore.get().setFavorite(true, item.id, false);
                        } else {
                            GalleryStore.get().delete(item.id, item.file);
                        }
                    } catch (Exception e) {
                        Logger.printException(() -> "Private gallery: cannot delete " + item.key(), e);
                    }
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
            SpookdType.apply(view, SpookdType.BODY);
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

        /** A cached thumbnail and the tile size it was made for. */
        private static final class Thumb {
            final Bitmap bitmap;
            final int size;

            Thumb(Bitmap bitmap, int size) {
                this.bitmap = bitmap;
                this.size = size;
            }
        }

        /** Download thumbnails are cached as JPEG; DM thumbnails stay in memory so no plaintext of them reaches disk. */
        private final class ThumbLoader {
            private final ExecutorService pool = Executors.newFixedThreadPool(3);
            private final LruCache<String, Thumb> cache =
                    new LruCache<String, Thumb>((int) (Runtime.getRuntime().maxMemory() / 8192)) {
                        @Override
                        protected int sizeOf(String key, Thumb thumb) {
                            return thumb.bitmap.getByteCount() / 1024;
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
                int size = Math.max(1, tileSize);
                Thumb hit = cache.get(key);
                if (hit != null) {
                    view.setImageBitmap(hit.bitmap);
                    // One made for smaller tiles shows until a sharper one replaces it.
                    if (hit.size >= size) return;
                }
                if (pool.isShutdown()) return;
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
                    cache.put(key, new Thumb(bitmap, size));
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
                    Bitmap disk = decodeFile(cached, size);
                    // A copy made for smaller tiles is made again from the original, then saved over,
                    // unless the original is no larger than that copy.
                    if (disk != null) {
                        int shorter = Math.min(disk.getWidth(), disk.getHeight());
                        if (shorter >= size || shorter + 1 >= sourceShorterSide(item, size)) return disk;
                    }
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
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, size);
            return BitmapFactory.decodeStream(media.openStream(), null, options);
        }

        /** Pool thread: the shorter side a thumbnail made now from the download's file would have. */
        private static int sourceShorterSide(Item item, int size) {
            int width = 0;
            int height = 0;
            if (item.photo()) {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(item.file.getPath(), bounds);
                width = bounds.outWidth;
                height = bounds.outHeight;
            } else {
                MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                try {
                    retriever.setDataSource(item.file.getPath());
                    width = dimension(retriever, MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                    height = dimension(retriever, MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                } catch (RuntimeException ignored) {
                } finally {
                    try {
                        retriever.release();
                    } catch (Exception ignored) {
                    }
                }
            }
            return bestShorterSide(width, height, size, item.photo());
        }

        private static Bitmap decodeFile(File file, int size) {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, size);
            return BitmapFactory.decodeFile(file.getPath(), options);
        }

        private static Bitmap frame(ViewerMedia media, int size) {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(new Source(media));
                Bitmap frame = null;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    int box = frameBox(dimension(retriever, MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),
                            dimension(retriever, MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT), size);
                    if (box > 0) {
                        frame = retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                                box, box);
                    }
                }
                if (frame == null) frame = retriever.getFrameAtTime(0);
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

    /**
     * The shorter side of a thumbnail for {@code size} tiles from a {@code width} by {@code height}
     * photo, sampled down, or video frame, never scaled up; above any size when the source's is unknown.
     */
    static int bestShorterSide(int width, int height, int size, boolean photo) {
        if (width <= 0 || height <= 0) return Integer.MAX_VALUE;
        int shorter = Math.min(width, height);
        return photo ? shorter / sampleSize(width, height, size) : Math.min(shorter, size);
    }

    /** RESULT_OK for a picked item's {@code uri}, else RESULT_CANCELED. */
    static int pickResultCode(Uri uri) {
        return uri == null ? Activity.RESULT_CANCELED : Activity.RESULT_OK;
    }

    /** The picked item's {@code uri} with a read grant, as Instagram's picker result carries it; null without one. */
    static Intent pickResult(Uri uri) {
        return uri == null ? null : new Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }

    /** Whether pick mode lists an item of {@code kind}: photos, and videos unless the picker takes photos only. */
    static boolean pickable(String kind, boolean photosOnly) {
        return GalleryItem.PHOTO.equals(kind) || (!photosOnly && GalleryItem.VIDEO.equals(kind));
    }

    /** The largest power-of-two step that keeps both sides at least {@code size}. */
    static int sampleSize(int width, int height, int size) {
        int sample = 1;
        while (width / (sample * 2) >= size && height / (sample * 2) >= size) sample *= 2;
        return sample;
    }

    /**
     * The square a scaled frame is fit into so its shorter side still covers {@code size}, at most
     * four times {@code size} for very long frames; 0 when the video's size is unknown.
     */
    static int frameBox(int width, int height, int size) {
        if (width <= 0 || height <= 0 || size <= 0) return 0;
        return (int) Math.min((long) size * Math.max(width, height) / Math.min(width, height), size * 4L);
    }

    private static int dimension(MediaMetadataRetriever retriever, int key) {
        try {
            return Integer.parseInt(retriever.extractMetadata(key));
        } catch (RuntimeException e) {
            return 0;
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
