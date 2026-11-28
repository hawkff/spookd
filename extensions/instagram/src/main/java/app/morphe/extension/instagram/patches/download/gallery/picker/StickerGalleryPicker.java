package app.morphe.extension.instagram.patches.download.gallery.picker;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentManager;
import android.content.Context;
import android.content.Intent;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.net.URLConnection;
import java.util.List;

import app.morphe.extension.instagram.patches.HomeLongPress;
import app.morphe.extension.instagram.patches.download.gallery.Gallery;
import app.morphe.extension.instagram.settings.preference.widgets.SpookdType;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * The From private gallery pill of the story photo sticker's gallery grid. The pick goes to the
 * grid's own handler for a tapped cell, so Instagram adds it as it would a photo from the phone.
 */
@SuppressWarnings({"unused", "deprecation"})
public final class StickerGalleryPicker {
    private static final String PICKER_TAG = "spookd_sticker_gallery_picker";
    private static final String STORY_GRID = "story-sticker-gallery";
    // GalleryActions keeps picks in cache/<SHARE_DIR>/<PICK_PREFIX><id>/<name> and serves them under the same path.
    static final String SHARE_DIR = "piko_share";
    static final String PICK_PREFIX = "pick-";
    private static volatile Constructor<?> mediumConstructor;
    // Main thread only.
    private static boolean noHostLogged;
    // The latest pill tap; a result of an earlier one is dropped. Main thread only.
    private static int currentLaunch;

    private StickerGalleryPicker() {
    }

