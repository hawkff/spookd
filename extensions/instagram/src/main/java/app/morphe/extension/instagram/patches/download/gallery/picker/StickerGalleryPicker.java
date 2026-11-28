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
import android.view.ViewParent;
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
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
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
    private static int listId = -1;
    private static boolean noHostLogged;

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
                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
                int margin = (int) (SpookdType.SPACE_S * density);
                params.setMargins(margin, margin, margin, margin);
                host.addView(pill, params);
            }
            if (pill == null) return;
            pill.setVisibility(show ? View.VISIBLE : View.GONE);
            // The same views may be refreshed by a newer grid.
            pill.setOnClickListener(v -> open(grid, v));
        } catch (Throwable t) {
            Logger.printException(() -> "StickerGalleryPicker: cannot add the entry", t);
        }
    }

    /** The grid's container if it is a frame, else the frame holding its list, else null. */
    private static FrameLayout hostOf(View container) {
        if (container instanceof FrameLayout) return (FrameLayout) container;
        if (listId < 0) listId = ResourceUtils.getIdentifier(ResourceType.ID, "gallery_sticker_grid_recycler_view");
        View list = listId == 0 ? null : container.findViewById(listId);
        ViewParent parent = list == null ? null : list.getParent();
        return parent instanceof FrameLayout ? (FrameLayout) parent : null;
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
    private static void prepare(Context app, Uri uri, WeakReference<Object> target) {
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
            Utils.runOnMainThread(() -> deliver(target, file, video, length));
        } catch (Throwable t) {
            failed("read " + uri, t);
        }
    }

    /** Main thread: Instagram's handler for a tapped cell, while the grid that asked is still shown. */
    private static void deliver(WeakReference<Object> target, File file, boolean video, int durationMs) {
        try {
            Object grid = target.get();
            View container = grid == null ? null : containerNative(grid);
            if (container == null || !container.isAttachedToWindow() || !Pref.storyStickerGallery()) {
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
        // The grid whose pill opened this picker; gone when the picker is recreated.
        WeakReference<Object> grid;

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
                if (uri != null && activity != null && (target == null || target.get() == null)) {
                    Utils.showToastShort(str("spookd_story_sticker_gallery_closed"));
                } else if (uri != null && activity != null) {
                    Context app = activity.getApplicationContext();
                    Utils.runOnBackgroundThread(() -> prepare(app, uri, target));
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
