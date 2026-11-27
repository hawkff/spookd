package app.morphe.extension.instagram.patches.download.gallery.editor;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.media.ExifInterface;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import app.morphe.extension.instagram.constants.UI;
import app.morphe.extension.instagram.patches.download.gallery.GalleryItem;
import app.morphe.extension.instagram.patches.download.gallery.GalleryStore;
import app.morphe.extension.instagram.settings.preference.widgets.InstagramPreferenceStyle;
import app.morphe.extension.instagram.settings.preference.widgets.SpookdType;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Full-screen editor of one private gallery photo download: crop, quarter turns, mirror and
 * straighten, saved as a new gallery item beside the untouched original. The screen runs on the
 * main thread; the preview decode and the full-size render and save run on one background thread.
 */
public final class PhotoEditor extends Dialog {
    private static final Executor WORKER = Executors.newSingleThreadExecutor();
    private static final int JPEG_QUALITY = 95;
    private static final int MAX_DEGREES = 45;
    /** Slider steps per degree. */
    private static final int STEPS = 2;
    /** Width over height of each ratio choice: 0 is free, -1 the photo's own. */
    private static final double[] RATIOS = {0, 1, 4 / 5.0, 16 / 9.0, -1};
    private static final String[] RATIO_LABELS = {"spookd_photo_editor_free", "1:1", "4:5", "16:9",
            "spookd_photo_editor_original"};
    /** The edges each crop handle moves, in reading order; a handle's index is its accessibility node id. */
    private static final int[] HANDLE_EDGES = {
            EditGeometry.LEFT | EditGeometry.TOP, EditGeometry.TOP, EditGeometry.TOP | EditGeometry.RIGHT,
            EditGeometry.RIGHT, EditGeometry.RIGHT | EditGeometry.BOTTOM, EditGeometry.BOTTOM,
            EditGeometry.BOTTOM | EditGeometry.LEFT, EditGeometry.LEFT};
    private static final String[] HANDLE_LABELS = {"spookd_photo_editor_handle_top_left",
            "spookd_photo_editor_handle_top", "spookd_photo_editor_handle_top_right",
            "spookd_photo_editor_handle_right", "spookd_photo_editor_handle_bottom_right",
            "spookd_photo_editor_handle_bottom", "spookd_photo_editor_handle_bottom_left",
            "spookd_photo_editor_handle_left"};
    /** Accessibility actions on the frame; their ids are {@link #ACTION_BASE} plus the index. */
    private static final String[] FRAME_ACTIONS = {"spookd_photo_editor_move_left",
            "spookd_photo_editor_move_right", "spookd_photo_editor_move_up", "spookd_photo_editor_move_down",
            "spookd_photo_editor_wider", "spookd_photo_editor_narrower", "spookd_photo_editor_taller",
            "spookd_photo_editor_shorter"};
    private static final int ACTION_BASE = 0x7e5d0000;
    private static final int ACTION_OUTWARD = ACTION_BASE + 8;
    private static final int ACTION_INWARD = ACTION_BASE + 9;
    private static final int HOST = AccessibilityNodeProvider.HOST_VIEW_ID;
    /** Unsaved edits of editors the gallery closed, by photo file path, for this process; main thread only. */
    private static final Map<String, Kept> kept = EditGeometry.recent(8);

    private final Activity activity;
    private final File file;
    private final GalleryItem item;
    private final String subFolder;
    private final String fileName;
    private final Runnable saved;
    private final float density;
    private final CropView cropView;
    private final ProgressBar progress;
    private final SeekBar slider;
    private final TextView angle;
    private final TextView saveAction;
    private final TextView rotateAction;
    private final TextView flipAction;
    private final TextView resetAction;
    private final TextView[] chips = new TextView[RATIOS.length];
    private final int primaryColor = InstagramPreferenceStyle.primaryTextColor();
    private final int backgroundColor = InstagramPreferenceStyle.backgroundColor();
    private final int chipColor = InstagramPreferenceStyle.searchBackgroundColor();

    // Main thread only.
    private Bitmap preview;
    private int sourceWidth;
    private int sourceHeight;
    private boolean png;
    private int uprightTurns;
    private boolean uprightMirror;
    private int turns;
    private boolean mirror;
    private float degrees;
    private int ratioChoice;
    private double ratio;
    /** The crop as last set by hand; straightening scales it into {@link #rect}. */
    private double[] userRect;
    private double[] rect;
    private boolean saving;
    /** Set once Save, Cancel or Reset ends the edit, so closing keeps nothing. */
    private boolean dropped;