    /** Main thread, at the start of each refresh of a sticker gallery grid; never throws. */
    public static void onGridShown(Object grid) {
        try {
            View container = containerNative(grid);
            if (container == null) return;
            boolean show = Pref.storyStickerGallery() && STORY_GRID.equals(moduleNameNative(grid))
                    && Gallery.canOpen(container.getContext());
            FrameLayout host = hostOf(container);
            if (host == null) {
                if (show && !noHostLogged) Logger.printInfo(() -> "StickerGalleryPicker: no frame around the grid");
                noHostLogged |= show;
                return;
            }
            View pill = host.findViewWithTag(PICKER_TAG);
            if (pill == null && show) {
                float density = container.getResources().getDisplayMetrics().density;
                pill = GalleryPicker.pill(container, v -> open(grid, v));
                pill.setTag(PICKER_TAG);
                // Above the grid for drawing and touch, without a shadow.
                pill.setOutlineProvider(null);
                pill.setTranslationZ(100 * density);
                host.addView(pill, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START));
                new Placer(container, host, pill, (int) (SpookdType.SPACE_S * density));
            }
            if (pill == null) return;
            // The placer shows it only while the switch allows it and the grid is shown.
            pill.setEnabled(show);
            // The same views may be refreshed by a newer grid.
            pill.setOnClickListener(v -> open(grid, v));
        } catch (Throwable t) {
            Logger.printException(() -> "StickerGalleryPicker: cannot add the entry", t);
        }
    }

    /** The nearest frame holding the grid's container, which may be the container itself; else null. */
    static FrameLayout hostOf(View container) {
        for (Object view = container; view instanceof View; view = ((View) view).getParent()) {
            if (view instanceof FrameLayout) return (FrameLayout) view;
        }
        return null;
    }

    /** {x, y} that put a {@code pillWidth} pill {@code margin} inside the top end of the container at {@code containerAt}. */
    static int[] topEnd(int[] containerAt, int containerWidth, int pillWidth, int margin) {
        return new int[]{containerAt[0] + containerWidth - margin - pillWidth, containerAt[1] + margin};
    }

    /**
     * Keeps the pill in its frame at the top end of the grid's container before each draw, and hidden
     * while Instagram hides the grid; the tray switches pages by visibility and alpha, without a layout.
     */
    private static final class Placer implements View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
        private final View container;
        private final View host;
        private final View pill;
        private final int margin;
        private final int[] containerAt = new int[2];
        private final int[] hostAt = new int[2];
        private ViewTreeObserver observer;

        Placer(View container, View host, View pill, int margin) {
            this.container = container;
            this.host = host;
            this.pill = pill;
            this.margin = margin;
            pill.addOnAttachStateChangeListener(this);
            if (pill.isAttachedToWindow()) onViewAttachedToWindow(pill);
        }

        @Override
        public void onViewAttachedToWindow(View v) {
            if (observer != null) return;
            observer = pill.getViewTreeObserver();
            observer.addOnPreDrawListener(this);
        }

        @Override
        public void onViewDetachedFromWindow(View v) {
            if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this);
            observer = null;
        }

        @Override
        public boolean onPreDraw() {
            try {
                boolean shown = pill.isEnabled() && container.isShown() && container.getAlpha() > 0f;
                int visibility = shown ? View.VISIBLE : View.GONE;
                // Each change asks for a draw, which calls this again with the same values.
                if (pill.getVisibility() != visibility) pill.setVisibility(visibility);
                if (!shown) return true;
                container.getLocationInWindow(containerAt);
                host.getLocationInWindow(hostAt);
                containerAt[0] -= hostAt[0];
                containerAt[1] -= hostAt[1];
                int[] at = topEnd(containerAt, container.getWidth(), pill.getWidth(), margin);
                float x = at[0] - pill.getLeft();
                float y = at[1] - pill.getTop();
                if (pill.getTranslationX() != x) pill.setTranslationX(x);
                if (pill.getTranslationY() != y) pill.setTranslationY(y);
            } catch (Throwable t) {
                Logger.printException(() -> "StickerGalleryPicker: cannot place the entry", t);
            }
            return true;
        }
    }

    /** Main thread: a headless fragment of the editor's screen opens the gallery and gets its result. */
    private static void open(Object grid, View pill) {
        try {
            if (!Pref.storyStickerGallery()) {
                pill.setVisibility(View.GONE);
                return;
            }
            Activity activity = HomeLongPress.activityOf(pill.getContext());
            FragmentManager fragments = activity.getFragmentManager();
            if (activity.isFinishing() || fragments.findFragmentByTag(PICKER_TAG) != null) return;
            Picker picker = new Picker();
            picker.grid = new WeakReference<>(grid);
            picker.launch = ++currentLaunch;
            Bundle args = new Bundle();
            args.putBoolean(GalleryPicker.EXTRA_PHOTOS_ONLY, !allowsVideo(grid));
            picker.setArguments(args);
            fragments.beginTransaction().add(picker, PICKER_TAG).commitAllowingStateLoss();
        } catch (Throwable t) {
            failed("open the gallery", t);
        }
    }

    /**
     * The picked copy behind {@code uri}: a file of one pick folder in the share folder of
     * {@code cacheDir}; null for any other URI, so nothing else in the app can be handed on.
     */
    static File pickedFile(File cacheDir, Uri uri) {
        if (uri == null || !"content".equals(uri.getScheme())) return null;
        List<String> path = uri.getPathSegments();
        if (path.size() != 4 || !"cache".equals(path.get(0)) || !SHARE_DIR.equals(path.get(1))) return null;
        String folder = path.get(2);
        String name = path.get(3);
        if (!folder.startsWith(PICK_PREFIX) || folder.indexOf('/') >= 0 || name.indexOf('/') >= 0
                || name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            return null;
        }
        return new File(new File(new File(cacheDir, SHARE_DIR), folder), name);
    }

    /** Background thread: checks the copy and reads a video's length, then hands it to the grid. */
    private static void prepare(Context app, Uri uri, WeakReference<Object> target, int launch) {
        try {
            File file = pickedFile(app.getCacheDir(), uri);
            String type = file == null ? null : URLConnection.guessContentTypeFromName(file.getName());
            boolean video = type != null && type.startsWith("video/");
            if (file == null || !file.isFile() || type == null || !video && !type.startsWith("image/")) {
                failed("read " + uri, null);
                return;
            }
            int length = video ? durationMs(file) : 0;
            if (video && length <= 0) {
                failed("read the length of " + file, null);
                return;
            }
            Utils.runOnMainThread(() -> deliver(target, launch, file, video, length));
        } catch (Throwable t) {
            failed("read " + uri, t);
        }
    }

    /** Main thread: Instagram's handler for a tapped cell, while the grid that asked is still shown. */
    private static void deliver(WeakReference<Object> target, int launch, File file, boolean video, int durationMs) {
        try {
            Object grid = target.get();
            if (grid == null || !stillShown(containerNative(grid), launch) || !Pref.storyStickerGallery()) {
                Utils.showToastShort(str("spookd_story_sticker_gallery_closed"));
                return;
            }
            // The gallery lists videos only for a grid that takes them; this keeps it so.
            if (video && !allowsVideo(grid)) {
                failed("add a video to a photo only grid", null);
                return;
            }
            pickNative(grid, medium(file, video, durationMs));
        } catch (Throwable t) {
            failed("add the sticker", t);
        }
    }

    /**
     * Main thread: the pick of tap {@code launch} still goes to its grid. Instagram hides a grid it
     * leaves without detaching it, so the grid must be shown and no later tap opened the gallery.
     */
    static boolean stillShown(View container, int launch) {
        return container != null && launch == currentLaunch && container.isShown();
    }

    /** As Instagram builds one for a file of its own: no media store id, 1 for a photo and 3 for a video, upright. */
    private static Object medium(File file, boolean video, int durationMs) throws ReflectiveOperationException {
        Constructor<?> constructor = mediumConstructor;
        if (constructor == null) {
            constructor = Class.forName("com.instagram.common.gallery.Medium").getConstructor(Uri.class,
                    String.class, int.class, int.class, int.class, int.class, long.class, long.class);
            mediumConstructor = constructor;
        }
        long now = System.currentTimeMillis();
        return constructor.newInstance(Uri.fromFile(file), file.getAbsolutePath(), 0, video ? 3 : 1, 0,
                durationMs, now / 1000, now);
    }

    /** Background thread; 0 when the length cannot be read. */
    private static int durationMs(File video) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(video.getPath());
            String value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return value == null ? 0 : (int) Math.min(Integer.MAX_VALUE, Long.parseLong(value.trim()));
        } catch (RuntimeException e) {
            Logger.printInfo(() -> "StickerGalleryPicker: cannot read " + video, e);
            return 0;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    /** Any thread; {@code t} may be null for a refused pick. */
    private static void failed(String action, Throwable t) {
        Logger.printException(() -> "StickerGalleryPicker: cannot " + action, t);
        Utils.showToastShort(str("spookd_story_sticker_gallery_failed"));
    }

    // The patch replaces these bodies with reads and calls on Instagram's sticker gallery grid.

    /** The view holding the grid. */
    public static View containerNative(Object grid) {
        return null;
    }

    /** The grid's analytics name, which says the editor it is in. */
    public static String moduleNameNative(Object grid) {
        return null;
    }

    /** Whether the grid hands videos on as videos; it lists only photos otherwise. */
    public static boolean allowsVideo(Object grid) {
        return false;
    }

    /** The grid's handler for a tapped cell. */
    public static void pickNative(Object grid, Object medium) {
    }

    /** Headless: opens the private gallery in pick mode and hands the pick on. */
    public static final class Picker extends Fragment {
        private static final int REQUEST = 0x57c4;
        // The grid whose pill opened this picker and that tap; gone when the picker is recreated.
        WeakReference<Object> grid;
        int launch;

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            // A recreated picker waits for the result of the one it replaces.
            if (savedInstanceState != null) return;
            try {
                startActivityForResult(GalleryPicker.pickIntent(getActivity().getPackageName(),
                        getArguments().getBoolean(GalleryPicker.EXTRA_PHOTOS_ONLY)), REQUEST);
            } catch (Exception e) {
                failed("open the gallery", e);
                done();
            }
        }

        @Override
        public void onActivityResult(int requestCode, int resultCode, Intent data) {
            super.onActivityResult(requestCode, resultCode, data);
            if (requestCode != REQUEST) return;
            try {
                Uri uri = resultCode == Activity.RESULT_OK && data != null ? data.getData() : null;
                Activity activity = getActivity();
                WeakReference<Object> target = grid;
                Object from = target == null ? null : target.get();
                if (uri != null && activity != null && (from == null || !stillShown(containerNative(from), launch))) {
                    Utils.showToastShort(str("spookd_story_sticker_gallery_closed"));
                } else if (uri != null && activity != null) {
                    Context app = activity.getApplicationContext();
                    int tap = launch;
                    Utils.runOnBackgroundThread(() -> prepare(app, uri, target, tap));
                }
            } catch (Throwable t) {
                failed("take the pick", t);
            }
            done();
        }

        private void done() {
            FragmentManager fragments = getFragmentManager();
            if (fragments != null) fragments.beginTransaction().remove(this).commitAllowingStateLoss();
        }
    }
}
