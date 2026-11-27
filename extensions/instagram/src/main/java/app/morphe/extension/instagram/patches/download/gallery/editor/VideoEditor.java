package app.morphe.extension.instagram.patches.download.gallery.editor;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.VideoView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import app.morphe.extension.instagram.constants.UI;
import app.morphe.extension.instagram.patches.download.gallery.Gallery;
import app.morphe.extension.instagram.patches.download.gallery.GalleryItem;
import app.morphe.extension.instagram.patches.download.gallery.GalleryStore;
import app.morphe.extension.instagram.settings.preference.widgets.InstagramPreferenceStyle;
import app.morphe.extension.instagram.settings.preference.widgets.SpookdType;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Full-screen editor of one private gallery video download: a lossless trim between keyframes, the
 * audio alone and the frame shown, each saved as a new gallery item beside the untouched original.
 * The screen runs on the main thread; reading the keyframes and every save run on one background thread.
 */
public final class VideoEditor extends Dialog {
    private static final Executor WORKER = Executors.newSingleThreadExecutor();
    private static final int JPEG_QUALITY = 95;
    private static final long TICK_MS = 50;
    private static final int HANDLE_DP = 16;
    private static final int ACTION_PREVIOUS = 0x7e5e0000;
    private static final int ACTION_NEXT = 0x7e5e0001;

    private final Activity activity;
    private final File file;
    private final GalleryItem item;
    private final String subFolder;
    private final String fileName;
    private final Runnable saved;
    private final float density;
    private final VideoView video;
    private final FrameLayout stage;
    private final TextView playGlyph;
    private final ProgressBar progress;
    private final TextView range;
    private final Timeline timeline;
    private final TextView saveAction;
    private final TextView[] steps = new TextView[4];
    private final TextView frameAction;
    private final TextView audioAction;
    private final Runnable tick = this::tick;
    private final int primaryColor = InstagramPreferenceStyle.primaryTextColor();
    private final int backgroundColor = InstagramPreferenceStyle.backgroundColor();

    // Main thread only.
    /** Sorted keyframe times then the duration; null until read. */
    private long[] points;
    private String audioMime;
    private long start;
    private long end;
    private long playhead;
    /** The preview's player once prepared, for exact seeks. */
    private MediaPlayer player;
    private boolean busy;
    /** Set while Save writes the trim, which closes the editor when done. */
    private boolean savingTrim;

    /** Background-thread work that writes a new item into {@code part}. */
    private interface Output {
        void write(File part) throws Exception;
    }

    private interface Job {
        void run() throws Exception;
    }

    /**
     * Main thread: opens the editor on a video download. After each save {@code saved} runs on the
     * main thread. Never throws; returns null when the editor cannot open.
     */
    public static Dialog show(Activity activity, File file, GalleryItem item, String subFolder, String fileName,
                              Runnable saved) {
        try {
            VideoEditor editor = new VideoEditor(activity, file, item, subFolder, fileName, saved);
            editor.show();
            WORKER.execute(editor::load);
            return editor;
        } catch (Exception e) {
            Logger.printException(() -> "VideoEditor.show failed", e);
            Utils.showToastShort(str("spookd_gallery_action_failed"));
            return null;
        }
    }