    /** An edit to restore the next time the same photo opens in the editor. */
    private static final class Kept {
        final int width;
        final int height;
        final int turns;
        final boolean mirror;
        final float degrees;
        final int ratioChoice;
        final double ratio;
        final double[] userRect;
        final double[] rect;

        Kept(PhotoEditor editor) {
            width = editor.sourceWidth;
            height = editor.sourceHeight;
            turns = editor.turns;
            mirror = editor.mirror;
            degrees = editor.degrees;
            ratioChoice = editor.ratioChoice;
            ratio = editor.ratio;
            userRect = editor.userRect;
            rect = editor.rect;
        }
    }

    /**
     * Main thread: opens the editor on a photo download. After a save {@code saved} runs on the main
     * thread. Never throws; returns null when the editor cannot open.
     */
    public static Dialog show(Activity activity, File file, GalleryItem item, String subFolder, String fileName,
                              Runnable saved) {
        try {
            PhotoEditor editor = new PhotoEditor(activity, file, item, subFolder, fileName, saved);
            editor.show();
            WORKER.execute(editor::load);
            return editor;
        } catch (Exception e) {
            Logger.printException(() -> "PhotoEditor.show failed", e);
            Utils.showToastShort(str("spookd_gallery_action_failed"));
            return null;
        }
    }

    private PhotoEditor(Activity activity, File file, GalleryItem item, String subFolder, String fileName,
                        Runnable saved) {
        super(activity, UI.isDarkMode() ? android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen
                : android.R.style.Theme_DeviceDefault_Light_NoActionBar_Fullscreen);
        this.activity = activity;
        this.file = file;
        this.item = item;
        this.subFolder = subFolder;
        this.fileName = fileName;
        this.saved = saved;
        Context context = getContext();
        density = context.getResources().getDisplayMetrics().density;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().setNavigationBarColor(backgroundColor);
        if (!UI.isDarkMode()) {
            View decor = getWindow().getDecorView();
            decor.setSystemUiVisibility(decor.getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(backgroundColor);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });

        LinearLayout toolbar = new LinearLayout(context);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(SpookdType.SPACE_XS), 0, dp(SpookdType.SPACE_XS), 0);
        toolbar.addView(action(str("spookd_cancel"), v -> leave()));
        TextView title = new TextView(context);
        title.setText(str("spookd_gallery_edit_photo"));
        InstagramPreferenceStyle.applyToolbarTitle(title);
        title.setTextColor(primaryColor);
        title.setGravity(Gravity.CENTER);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        saveAction = action(str("spookd_photo_editor_save"), v -> save());
        saveAction.setTextColor(InstagramPreferenceStyle.selectionColor());
        toolbar.addView(saveAction);
        toolbar.setMinimumHeight(dp(SpookdType.TOOLBAR_HEIGHT));
        root.addView(toolbar);

        FrameLayout stage = new FrameLayout(context);
        cropView = new CropView(context);
        stage.addView(cropView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        progress = new ProgressBar(context);
        stage.addView(progress, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        root.addView(stage, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout straightenRow = new LinearLayout(context);
        straightenRow.setGravity(Gravity.CENTER_VERTICAL);
        straightenRow.setPadding(dp(SpookdType.SPACE_L), 0, dp(SpookdType.SPACE_L), 0);
        TextView label = text(str("spookd_photo_editor_straighten"), SpookdType.LABEL);
        straightenRow.addView(label);
        slider = new SeekBar(context);
        slider.setMax(2 * MAX_DEGREES * STEPS);
        slider.setProgress(MAX_DEGREES * STEPS);
        slider.setContentDescription(label.getText());
        ColorStateList accent = ColorStateList.valueOf(InstagramPreferenceStyle.selectionColor());
        slider.setProgressTintList(accent);
        slider.setThumbTintList(accent);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (fromUser) straighten(value / (float) STEPS - MAX_DEGREES);
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
            }
        });
        straightenRow.addView(slider, new LinearLayout.LayoutParams(0, dp(SpookdType.MIN_TOUCH), 1));
        angle = text("", SpookdType.SECONDARY);
        angle.setMinEms(4);
        angle.setGravity(Gravity.END);
        angle.setTextColor(InstagramPreferenceStyle.secondaryTextColor());
        straightenRow.addView(angle);
        root.addView(straightenRow);

