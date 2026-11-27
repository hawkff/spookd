package app.morphe.extension.instagram.patches.instants.upload;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentManager;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;

import app.morphe.extension.instagram.entity.InstagramDialogBox;
import app.morphe.extension.instagram.patches.HomeLongPress;
import app.morphe.extension.instagram.patches.download.gallery.Gallery;
import app.morphe.extension.instagram.patches.download.gallery.picker.GalleryPicker;
import app.morphe.extension.instagram.settings.SettingsStatus;
import app.morphe.extension.instagram.settings.preference.widgets.SpookdType;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;

/**
 * The Instants camera's button that sends a photo or video picked on the phone or in the private
 * gallery. It goes into the camera's own handlers for a taken photo and a recorded video, so
 * Instagram builds, crops and sends it like a capture and its send hooks still apply.
 */
@SuppressWarnings({"unused", "deprecation"})
public final class InstantsUpload {
    private static final String PICKER_TAG = "spookd_instants_upload_picker";
    private static final String VIDEO_DIR = "spookd_instants_upload";
    // About 32 MB as ARGB, for a photo whose format has no region decoding.
    private static final long MAX_DECODE_PIXELS = 8_000_000L;
    static final long MAX_VIDEO_BYTES = 1L << 30;
    static final long MIN_FREE_BYTES = 200L << 20;
    // Main thread only.
    private static final Rect visibleRect = new Rect();
    // Instagram uploads a sent video from its file, so copies stay this long.
    private static final long VIDEO_KEEP_MS = 24 * 60 * 60 * 1000L;
    private InstantsUpload() {
    }

    /** End of the Instants camera's setup, with its preview and its shutter; any thread, never throws. */
    public static void onCameraReady(View preview, Object cameraShutter) {
        try {
            if (cameraShutter == null || !(preview instanceof ViewGroup) || !Pref.sendMediaAsInstants()) return;
            preview.post(() -> attach((ViewGroup) preview, cameraShutter));
        } catch (Throwable t) {
            Logger.printException(() -> "InstantsUpload: cannot watch the camera", t);
        }
    }

