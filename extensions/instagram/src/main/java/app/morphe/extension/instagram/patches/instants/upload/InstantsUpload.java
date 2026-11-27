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
import android.graphics.Matrix;
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
    private static final String BUTTON_TAG = "spookd_instants_upload";
    private static final String PICKER_TAG = "spookd_instants_upload_picker";
    private static final String VIDEO_DIR = "spookd_instants_upload";
    // Instagram uploads a sent video from its file, so copies stay this long.
    private static final long VIDEO_KEEP_MS = 24 * 60 * 60 * 1000L;
    // The latest Instants camera's shutter; written on the main thread.
    private static volatile WeakReference<Object> shutter = new WeakReference<>(null);

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
            shutter = new WeakReference<>(cameraShutter);
            if (preview.findViewWithTag(BUTTON_TAG) != null) return;
            Context context = preview.getContext();
            float density = context.getResources().getDisplayMetrics().density;
            ImageView button = new ImageView(context);
            button.setTag(BUTTON_TAG);
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
            button.setOnClickListener(InstantsUpload::choose);
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
    private static void choose(View button) {
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
            dialog.addDialogMenuItems(items, (d, which) -> pick(activity, which == 1));
            dialog.getDialog().show();
        } catch (Throwable t) {
            failed("open the choice", t);
        }
    }

    /** Main thread: a headless fragment of the camera's screen opens the picker and gets its result. */
    private static void pick(Activity activity, boolean gallery) {
        try {
            FragmentManager fragments = activity.getFragmentManager();
            if (activity.isFinishing() || fragments.findFragmentByTag(PICKER_TAG) != null) return;
            Picker picker = new Picker();
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
    private static void prepare(Context app, Uri uri) {
        try {
            String type = app.getContentResolver().getType(uri);
            if (type != null && type.startsWith("video/")) prepareVideo(app, uri);
            else if (type != null && type.startsWith("image/")) preparePhoto(app, uri);
            else Utils.showToastShort(str("spookd_instants_upload_failed"));
        } catch (Throwable t) {
            failed("read " + uri, t);
        }
    }

    /** Background thread: upright, center-cropped to a square no wider than the screen, like a camera frame. */
    private static void preparePhoto(Context app, Uri uri) throws IOException {
        ContentResolver resolver = app.getContentResolver();
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = resolver.openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("Not a photo: " + uri);
        int degrees = 0;
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in != null) {
                degrees = exifDegrees(new ExifInterface(in).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL));
            }
        } catch (IOException | RuntimeException e) {
            Logger.printInfo(() -> "InstantsUpload: no orientation for " + uri, e);
        }
        DisplayMetrics screen = Resources.getSystem().getDisplayMetrics();
        int target = Math.min(screen.widthPixels, screen.heightPixels);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(Math.min(bounds.outWidth, bounds.outHeight), target);
        Bitmap decoded;
        try (InputStream in = resolver.openInputStream(uri)) {
            decoded = BitmapFactory.decodeStream(in, null, options);
        }
        if (decoded == null) throw new IOException("Cannot decode " + uri);
        int[] crop = centerSquare(decoded.getWidth(), decoded.getHeight());
        Matrix matrix = new Matrix();
        float scale = Math.min(1f, target / (float) crop[2]);
        matrix.setScale(scale, scale);
        matrix.postRotate(degrees);
        Bitmap photo = Bitmap.createBitmap(decoded, crop[0], crop[1], crop[2], crop[2], matrix, true);
        if (photo != decoded) decoded.recycle();
        Utils.runOnMainThread(() -> sendPhoto(photo));
    }

    /** Main thread. */
    private static void sendPhoto(Bitmap photo) {
        try {
            Object camera = shutter.get();
            if (camera == null) {
                photo.recycle();
                Utils.showToastShort(str("spookd_instants_upload_camera_closed"));
                return;
            }
            // Without a high-quality capture id the frame details are only checked for null.
            capturePhotoNative(camera, photo, "", null, null, new Object(), Boolean.FALSE, null, Boolean.FALSE);
        } catch (Throwable t) {
            failed("send a photo", t);
        }
    }

    /** Background thread: refuses a video longer than the camera records, else copies it for Instagram's handler. */
    private static void prepareVideo(Context app, Uri uri) throws IOException {
        Object camera = shutter.get();
        if (camera == null) {
            Utils.showToastShort(str("spookd_instants_upload_camera_closed"));
            return;
        }
        long maxMs = maxVideoMillisNative(camera);
        if (tooLong(durationMs(app, uri), maxMs)) {
            Utils.showToastLong(str("spookd_instants_upload_too_long", maxMs / 1000));
            return;
        }
        File dir = new File(app.getCacheDir(), VIDEO_DIR);
        clearOld(dir, System.currentTimeMillis());
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("No folder " + dir);
        File video = new File(dir, "instant_" + System.currentTimeMillis() + ".mp4");
        try (InputStream in = app.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(video)) {
            if (in == null) throw new IOException("Cannot read " + uri);
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        } catch (IOException e) {
            video.delete();
            throw e;
        }
        Utils.runOnMainThread(() -> sendVideo(video));
    }

    /** Main thread. */
    private static void sendVideo(File video) {
        try {
            Object camera = shutter.get();
            if (camera == null) {
                video.delete();
                Utils.showToastShort(str("spookd_instants_upload_camera_closed"));
                return;
            }
            captureVideoNative(camera, video);
        } catch (Throwable t) {
            failed("send a video", t);
        }
    }

    /** -1 when unknown. */
    private static long durationMs(Context app, Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(app, uri);
            String value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return value == null ? -1 : Long.parseLong(value);
        } catch (RuntimeException e) {
            return -1;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
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

    /** A known duration over the camera's limit; no limit when Instagram sets none. */
    static boolean tooLong(long durationMs, long maxMs) {
        return maxMs > 0 && durationMs > maxMs;
    }

    /** Clockwise turn for an EXIF orientation; mirrored orientations keep their turn only. */
    static int exifDegrees(int orientation) {
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:
            case ExifInterface.ORIENTATION_TRANSVERSE:
                return 90;
            case ExifInterface.ORIENTATION_ROTATE_180:
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                return 180;
            case ExifInterface.ORIENTATION_ROTATE_270:
            case ExifInterface.ORIENTATION_TRANSPOSE:
                return 270;
            default:
                return 0;
        }
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

    public static float horizontalMarginNative(View preview) {
        return 0;
    }

    public static float verticalOffsetNative(View preview) {
        return 0;
    }

    /** Headless: opens the phone's photo picker or the private gallery and hands the pick on. */
    public static final class Picker extends Fragment {
        static final String GALLERY = "gallery";
        private static final int REQUEST = 0x1a57;

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
            if (uri != null && activity != null) {
                Context app = activity.getApplicationContext();
                Utils.runOnBackgroundThread(() -> prepare(app, uri));
            }
            done();
        }

        private void done() {
            FragmentManager fragments = getFragmentManager();
            if (fragments != null) fragments.beginTransaction().remove(this).commitAllowingStateLoss();
        }
    }
}