        HorizontalScrollView chipScroller = new HorizontalScrollView(context);
        chipScroller.setHorizontalScrollBarEnabled(false);
        LinearLayout chipRow = new LinearLayout(context);
        chipRow.setPadding(dp(SpookdType.SPACE_M), 0, dp(SpookdType.SPACE_M), 0);
        for (int i = 0; i < RATIOS.length; i++) {
            int choice = i;
            String name = RATIO_LABELS[i];
            TextView chip = text(name.startsWith("spookd_") ? str(name) : name, SpookdType.LABEL);
            chip.setGravity(Gravity.CENTER);
            chip.setMinHeight(dp(SpookdType.MIN_TOUCH));
            GradientDrawable fill = new GradientDrawable();
            fill.setCornerRadius(dp(SpookdType.MIN_TOUCH));
            chip.setBackground(fill);
            chip.setPadding(dp(SpookdType.SPACE_L), 0, dp(SpookdType.SPACE_L), 0);
            chip.setOnClickListener(v -> chooseRatio(choice));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.setMargins(dp(SpookdType.SPACE_XS), dp(SpookdType.SPACE_XS), dp(SpookdType.SPACE_XS),
                    dp(SpookdType.SPACE_XS));
            chipRow.addView(chip, params);
            chips[i] = chip;
        }
        chipScroller.addView(chipRow);
        root.addView(chipScroller);

        LinearLayout tools = new LinearLayout(context);
        rotateAction = action(str("spookd_photo_editor_rotate"), v -> rotate());
        flipAction = action(str("spookd_photo_editor_flip"), v -> flip());
        resetAction = action(str("spookd_reset"), v -> {
            kept.remove(file.getPath());
            reset();
        });
        for (TextView tool : new TextView[]{rotateAction, flipAction, resetAction}) {
            tools.addView(tool, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        }
        root.addView(tools);

        setContentView(root);
        setOnDismissListener(d -> {
            // The gallery closes when the app leaves the foreground; the edit waits for the next open.
            if (!dropped && hasChanges()) kept.put(file.getPath(), new Kept(this));
            preview = null;
        });
        updateViews();
    }

    @Override
    public void onBackPressed() {
        leave();
    }

    private int dp(int value) {
        return (int) (value * density);
    }

    private TextView text(CharSequence value, int role) {
        TextView view = new TextView(getContext());
        view.setText(value);
        SpookdType.apply(view, role);
        view.setTextColor(primaryColor);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        return view;
    }