    /** Main thread: a round button at the bottom start of the visible square, once per preview. */
    private static void attach(ViewGroup preview, Object cameraShutter) {
        try {
            for (int i = 0; i < preview.getChildCount(); i++) {
                Object tag = preview.getChildAt(i).getTag();
                if (tag instanceof Camera) {
                    // The same preview set up again: its picks go to the new shutter.
                    ((Camera) tag).shutter = new WeakReference<>(cameraShutter);
                    return;
                }
            }
            Camera camera = new Camera(preview, cameraShutter);
            Context context = preview.getContext();
            float density = context.getResources().getDisplayMetrics().density;
            ImageView button = new ImageView(context);
            button.setTag(camera);
            int icon = ResourceUtils.getIdentifier(ResourceType.DRAWABLE, "instagram_photo_add_filled_24");
            button.setImageResource(icon != 0 ? icon : android.R.drawable.ic_menu_gallery);
            button.setColorFilter(Color.WHITE);
            int padding = (int) (SpookdType.SPACE_M * density);
            button.setPadding(padding, padding, padding, padding);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(0x66000000);
            button.setBackground(circle);
            button.setContentDescription(str("spookd_instants_upload_button"));
            button.setOnClickListener(v -> choose(v, camera));
            int size = (int) (SpookdType.MIN_TOUCH * density);
            preview.addView(button, new ViewGroup.LayoutParams(size, size));
            preview.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> place(preview, button));
        } catch (Throwable t) {
            Logger.printException(() -> "InstantsUpload: cannot add the button", t);
        }
    }

    /** Main thread, after each preview layout. */
    private static void place(ViewGroup preview, View button) {
        try {
            float margin = horizontalMarginNative(preview);
            float top = verticalOffsetNative(preview);
            float inset = SpookdType.SPACE_M * preview.getResources().getDisplayMetrics().density;
            float bottom = Math.min(top + preview.getWidth() - 2 * margin, preview.getHeight());
            button.setTranslationX(margin + inset);
            button.setTranslationY(bottom - inset - button.getHeight());
        } catch (Throwable t) {
            Logger.printException(() -> "InstantsUpload: cannot place the button", t);
        }
    }

    /** Main thread. */
    private static void choose(View button, Camera camera) {
        try {
            if (!Pref.sendMediaAsInstants()) {
                button.setVisibility(View.GONE);
                return;
            }
            Activity activity = HomeLongPress.activityOf(button.getContext());
            CharSequence phone = str("spookd_instants_upload_from_phone");
            // Same condition as the chat picker's entry; the gallery asks for the screen lock itself.
            CharSequence[] items = SettingsStatus.downloadSection() && Gallery.canOpen(activity)
                    ? new CharSequence[]{phone, str("spookd_gallery_picker_entry")}
                    : new CharSequence[]{phone};
            InstagramDialogBox dialog = new InstagramDialogBox(activity);
            dialog.addDialogMenuItems(items, (d, which) -> pick(activity, which == 1, camera));
            dialog.getDialog().show();
        } catch (Throwable t) {
            failed("open the choice", t);
        }
    }

    /** Main thread: a headless fragment of the camera's screen opens the picker and gets its result. */
    private static void pick(Activity activity, boolean gallery, Camera camera) {
        try {
            FragmentManager fragments = activity.getFragmentManager();
            if (activity.isFinishing() || fragments.findFragmentByTag(PICKER_TAG) != null) return;
            Picker picker = new Picker();
            picker.camera = camera;
            Bundle args = new Bundle();
            args.putBoolean(Picker.GALLERY, gallery);
            picker.setArguments(args);
            fragments.beginTransaction().add(picker, PICKER_TAG).commitAllowingStateLoss();
        } catch (Throwable t) {
            failed("open the picker", t);
        }
    }

    static Intent phoneIntent() {
        if (Build.VERSION.SDK_INT >= 33) return new Intent(MediaStore.ACTION_PICK_IMAGES);
        return new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
    }

    /** The private gallery in pick mode, launched with the same extras as the chat picker's entry. */
    static Intent galleryIntent(String packageName) {
        return new Intent()
                .setClassName(packageName, "com.instagram.modal.ModalActivity")
                .putExtra("piko", true)
                .putExtra("fragment_arguments", new Bundle())
                .putExtra("piko_launch_type", "private_gallery")
                .putExtra(GalleryPicker.EXTRA_PICK, true);
    }

    /** Background thread. */
    private static void prepare(Context app, Uri uri, Camera camera) {
        try {
            String type = app.getContentResolver().getType(uri);
            if (type != null && type.startsWith("video/")) prepareVideo(app, uri, camera);
            else if (type != null && type.startsWith("image/")) preparePhoto(app, uri, camera);
            else Utils.showToastShort(str("spookd_instants_upload_failed"));
        } catch (Throwable t) {
            failed("read " + uri, t);
        }
    }

    /** Background thread: upright, center-cropped to a square no wider than the screen, like a camera frame. */
    private static void preparePhoto(Context app, Uri uri, Camera camera) throws IOException {
        ContentResolver resolver = app.getContentResolver();
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = resolver.openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("Not a photo: " + uri);
        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in != null) {
                orientation = new ExifInterface(in).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            }
        } catch (IOException | RuntimeException e) {
            Logger.printInfo(() -> "InstantsUpload: no orientation for " + uri, e);
        }
        DisplayMetrics screen = Resources.getSystem().getDisplayMetrics();
        int target = Math.min(screen.widthPixels, screen.heightPixels);
        int[] square = centerSquare(bounds.outWidth, bounds.outHeight);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(square[2], target);
        // Only the centered square is decoded, so a panorama costs no more than a square photo.
        Bitmap decoded = null;
        try (InputStream in = resolver.openInputStream(uri)) {
            BitmapRegionDecoder region = in == null ? null : BitmapRegionDecoder.newInstance(in, false);
            if (region != null) {
                try {
                    decoded = region.decodeRegion(new Rect(square[0], square[1], square[0] + square[2],
                            square[1] + square[2]), options);
                } finally {
                    region.recycle();
                }
            }
        } catch (IOException | RuntimeException e) {
            Logger.printInfo(() -> "InstantsUpload: no region decode for " + uri, e);
        }
        if (decoded == null) {
            // Formats without region decoding are sampled down until the whole photo fits the pixel budget.
            options.inSampleSize = boundedSampleSize(bounds.outWidth, bounds.outHeight, target, MAX_DECODE_PIXELS);
            try (InputStream in = resolver.openInputStream(uri)) {
                decoded = BitmapFactory.decodeStream(in, null, options);
            }
        }
        if (decoded == null) throw new IOException("Cannot decode " + uri);
        int[] crop = centerSquare(decoded.getWidth(), decoded.getHeight());
        Matrix matrix = new Matrix();
        float scale = Math.min(1f, target / (float) crop[2]);
        matrix.setScale(scale, scale);
        matrix.postConcat(exifMatrix(orientation));
        Bitmap photo = Bitmap.createBitmap(decoded, crop[0], crop[1], crop[2], crop[2], matrix, true);
        if (photo != decoded) decoded.recycle();
        Utils.runOnMainThread(() -> sendPhoto(photo, camera));
    }

    /** Main thread. */
    private static void sendPhoto(Bitmap photo, Camera camera) {
        try {
            Object live = camera.live();
            if (live == null) {
                photo.recycle();
                Utils.showToastShort(str("spookd_instants_upload_camera_closed"));
                return;
            }
            // Without a high-quality capture id the frame details are only checked for null.
            capturePhotoNative(live, photo, "", null, null, new Object(), Boolean.FALSE, null, Boolean.FALSE);
        } catch (Throwable t) {
            failed("send a photo", t);
        }
    }

    /** Background thread: copies the video for Instagram's handler, then reads its length and size from the copy. */
    private static void prepareVideo(Context app, Uri uri, Camera camera) throws IOException {
        File dir = new File(app.getCacheDir(), VIDEO_DIR);
        clearOld(dir, System.currentTimeMillis());
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("No folder " + dir);
        File video = new File(dir, "instant_" + System.currentTimeMillis() + ".mp4");
        boolean fits = true;
        try (InputStream in = app.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(video)) {
            if (in == null) throw new IOException("Cannot read " + uri);
            byte[] buffer = new byte[64 * 1024];
            long copied = 0;
            int read;
            while (fits && (read = in.read(buffer)) != -1) {
                copied += read;
                fits = copyFits(copied, dir.getUsableSpace() - read);
                if (fits) out.write(buffer, 0, read);
            }
        } catch (IOException e) {
            video.delete();
            throw e;
        }
        if (!fits) {
            video.delete();
            Utils.showToastLong(str("spookd_instants_upload_too_large"));
            return;
        }
        long[] info = videoInfo(video);
        Utils.runOnMainThread(() -> sendVideo(video, info, camera));
    }

    /** Main thread: the copy is deleted unless Instagram's handler took it. */
    private static void sendVideo(File video, long[] info, Camera camera) {
        boolean handedOff = false;
        try {
            long durationMs = info[0];
            Object live = camera.live();
            if (live == null) {
                Utils.showToastShort(str("spookd_instants_upload_camera_closed"));
                return;
            }
            long maxMs = maxVideoMillisNative(live);
            if (!durationAllowed(durationMs, maxMs)) {
                Utils.showToastLong(durationMs <= 0 ? str("spookd_instants_upload_video_unreadable")
                        : maxMs <= 0 ? str("spookd_instants_upload_failed")
                        : str("spookd_instants_upload_too_long", maxMs / 1000));
                return;
            }
            View preview = camera.preview.get();
            // The camera crops a recording to its square at the preview's offset and never fills the square from a short video.
            if (preview == null || !fitsCameraCrop((int) info[1], (int) info[2], (int) info[3], preview.getWidth(),
                    horizontalMarginNative(preview), verticalOffsetNative(preview))) {
                Utils.showToastLong(str("spookd_instants_upload_video_shape"));
                return;
            }
            captureVideoNative(live, video);
            handedOff = true;
        } catch (Throwable t) {
            failed("send a video", t);
        } finally {
            if (!handedOff) video.delete();
        }
    }

    /** {duration ms, width, height, rotation}; -1 for what can't be read. */
    private static long[] videoInfo(File video) {
        long[] info = {-1, -1, -1, -1};
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(video.getPath());
            int[] keys = {MediaMetadataRetriever.METADATA_KEY_DURATION, MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH,
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT, MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION};
            for (int i = 0; i < keys.length; i++) {
                String value = retriever.extractMetadata(keys[i]);
                if (value != null) info[i] = Long.parseLong(value.trim());
            }
        } catch (RuntimeException e) {
            Logger.printInfo(() -> "InstantsUpload: cannot read " + video, e);
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
        return info;
    }

    private static void clearOld(File dir, long now) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (now - file.lastModified() > VIDEO_KEEP_MS) file.delete();
        }
    }

    /** Any thread. */
    private static void failed(String action, Throwable t) {
        Logger.printException(() -> "InstantsUpload: cannot " + action, t);
        Utils.showToastShort(str("spookd_instants_upload_failed"));
    }

    /** A copy stays within {@link #MAX_VIDEO_BYTES} and leaves at least {@link #MIN_FREE_BYTES} of storage free. */
    static boolean copyFits(long copiedBytes, long freeBytesAfter) {
        return copiedBytes <= MAX_VIDEO_BYTES && freeBytesAfter >= MIN_FREE_BYTES;
    }

    /** A pick goes out only while its camera's preview is shown, its shutter not released and the switch on. */
    static boolean accepts(boolean previewShown, boolean shutterOpen, boolean enabled) {
        return previewShown && shutterOpen && enabled;
    }

    /**
     * Whether the camera's crop of a recording fills its square from this video: scaled to the preview's
     * width, the upright video must reach from the top to the bottom of the square at its offset.
     */
    static boolean fitsCameraCrop(int width, int height, int rotation, float previewWidth, float margin, float offset) {
        boolean turned = rotation == 90 || rotation == 270;
        float uprightWidth = turned ? height : width;
        float uprightHeight = turned ? width : height;
        float side = previewWidth - 2 * margin;
        if (uprightWidth <= 0 || uprightHeight <= 0 || side <= 0 || offset < 0) return false;
        // One pixel of rounding slack.
        return uprightHeight * previewWidth / uprightWidth + 1 >= offset + side;
    }

    /** {x, y, side} of the centered square of a {@code width} by {@code height} image. */
    static int[] centerSquare(int width, int height) {
        int side = Math.min(width, height);
        return new int[]{(width - side) / 2, (height - side) / 2, side};
    }

    /** The largest power-of-two step that keeps the shorter side at least {@code target}. */
    static int sampleSize(int shortSide, int target) {
        int sample = 1;
        while (shortSide / (sample * 2) >= target) sample *= 2;
        return sample;
    }

    /** {@link #sampleSize}, stepped further until the whole decoded photo has at most {@code maxPixels}. */
    static int boundedSampleSize(int width, int height, int target, long maxPixels) {
        int sample = sampleSize(Math.min(width, height), target);
        while ((long) (width / sample) * (height / sample) > maxPixels) sample *= 2;
        return sample;
    }

    /** A known length within the camera's known recording limit; Instagram's send path does not check it again. */
    static boolean durationAllowed(long durationMs, long maxMs) {
        return durationMs > 0 && maxMs > 0 && durationMs <= maxMs;
    }

    /** The turn and mirror that show stored pixels upright for an EXIF orientation. */
    static Matrix exifMatrix(int orientation) {
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.setScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.setRotate(180);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.setRotate(180);
                matrix.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                matrix.setRotate(90);
                matrix.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.setRotate(90);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                matrix.setRotate(-90);
                matrix.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.setRotate(-90);
                break;
            default:
                break;
        }
        return matrix;
    }

    // The patch replaces these bodies with calls into the Instants camera's preview and shutter.

    /** The camera's handler for a taken photo, with its arguments in Instagram's order. */
    public static void capturePhotoNative(Object camera, Bitmap photo, String caption, Object audience,
                                          Long highQualityId, Object frame, Boolean unused, Object mode,
                                          Boolean saveOriginal) {
    }

    /** The camera's handler for a recorded video file. */
    public static void captureVideoNative(Object camera, File video) {
    }

    /** The longest video the camera records, in milliseconds. */
    public static long maxVideoMillisNative(Object camera) {
        return 0;
    }

    /** Whether Instagram released this shutter when its camera closed. */
    public static boolean releasedNative(Object camera) {
        return false;
    }

    public static float horizontalMarginNative(View preview) {
        return 0;
    }

    public static float verticalOffsetNative(View preview) {
        return 0;
    }

    /** One Instants camera preview and the shutter its latest setup built; set on the main thread. */
    static final class Camera {
        private final WeakReference<View> preview;
        volatile WeakReference<Object> shutter;

        Camera(View preview, Object shutter) {
            this.preview = new WeakReference<>(preview);
            this.shutter = new WeakReference<>(shutter);
        }

        /** This camera's shutter while it can still take a pick, else null. */
        Object live() {
            View view = preview.get();
            Object current = shutter.get();
            boolean open = current != null && !releasedNative(current);
            // A camera page swiped out of view stays attached and VISIBLE, so its on-screen area is checked too.
            boolean shown = view != null && view.isShown() && view.getGlobalVisibleRect(visibleRect)
                    && !visibleRect.isEmpty();
            return accepts(shown, open, Pref.sendMediaAsInstants()) ? current : null;
        }
    }

    /** Headless: opens the phone's photo picker or the private gallery and hands the pick on. */
    public static final class Picker extends Fragment {
        static final String GALLERY = "gallery";
        private static final int REQUEST = 0x1a57;
        // The camera whose button opened this picker; gone when the picker is recreated.
        Camera camera;

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            // A recreated picker waits for the result of the one it replaces.
            if (savedInstanceState != null) return;
            try {
                Activity activity = getActivity();
                startActivityForResult(getArguments().getBoolean(GALLERY)
                        ? galleryIntent(activity.getPackageName()) : phoneIntent(), REQUEST);
            } catch (Exception e) {
                Logger.printException(() -> "InstantsUpload: no picker", e);
                Utils.showToastShort(str(e instanceof ActivityNotFoundException
                        ? "spookd_file_picker_unavailable" : "spookd_instants_upload_failed"));
                done();
            }
        }

        @Override
        public void onActivityResult(int requestCode, int resultCode, Intent data) {
            super.onActivityResult(requestCode, resultCode, data);
            if (requestCode != REQUEST) return;
            Uri uri = resultCode == Activity.RESULT_OK && data != null ? data.getData() : null;
            Activity activity = getActivity();
            Camera from = camera;
            if (uri != null && activity != null && from == null) {
                Utils.showToastShort(str("spookd_instants_upload_camera_closed"));
            } else if (uri != null && activity != null) {
                Context app = activity.getApplicationContext();
                Utils.runOnBackgroundThread(() -> prepare(app, uri, from));
            }
            done();
        }

        private void done() {
            FragmentManager fragments = getFragmentManager();
            if (fragments != null) fragments.beginTransaction().remove(this).commitAllowingStateLoss();
        }
    }
}