    private VideoEditor(Activity activity, File file, GalleryItem item, String subFolder, String fileName,
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
        title.setText(str("spookd_gallery_edit_video"));
        InstagramPreferenceStyle.applyToolbarTitle(title);
        title.setTextColor(primaryColor);
        title.setGravity(Gravity.CENTER);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        saveAction = action(str("spookd_photo_editor_save"), v -> saveTrim());
        saveAction.setTextColor(InstagramPreferenceStyle.selectionColor());
        toolbar.addView(saveAction);
        toolbar.setMinimumHeight(dp(SpookdType.TOOLBAR_HEIGHT));
        root.addView(toolbar);

        stage = new FrameLayout(context);
        stage.setBackgroundColor(Color.BLACK);
        video = new VideoView(context);
        video.setSecure(true);
        // Opening the editor should not pause music playing elsewhere.
        video.setAudioFocusRequest(AudioManager.AUDIOFOCUS_NONE);
        stage.addView(video, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        playGlyph = new TextView(context);
        playGlyph.setText("▶");
        playGlyph.setTextSize(TypedValue.COMPLEX_UNIT_SP, 48);
        playGlyph.setTextColor(Color.WHITE);
        playGlyph.setShadowLayer(4, 0, 1, Color.BLACK);
        playGlyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        stage.addView(playGlyph, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        progress = new ProgressBar(context);
        stage.addView(progress, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        stage.setOnClickListener(v -> toggle());
        root.addView(stage, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        range = text("", SpookdType.SECONDARY);
        range.setGravity(Gravity.CENTER);
        range.setPadding(0, dp(SpookdType.SPACE_S), 0, 0);
        root.addView(range);
        timeline = new Timeline(context);
        root.addView(timeline, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                dp(SpookdType.MIN_TOUCH + SpookdType.SPACE_S)));

        LinearLayout stepRow = new LinearLayout(context);
        String startLabel = str("spookd_video_editor_start_label");
        String endLabel = str("spookd_video_editor_end_label");
        String[] labels = {"‹ " + startLabel, startLabel + " ›", "‹ " + endLabel, endLabel + " ›"};
        String[] descriptions = {"spookd_video_editor_start_previous", "spookd_video_editor_start_next",
                "spookd_video_editor_end_previous", "spookd_video_editor_end_next"};
        for (int i = 0; i < steps.length; i++) {
            boolean endHandle = i >= 2;
            int direction = i % 2 == 0 ? -1 : 1;
            TextView step = action(labels[i], v -> stepHandle(endHandle, direction));
            step.setContentDescription(str(descriptions[i]));
            stepRow.addView(step, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            steps[i] = step;
        }
        root.addView(stepRow);

        LinearLayout tools = new LinearLayout(context);
        frameAction = action(str("spookd_video_editor_save_frame"), v -> saveFrame());
        audioAction = action(str("spookd_video_editor_save_audio"), v -> saveAudio());
        tools.addView(frameAction, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        tools.addView(audioAction, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        root.addView(tools);

        TextView note = new TextView(context);
        note.setText(str("spookd_video_editor_note"));
        SpookdType.apply(note, SpookdType.CAPTION);
        note.setTextColor(InstagramPreferenceStyle.secondaryTextColor());
        note.setPadding(dp(SpookdType.SPACE_L), dp(SpookdType.SPACE_XS), dp(SpookdType.SPACE_L),
                dp(SpookdType.SPACE_M));
        root.addView(note);

        video.setOnPreparedListener(mp -> {
            player = mp;
            // Shows the frame at the playhead instead of black until playback starts.
            seek(playhead);
        });
        video.setOnCompletionListener(mp -> {
            playhead = end;
            updateViews();
        });
        video.setOnErrorListener((mp, what, extra) -> {
            Logger.printInfo(() -> "VideoEditor: preview error " + what + "/" + extra);
            player = null;
            Utils.showToastShort(str("spookd_media_unreadable"));
            updateViews();
            return true;
        });
        video.setVideoPath(file.getPath());

        setContentView(root);
        setOnDismissListener(d -> {
            video.removeCallbacks(tick);
            player = null;
            video.stopPlayback();
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

    /** Background thread. */
    private void load() {
        try {
            VideoTrim.Probe probe = VideoTrim.probe(file.getPath());
            Utils.runOnMainThread(() -> loaded(probe));
        } catch (Exception | OutOfMemoryError e) {
            Logger.printException(() -> "VideoEditor: cannot open " + file.getName(), e);
            Utils.runOnMainThread(() -> {
                if (!isShowing()) return;
                Utils.showToastShort(str("spookd_media_unreadable"));
                dismiss();
            });
        }
    }

    private void loaded(VideoTrim.Probe probe) {
        if (!isShowing()) return;
        points = probe.points;
        audioMime = probe.audioMime;
        start = points[0];
        end = last();
        progress.setVisibility(View.GONE);
        updateViews();
    }

    private long last() {
        return points[points.length - 1];
    }

    private boolean hasChanges() {
        return points != null && (start != points[0] || end != last());
    }

    /** Where a step of {@code direction} keyframes moves a handle; where it is when it cannot move. */
    private long stepped(boolean endHandle, int direction) {
        return endHandle ? VideoTrim.step(points, end, direction, start + 1, last())
                : VideoTrim.step(points, start, direction, points[0], end - 1);
    }

    private String handleDescription(boolean endHandle) {
        return endHandle ? str("spookd_video_editor_end_handle", VideoTrim.time(end))
                : str("spookd_video_editor_start_handle", VideoTrim.time(start));
    }

    private void updateViews() {
        boolean ready = points != null && !busy;
        enable(saveAction, ready && hasChanges());
        for (int i = 0; i < steps.length; i++) {
            boolean endHandle = i >= 2;
            enable(steps[i], ready && stepped(endHandle, i % 2 == 0 ? -1 : 1) != (endHandle ? end : start));
        }
        enable(frameAction, ready);
        enable(audioAction, ready && audioMime != null);
        boolean playing = video.isPlaying();
        playGlyph.setVisibility(ready && !playing ? View.VISIBLE : View.GONE);
        stage.setContentDescription(str(playing ? "spookd_video_editor_pause" : "spookd_video_editor_play"));
        if (points != null) {
            range.setText(str("spookd_video_editor_range", VideoTrim.time(start), VideoTrim.time(end),
                    VideoTrim.time(end - start)));
        }
        timeline.update();
    }

    /** Moves the preview to {@code us}, exactly once the player is prepared. */
    private void seek(long us) {
        playhead = us;
        timeline.invalidate();
        MediaPlayer prepared = player;
        if (prepared != null) {
            try {
                prepared.seekTo(us / 1000, MediaPlayer.SEEK_CLOSEST);
                return;
            } catch (RuntimeException e) {
                // The view released it, for example when its surface went away.
                player = null;
            }
        }
        video.seekTo((int) (us / 1000));
    }

    private void pause() {
        if (!video.isPlaying()) return;
        video.pause();
        playhead = video.getCurrentPosition() * 1000L;
    }

    /** A tap on the preview: plays the selection from the playhead, or from its start when outside it. */
    private void toggle() {
        // Before the preview is prepared a start would play with nothing following the playhead.
        if (points == null || busy || player == null) return;
        if (video.isPlaying()) {
            pause();
        } else {
            if (playhead < start || playhead >= end) seek(start);
            video.start();
            video.removeCallbacks(tick);
            video.postDelayed(tick, TICK_MS);
        }
        updateViews();
    }

    /** While playing: follows the playhead and pauses at the end of the selection. */
    private void tick() {
        if (!isShowing()) return;
        boolean playing = video.isPlaying();
        if (playing) {
            playhead = video.getCurrentPosition() * 1000L;
            if (playhead >= end) {
                video.pause();
                playing = false;
            }
        }
        if (playing) {
            timeline.invalidate();
            video.postDelayed(tick, TICK_MS);
        } else {
            updateViews();
        }
    }

    /** Sets a handle to cut point {@code value} and shows it; ignores -1 and no change. */
    private void setHandle(boolean endHandle, long value) {
        if (value < 0 || value == (endHandle ? end : start)) return;
        if (endHandle) end = value;
        else start = value;
        pause();
        seek(value);
        updateViews();
    }

    private void stepHandle(boolean endHandle, int direction) {
        if (points == null || busy) return;
        setHandle(endHandle, stepped(endHandle, direction));
        timeline.handle(endHandle).announceForAccessibility(handleDescription(endHandle));
    }

    /** Cancel and Back: asks first when there is something to lose; an audio or frame save keeps the trim unsaved. */
    private void leave() {
        if (savingTrim || !hasChanges()) {
            dismiss();
            return;
        }
        new AlertDialog.Builder(InstagramPreferenceStyle.dialogContext(activity))
                .setMessage(str("spookd_video_editor_discard_confirm"))
                .setPositiveButton(str("spookd_photo_editor_discard"), (d, which) -> dismiss())
                .setNegativeButton(str("spookd_photo_editor_keep_editing"), null)
                .show();
    }

    private void saveTrim() {
        if (busy || !hasChanges()) return;
        String extension = VideoTrim.videoExtension(VideoTrim.extension(file.getName(), ".mp4"));
        long from = start;
        long to = end;
        work(() -> publish(GalleryItem.VIDEO, VideoTrim.derivedName(fileName, "_trimmed", extension),
                        part -> VideoTrim.remux(file.getPath(), part.getPath(), VideoTrim.muxerFormat(extension),
                                true, from, to)),
                str("spookd_video_editor_saved", VideoTrim.time(start), VideoTrim.time(end)), true);
    }

    /** The audio of the selection, which is all of it until a handle moves. */
    private void saveAudio() {
        if (busy || audioMime == null) return;
        String extension = VideoTrim.audioExtension(audioMime, VideoTrim.extension(file.getName(), ".mp4"));
        if (extension == null) {
            Utils.showToastLong(str("spookd_video_editor_audio_unsupported"));
            return;
        }
        long from = start;
        long to = end;
        work(() -> publish(GalleryItem.AUDIO, VideoTrim.derivedName(fileName, "_audio", extension),
                        part -> VideoTrim.remux(file.getPath(), part.getPath(), VideoTrim.muxerFormat(extension),
                                false, from, to)),
                str("spookd_video_editor_audio_saved", VideoTrim.time(start), VideoTrim.time(end)), false);
    }

    private void saveFrame() {
        if (busy || points == null) return;
        pause();
        long at = playhead;
        work(() -> publish(GalleryItem.PHOTO, VideoTrim.derivedName(fileName, "_frame", ".jpg"),
                        part -> writeFrame(part, at)),
                str("spookd_video_editor_frame_saved", VideoTrim.time(at)), false);
    }

    /** Main thread: runs {@code job} on the background thread with the spinner up; Save closes the editor. */
    private void work(Job job, String done, boolean close) {
        if (busy || points == null) return;
        pause();
        busy = true;
        savingTrim = close;
        progress.setVisibility(View.VISIBLE);
        updateViews();
        WORKER.execute(() -> {
            try {
                job.run();
                Utils.runOnMainThread(() -> {
                    busy = false;
                    savingTrim = false;
                    Utils.showToastLong(done);
                    saved.run();
                    if (close) {
                        dismiss();
                        return;
                    }
                    progress.setVisibility(View.GONE);
                    updateViews();
                });
            } catch (Exception | OutOfMemoryError e) {
                Logger.printException(() -> "VideoEditor: cannot save from " + file.getName(), e);
                Utils.runOnMainThread(() -> {
                    busy = false;
                    savingTrim = false;
                    progress.setVisibility(View.GONE);
                    updateViews();
                    Utils.showToastShort(str("spookd_gallery_action_failed"));
                });
            }
        });
    }

    /** Background thread: a new gallery item of {@code kind} with the original's details, then the cleanup. */
    private void publish(String kind, String name, Output output) throws Exception {
        GalleryStore store = GalleryStore.get();
        // Read before the write, so a gallery clear meanwhile drops this item.
        long epoch = store.clearEpoch();
        File part = store.partFile();
        try {
            output.write(part);
            GalleryItem details = new GalleryItem(item.tab, kind, item.ownerUsername, item.ownerId, item.mediaId,
                    item.link, item.accountId, item.takenAt);
            store.publish(part, details, subFolder, name, GalleryStore.Conflict.RENAME, epoch);
        } finally {
            // Publishing moved the part to its item name, so this only removes a partial file.
            part.delete();
        }
        Gallery.prune();
    }

    /** Background thread: the frame nearest {@code at}, upright, as JPEG. */
    private void writeFrame(File part, long at) throws IOException {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        Bitmap frame;
        try {
            retriever.setDataSource(file.getPath());
            frame = retriever.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST);
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
        if (frame == null) throw new IOException("No frame at " + at);
        try (OutputStream out = new FileOutputStream(part)) {
            if (!frame.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                throw new IOException("Cannot encode the frame");
            }
        } finally {
            frame.recycle();
        }
    }

    /**
     * The whole video as a bar with keyframe ticks, the selection between two handles and the
     * playhead. Dragging a handle snaps it to the nearest keyframe; dragging elsewhere moves the playhead.
     */
    private final class Timeline extends FrameLayout {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint selection = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint marks = new Paint();
        private final Paint head = new Paint();
        private final Handle startHandle;
        private final Handle endHandle;
        /** 0 none, 1 the start handle, 2 the end handle, 3 the playhead. */
        private int dragging;

        Timeline(Context context) {
            super(context);
            setWillNotDraw(false);
            track.setColor(InstagramPreferenceStyle.searchBackgroundColor());
            selection.setColor(InstagramPreferenceStyle.selectionColor());
            marks.setColor(InstagramPreferenceStyle.secondaryTextColor());
            marks.setStrokeWidth(density);
            head.setColor(primaryColor);
            head.setStrokeWidth(2 * density);
            startHandle = new Handle(context, false);
            endHandle = new Handle(context, true);
            addView(startHandle, new FrameLayout.LayoutParams(dp(HANDLE_DP), FrameLayout.LayoutParams.MATCH_PARENT));
            addView(endHandle, new FrameLayout.LayoutParams(dp(HANDLE_DP), FrameLayout.LayoutParams.MATCH_PARENT));
        }

        Handle handle(boolean end) {
            return end ? endHandle : startHandle;
        }

        private float inset() {
            return dp(SpookdType.SPACE_L) + dp(HANDLE_DP) / 2f;
        }

        private float x(long us) {
            float inset = inset();
            return inset + (getWidth() - 2 * inset) * (us / (float) last());
        }

        private long time(float px) {
            float inset = inset();
            double share = (px - inset) / Math.max(1f, getWidth() - 2 * inset);
            return Math.round(Math.max(0, Math.min(1, share)) * last());
        }

        void update() {
            boolean ready = points != null && getWidth() > 0;
            startHandle.setVisibility(ready ? View.VISIBLE : View.INVISIBLE);
            endHandle.setVisibility(ready ? View.VISIBLE : View.INVISIBLE);
            if (!ready) return;
            float half = dp(HANDLE_DP) / 2f;
            startHandle.setTranslationX(x(start) - half);
            endHandle.setTranslationX(x(end) - half);
            startHandle.setContentDescription(handleDescription(false));
            endHandle.setContentDescription(handleDescription(true));
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            update();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (points == null || getWidth() == 0) return;
            float cy = getHeight() / 2f;
            float bar = dp(SpookdType.SPACE_XS);
            canvas.drawRect(x(0), cy - bar, x(last()), cy + bar, track);
            canvas.drawRect(x(start), cy - bar, x(end), cy + bar, selection);
            // Ticks closer than a few pixels would only fill the bar.
            if (points.length * 3 * density < getWidth()) {
                for (long point : points) {
                    float px = x(point);
                    canvas.drawLine(px, cy - 3 * bar, px, cy - 2 * bar, marks);
                }
            }
            float px = x(Math.max(0, Math.min(playhead, last())));
            canvas.drawLine(px, cy - 3 * bar, px, cy + 3 * bar, head);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (points == null || busy) return false;
            float px = event.getX();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    float toStart = Math.abs(px - x(start));
                    float toEnd = Math.abs(px - x(end));
                    // Handles on top of each other: the side of the touch picks one.
                    dragging = Math.min(toStart, toEnd) > dp(SpookdType.SPACE_XL) ? 3
                            : toEnd < toStart || (toEnd == toStart && px >= x(end)) ? 2 : 1;
                    ViewParent parent = getParent();
                    if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
                    drag(px);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (dragging == 0) return false;
                    drag(px);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    boolean was = dragging != 0;
                    dragging = 0;
                    return was;
                default:
                    return dragging != 0;
            }
        }

        private void drag(float px) {
            long at = time(px);
            if (dragging == 1) setHandle(false, VideoTrim.snap(points, at, points[0], end - 1));
            else if (dragging == 2) setHandle(true, VideoTrim.snap(points, at, start + 1, last()));
            else seek(at);
        }
    }

    /** One trim handle; TalkBack adjusts it like a slider, one keyframe per step. */
    private final class Handle extends View {
        private final boolean endHandle;

        Handle(Context context, boolean endHandle) {
            super(context);
            this.endHandle = endHandle;
            GradientDrawable fill = new GradientDrawable();
            fill.setColor(primaryColor);
            fill.setCornerRadius(dp(SpookdType.SPACE_XS));
            setBackground(fill);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName(SeekBar.class.getName());
            if (points == null || busy) return;
            long value = endHandle ? end : start;
            if (stepped(endHandle, -1) != value) {
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACTION_PREVIOUS,
                        str("spookd_video_editor_previous_keyframe")));
            }
            if (stepped(endHandle, 1) != value) {
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACTION_NEXT,
                        str("spookd_video_editor_next_keyframe")));
            }
        }

        @Override
        public boolean performAccessibilityAction(int action, Bundle arguments) {
            int direction = action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD || action == ACTION_PREVIOUS ? -1
                    : action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action == ACTION_NEXT ? 1 : 0;
            if (direction == 0 || points == null || busy) return super.performAccessibilityAction(action, arguments);
            stepHandle(endHandle, direction);
            return true;
        }
    }
}