    private TextView action(String value, View.OnClickListener click) {
        TextView view = text(value, SpookdType.LABEL);
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(SpookdType.MIN_TOUCH));
        view.setPadding(dp(SpookdType.SPACE_M), 0, dp(SpookdType.SPACE_M), 0);
        view.setOnClickListener(click);
        return view;
    }

    private static void enable(View view, boolean enabled) {
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1f : 0.4f);
    }

    /** Background thread: reads the size, type and EXIF orientation, and decodes a screen-sized preview. */
    private void load() {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("Not a photo");
            int[] upright = EditGeometry.orientation(exifOrientation(file));
            DisplayMetrics screen = getContext().getResources().getDisplayMetrics();
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = EditGeometry.previewSample(bounds.outWidth, bounds.outHeight,
                    screen.widthPixels, screen.heightPixels);
            Bitmap bitmap = BitmapFactory.decodeFile(file.getPath(), options);
            if (bitmap == null) throw new IOException("Cannot decode the photo");
            boolean isPng = "image/png".equals(bounds.outMimeType);
            Utils.runOnMainThread(() -> loaded(bitmap, bounds.outWidth, bounds.outHeight, upright, isPng));
        } catch (Exception | OutOfMemoryError e) {
            Logger.printException(() -> "PhotoEditor: cannot open " + file.getName(), e);
            Utils.runOnMainThread(() -> {
                if (!isShowing()) return;
                Utils.showToastShort(str("spookd_media_unreadable"));
                dismiss();
            });
        }
    }

    private static int exifOrientation(File file) {
        try {
            return new ExifInterface(file.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
        } catch (Exception e) {
            return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    private void loaded(Bitmap bitmap, int width, int height, int[] upright, boolean isPng) {
        if (!isShowing()) return;
        preview = bitmap;
        sourceWidth = width;
        sourceHeight = height;
        png = isPng;
        uprightTurns = upright[0];
        uprightMirror = upright[1] == 1;
        progress.setVisibility(View.GONE);
        reset();
        Kept edit = kept.get(file.getPath());
        if (edit == null || edit.width != width || edit.height != height) return;
        turns = edit.turns;
        mirror = edit.mirror;
        degrees = edit.degrees;
        ratioChoice = edit.ratioChoice;
        ratio = edit.ratio;
        userRect = edit.userRect;
        rect = edit.rect;
        updateViews();
        Utils.showToastShort(str("spookd_photo_editor_edits_kept"));
    }

    /** Ends the edit for good: nothing is kept for the next open. */
    private void drop() {
        dropped = true;
        kept.remove(file.getPath());
        dismiss();
    }

    private double baseWidth() {
        return turns % 2 == 0 ? sourceWidth : sourceHeight;
    }

    private double baseHeight() {
        return turns % 2 == 0 ? sourceHeight : sourceWidth;
    }

    private double[] fullRect() {
        double w = baseWidth() / 2;
        double h = baseHeight() / 2;
        return new double[]{-w, -h, w, h};
    }

    private boolean hasChanges() {
        if (preview == null) return false;
        if (turns != uprightTurns || mirror != uprightMirror || degrees != 0) return true;
        double[] full = fullRect();
        for (int i = 0; i < 4; i++) {
            if (Math.abs(rect[i] - full[i]) > 0.5) return true;
        }
        return false;
    }

    private void reset() {
        if (preview == null) return;
        turns = uprightTurns;
        mirror = uprightMirror;
        degrees = 0;
        ratioChoice = 0;
        ratio = 0;
        userRect = fullRect();
        rect = userRect;
        updateViews();
    }

    private void chooseRatio(int choice) {
        if (preview == null) return;
        ratioChoice = choice;
        ratio = RATIOS[choice] < 0 ? baseWidth() / baseHeight() : RATIOS[choice];
        if (ratio > 0) {
            userRect = EditGeometry.largest(ratio, baseWidth(), baseHeight(), 0);
            rect = EditGeometry.fit(userRect, baseWidth(), baseHeight(), degrees);
        }
        updateViews();
    }

    /** A quarter turn clockwise of what is shown; a fixed ratio turns with the frame. */
    private void rotate() {
        if (preview == null) return;
        // Mirrored, a clockwise turn of the result is a counterclockwise turn of the photo.
        turns = (turns + (mirror ? 3 : 1)) % 4;
        rect = EditGeometry.turned(rect);
        userRect = EditGeometry.turned(userRect);
        if (ratio > 0) ratio = 1 / ratio;
        updateViews();
    }

    /** Mirrors what is shown left to right, which also reverses the straighten angle. */
    private void flip() {
        if (preview == null) return;
        mirror = !mirror;
        degrees = -degrees;
        rect = EditGeometry.mirrored(rect);
        userRect = EditGeometry.mirrored(userRect);
        updateViews();
    }

    private void straighten(float value) {
        if (preview == null) return;
        degrees = value;
        rect = EditGeometry.fit(userRect, baseWidth(), baseHeight(), degrees);
        updateViews();
    }

    private void updateViews() {
        boolean ready = preview != null && !saving;
        boolean changed = ready && hasChanges();
        enable(saveAction, changed);
        enable(resetAction, changed);
        enable(rotateAction, ready);
        enable(flipAction, ready);
        enable(slider, ready);
        slider.setProgress(Math.round((degrees + MAX_DEGREES) * STEPS));
        // Adding 0 turns a mirrored -0 into 0.
        angle.setText(String.format(Locale.getDefault(), "%.1f°", degrees + 0f));
        for (int i = 0; i < chips.length; i++) {
            TextView chip = chips[i];
            boolean selected = i == ratioChoice;
            chip.setSelected(selected);
            ((GradientDrawable) chip.getBackground()).setColor(selected ? primaryColor : chipColor);
            chip.setTextColor(selected ? backgroundColor : primaryColor);
            enable(chip, ready);
        }
        cropView.invalidate();
        if (rect != null) cropView.setContentDescription(cropSize());
    }

    /** Smallest crop side a drag allows, in source pixels. */
    private double minSide() {
        return 0.1 * Math.min(baseWidth(), baseHeight());
    }

    private String cropSize() {
        return str("spookd_photo_editor_crop_frame", (int) Math.round(rect[2] - rect[0]),
                (int) Math.round(rect[3] - rect[1]));
    }

    /**
     * Accessibility step {@code action} on the frame ({@link #HOST}) or on handle {@code node}: 5% of the
     * shorter side, clamped like a drag. False when it is not one of their actions.
     */
    private boolean stepCrop(int node, int action) {
        if (preview == null || saving) return false;
        double step = 0.05 * Math.min(baseWidth(), baseHeight());
        double w = baseWidth();
        double h = baseHeight();
        double min = minSide();
        double[] next;
        if (node == HOST) {
            int move = EditGeometry.MOVE;
            switch (action - ACTION_BASE) {
                case 0: next = EditGeometry.drag(rect, move, -step, 0, ratio, min, w, h, degrees); break;
                case 1: next = EditGeometry.drag(rect, move, step, 0, ratio, min, w, h, degrees); break;
                case 2: next = EditGeometry.drag(rect, move, 0, -step, ratio, min, w, h, degrees); break;
                case 3: next = EditGeometry.drag(rect, move, 0, step, ratio, min, w, h, degrees); break;
                case 4: next = EditGeometry.grown(rect, step, 0, ratio, min, w, h, degrees); break;
                case 5: next = EditGeometry.grown(rect, -step, 0, ratio, min, w, h, degrees); break;
                case 6: next = EditGeometry.grown(rect, 0, step, ratio, min, w, h, degrees); break;
                case 7: next = EditGeometry.grown(rect, 0, -step, ratio, min, w, h, degrees); break;
                default: return false;
            }
        } else {
            if (action != ACTION_OUTWARD && action != ACTION_INWARD) return false;
            int edges = HANDLE_EDGES[node];
            double signed = action == ACTION_OUTWARD ? step : -step;
            double dx = (edges & EditGeometry.LEFT) != 0 ? -signed : (edges & EditGeometry.RIGHT) != 0 ? signed : 0;
            double dy = (edges & EditGeometry.TOP) != 0 ? -signed : (edges & EditGeometry.BOTTOM) != 0 ? signed : 0;
            next = EditGeometry.drag(rect, edges, dx, dy, ratio, min, w, h, degrees);
        }
        rect = next;
        userRect = next;
        updateViews();
        cropView.announceForAccessibility(cropSize());
        return true;
    }

    /** Cancel and Back: asks first when there is something to lose. */
    private void leave() {
        if (saving) {
            dismiss();
            return;
        }
        if (!hasChanges()) {
            drop();
            return;
        }
        new AlertDialog.Builder(InstagramPreferenceStyle.dialogContext(activity))
                .setMessage(str("spookd_photo_editor_discard_confirm"))
                .setPositiveButton(str("spookd_photo_editor_discard"), (d, which) -> drop())
                .setNegativeButton(str("spookd_photo_editor_keep_editing"), null)
                .show();
    }

    /** Main thread; renders the full-size photo and publishes it on the background thread. */
    private void save() {
        if (saving || !hasChanges()) return;
        saving = true;
        progress.setVisibility(View.VISIBLE);
        updateViews();
        int width = sourceWidth;
        int height = sourceHeight;
        int turns = this.turns;
        boolean mirror = this.mirror;
        float degrees = this.degrees;
        double[] crop = rect;
        boolean png = this.png;
        WORKER.execute(() -> {
            try {
                boolean smaller = write(width, height, turns, mirror, degrees, crop, png);
                Utils.runOnMainThread(() -> {
                    saving = false;
                    Utils.showToastLong(str(smaller ? "spookd_photo_editor_saved_smaller"
                            : "spookd_photo_editor_saved"));
                    saved.run();
                    drop();
                });
            } catch (Exception | OutOfMemoryError e) {
                Logger.printException(() -> "PhotoEditor: cannot save the edit of " + file.getName(), e);
                Utils.runOnMainThread(() -> {
                    saving = false;
                    progress.setVisibility(View.GONE);
                    updateViews();
                    Utils.showToastShort(str("spookd_gallery_action_failed"));
                });
            }
        });
    }

    /**
     * Background thread: decodes at the largest size whose decode and result fit in half the free heap,
     * halving again on running out of memory. Returns whether the result is smaller than full size.
     */
    private boolean write(int width, int height, int turns, boolean mirror, float degrees, double[] crop,
                          boolean png) throws IOException {
        // Read before the decode, so a gallery clear meanwhile drops this edit.
        long epoch = GalleryStore.get().clearEpoch();
        Runtime runtime = Runtime.getRuntime();
        long free = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory());
        // Two ARGB copies at 4 bytes a pixel, in half of what is free.
        int sample = EditGeometry.budgetSample(width, height, free / 16);
        for (int attempt = 0; ; attempt++) {
            Bitmap result;
            try {
                result = render(width, height, turns, mirror, degrees, crop, sample);
            } catch (OutOfMemoryError e) {
                if (attempt == 2) throw e;
                sample *= 2;
                continue;
            }
            try {
                publish(result, png, epoch);
            } finally {
                result.recycle();
            }
            return sample > 1;
        }
    }

    private Bitmap render(int width, int height, int turns, boolean mirror, float degrees, double[] crop,
                          int sample) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        Bitmap source = BitmapFactory.decodeFile(file.getPath(), options);
        if (source == null) throw new IOException("Cannot decode the photo");
        try {
            int decodedWidth = source.getWidth();
            int decodedHeight = source.getHeight();
            // Each side is sampled on its own, so the decode is mapped back to source pixels per axis.
            double scale = Math.min(decodedWidth / (double) width, decodedHeight / (double) height);
            double baseWidth = turns % 2 == 0 ? width : height;
            double baseHeight = turns % 2 == 0 ? height : width;
            int[] out = EditGeometry.outputRect(crop, baseWidth, baseHeight, scale);
            Bitmap result = Bitmap.createBitmap(out[2] - out[0], out[3] - out[1], Bitmap.Config.ARGB_8888);
            Matrix matrix = new Matrix();
            frame(matrix, width, height, turns, mirror, degrees);
            matrix.preScale(width / (float) decodedWidth, height / (float) decodedHeight);
            matrix.postTranslate((float) (baseWidth / 2), (float) (baseHeight / 2));
            matrix.postScale((float) scale, (float) scale);
            matrix.postTranslate(-out[0], -out[1]);
            new Canvas(result).drawBitmap(source, matrix, new Paint(Paint.FILTER_BITMAP_FLAG));
            return result;
        } finally {
            source.recycle();
        }
    }

    /** Background thread: a new gallery item with the original's details; JPEG, or PNG for a PNG. */
    private void publish(Bitmap result, boolean png, long epoch) throws IOException {
        GalleryStore store = GalleryStore.get();
        File part = store.partFile();
        try (OutputStream out = new FileOutputStream(part)) {
            if (!result.compress(png ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                throw new IOException("Cannot encode the edit");
            }
        } catch (IOException | RuntimeException e) {
            part.delete();
            throw e;
        }
        store.publish(part, item, subFolder, EditGeometry.editedName(fileName, png), GalleryStore.Conflict.RENAME,
                epoch);
    }

    /** Sets {@code matrix} to map a {@code width} by {@code height} source onto the straightened frame, in source pixels. */
    private static void frame(Matrix matrix, int width, int height, int turns, boolean mirror, float degrees) {
        matrix.setTranslate(-width / 2f, -height / 2f);
        matrix.postRotate(90 * turns);
        if (mirror) matrix.postScale(-1, 1);
        matrix.postRotate(degrees);
    }

    /** The photo fitted to the view with the crop frame over it; dragging an edge, a corner or the inside edits the crop. */
    private final class CropView extends View {
        private final Paint image = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint shade = new Paint();
        private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint handle = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix matrix = new Matrix();
        private float scale;
        private int dragging;
        private float downX;
        private float downY;
        private double[] downRect;

        CropView(Context context) {
            super(context);
            shade.setColor(0x99000000);
            border.setColor(0xFFFFFFFF);
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(1.5f * density);
            handle.setColor(0xFFFFFFFF);
            handle.setStyle(Paint.Style.STROKE);
            handle.setStrokeWidth(4 * density);
            handle.setStrokeCap(Paint.Cap.SQUARE);
            setContentDescription(str("spookd_photo_editor_crop_frame", 0, 0));
        }

        /** Fits the turned photo's bounds into the view with room for the handles; false before there is one. */
        private boolean fitFrame() {
            if (preview == null || getWidth() == 0 || getHeight() == 0) return false;
            double rad = Math.toRadians(degrees);
            double cos = Math.abs(Math.cos(rad));
            double sin = Math.abs(Math.sin(rad));
            double boxWidth = baseWidth() * cos + baseHeight() * sin;
            double boxHeight = baseWidth() * sin + baseHeight() * cos;
            float margin = 2 * dp(SpookdType.SPACE_L);
            scale = (float) Math.min((getWidth() - margin) / boxWidth, (getHeight() - margin) / boxHeight);
            return scale > 0;
        }

        private float x(double frameX) {
            return (float) (getWidth() / 2f + frameX * scale);
        }

        private float y(double frameY) {
            return (float) (getHeight() / 2f + frameY * scale);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (!fitFrame()) return;
            frame(matrix, sourceWidth, sourceHeight, turns, mirror, degrees);
            matrix.preScale(sourceWidth / (float) preview.getWidth(), sourceHeight / (float) preview.getHeight());
            matrix.postScale(scale, scale);
            matrix.postTranslate(getWidth() / 2f, getHeight() / 2f);
            canvas.drawBitmap(preview, matrix, image);
            float l = x(rect[0]);
            float t = y(rect[1]);
            float r = x(rect[2]);
            float b = y(rect[3]);
            canvas.drawRect(0, 0, getWidth(), t, shade);
            canvas.drawRect(0, b, getWidth(), getHeight(), shade);
            canvas.drawRect(0, t, l, b, shade);
            canvas.drawRect(r, t, getWidth(), b, shade);
            canvas.drawRect(l, t, r, b, border);
            float length = dp(SpookdType.SPACE_L);
            float[] xs = {l, r};
            float[] ys = {t, b};
            for (int i = 0; i < 2; i++) {
                for (int j = 0; j < 2; j++) {
                    canvas.drawLine(xs[i], ys[j], xs[i] + (i == 0 ? length : -length), ys[j], handle);
                    canvas.drawLine(xs[i], ys[j], xs[i], ys[j] + (j == 0 ? length : -length), handle);
                }
            }
            float midX = (l + r) / 2;
            float midY = (t + b) / 2;
            canvas.drawLine(midX - length / 2, t, midX + length / 2, t, handle);
            canvas.drawLine(midX - length / 2, b, midX + length / 2, b, handle);
            canvas.drawLine(l, midY - length / 2, l, midY + length / 2, handle);
            canvas.drawLine(r, midY - length / 2, r, midY + length / 2, handle);
        }

        /** The edges a touch at {@code px}, {@code py} grabs, {@link EditGeometry#MOVE} inside, 0 elsewhere. */
        private int hit(float px, float py) {
            float slop = dp(SpookdType.SPACE_XL);
            float l = x(rect[0]);
            float t = y(rect[1]);
            float r = x(rect[2]);
            float b = y(rect[3]);
            if (px < l - slop || px > r + slop || py < t - slop || py > b + slop) return 0;
            int edges = 0;
            // On a small frame the nearer edge wins, so both stay reachable.
            if (Math.abs(px - l) <= slop && Math.abs(px - l) <= Math.abs(px - r)) edges |= EditGeometry.LEFT;
            else if (Math.abs(px - r) <= slop) edges |= EditGeometry.RIGHT;
            if (Math.abs(py - t) <= slop && Math.abs(py - t) <= Math.abs(py - b)) edges |= EditGeometry.TOP;
            else if (Math.abs(py - b) <= slop) edges |= EditGeometry.BOTTOM;
            return edges == 0 ? EditGeometry.MOVE : edges;
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (saving || !fitFrame()) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dragging = hit(event.getX(), event.getY());
                    if (dragging == 0) return false;
                    downX = event.getX();
                    downY = event.getY();
                    downRect = rect;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (dragging == 0) return false;
                    rect = EditGeometry.drag(downRect, dragging, (event.getX() - downX) / scale,
                            (event.getY() - downY) / scale, ratio, minSide(), baseWidth(), baseHeight(), degrees);
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (dragging == 0) return false;
                    dragging = 0;
                    userRect = rect;
                    updateViews();
                    return true;
                default:
                    return dragging != 0;
            }
        }

        private final Nodes nodes = new Nodes();
        private int hovered = HOST;

        @Override
        public AccessibilityNodeProvider getAccessibilityNodeProvider() {
            return nodes;
        }

        /** Explore by touch finds the handle under the finger; elsewhere the frame itself. */
        @Override
        public boolean dispatchHoverEvent(MotionEvent event) {
            AccessibilityManager manager = (AccessibilityManager) getContext()
                    .getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (manager == null || !manager.isTouchExplorationEnabled() || preview == null || !fitFrame()) {
                return super.dispatchHoverEvent(event);
            }
            int node = HOST;
            if (event.getActionMasked() != MotionEvent.ACTION_HOVER_EXIT) {
                int edges = hit(event.getX(), event.getY());
                for (int i = 0; i < HANDLE_EDGES.length; i++) {
                    if (HANDLE_EDGES[i] == edges) node = i;
                }
            }
            if (node != hovered) {
                if (hovered != HOST) nodes.send(hovered, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT);
                hovered = node;
                if (node != HOST) nodes.send(node, AccessibilityEvent.TYPE_VIEW_HOVER_ENTER);
            }
            return node != HOST || super.dispatchHoverEvent(event);
        }

        /** Handle {@code node}'s touch area, in this view's coordinates. */
        private Rect handleBounds(int node) {
            int edges = HANDLE_EDGES[node];
            float l = x(rect[0]);
            float t = y(rect[1]);
            float r = x(rect[2]);
            float b = y(rect[3]);
            float cx = (edges & EditGeometry.LEFT) != 0 ? l : (edges & EditGeometry.RIGHT) != 0 ? r : (l + r) / 2;
            float cy = (edges & EditGeometry.TOP) != 0 ? t : (edges & EditGeometry.BOTTOM) != 0 ? b : (t + b) / 2;
            int half = dp(SpookdType.SPACE_XL);
            return new Rect(Math.round(cx) - half, Math.round(cy) - half, Math.round(cx) + half,
                    Math.round(cy) + half);
        }

        /** The frame with its move and resize actions, and one virtual node per handle for TalkBack. */
        private final class Nodes extends AccessibilityNodeProvider {
            private int focused = HOST;

            @Override
            public AccessibilityNodeInfo createAccessibilityNodeInfo(int node) {
                boolean ready = preview != null && !saving && fitFrame();
                if (node == HOST) {
                    AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain(CropView.this);
                    onInitializeAccessibilityNodeInfo(info);
                    if (!ready) return info;
                    for (int i = 0; i < HANDLE_EDGES.length; i++) info.addChild(CropView.this, i);
                    for (int i = 0; i < FRAME_ACTIONS.length; i++) {
                        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACTION_BASE + i,
                                str(FRAME_ACTIONS[i])));
                    }
                    return info;
                }
                if (!ready || node < 0 || node >= HANDLE_EDGES.length) return null;
                AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain(CropView.this, node);
                info.setPackageName(getContext().getPackageName());
                info.setClassName(View.class.getName());
                info.setParent(CropView.this);
                info.setContentDescription(str(HANDLE_LABELS[node]));
                Rect bounds = handleBounds(node);
                info.setBoundsInParent(bounds);
                int[] screen = new int[2];
                getLocationOnScreen(screen);
                bounds.offset(screen[0], screen[1]);
                info.setBoundsInScreen(bounds);
                info.setVisibleToUser(true);
                info.setEnabled(true);
                info.setAccessibilityFocused(focused == node);
                info.addAction(focused == node
                        ? AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS
                        : AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS);
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACTION_OUTWARD,
                        str("spookd_photo_editor_outward")));
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACTION_INWARD,
                        str("spookd_photo_editor_inward")));
                return info;
            }

            @Override
            public boolean performAction(int node, int action, Bundle arguments) {
                try {
                    if (node == HOST) {
                        return stepCrop(HOST, action) || performAccessibilityAction(action, arguments);
                    }
                    if (node < 0 || node >= HANDLE_EDGES.length) return false;
                    if (action == AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) {
                        if (focused == node) return false;
                        focused = node;
                        send(node, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);
                        return true;
                    }
                    if (action == AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS) {
                        if (focused != node) return false;
                        focused = HOST;
                        send(node, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED);
                        return true;
                    }
                    return stepCrop(node, action);
                } catch (Exception e) {
                    Logger.printException(() -> "PhotoEditor: accessibility action failed", e);
                    return false;
                }
            }

            void send(int node, int type) {
                ViewParent parent = getParent();
                if (parent == null) return;
                AccessibilityEvent event = AccessibilityEvent.obtain(type);
                event.setSource(CropView.this, node);
                event.setPackageName(getContext().getPackageName());
                event.setClassName(View.class.getName());
                event.setContentDescription(str(HANDLE_LABELS[node]));
                event.setEnabled(true);
                parent.requestSendAccessibilityEvent(CropView.this, event);
            }
        }
    }
}
