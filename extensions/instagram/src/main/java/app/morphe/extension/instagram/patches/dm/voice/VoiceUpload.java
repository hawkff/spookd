package app.morphe.extension.instagram.patches.dm.voice;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Fragment;
import android.app.FragmentManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.List;

import app.morphe.extension.instagram.patches.confirm.ActionConfirm;
import app.morphe.extension.instagram.patches.dm.InboxLock;
import app.morphe.extension.instagram.patches.download.gallery.Gallery;
import app.morphe.extension.instagram.patches.download.gallery.GalleryItem;
import app.morphe.extension.instagram.patches.download.gallery.GalleryStore;
import app.morphe.extension.instagram.settings.preference.widgets.InstagramPreferenceStyle;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Sends an audio file as a voice message. Holding the mic in a chat opens a sheet to pick a file from
 * Files or the private gallery and trim it; the selection is converted to what Instagram's recorder
 * writes and handed to that chat's voice controller as a finished recording, which Instagram uploads
 * and sends as it does a recording.
 */
public final class VoiceUpload {
    private static final String PICKER_TAG = "spookd_voice_file";
    private static final long STEP_MS = 100;
    private static final long TICK_MS = 50;
    // The voice controller of the chat whose mic was held.
    private static WeakReference<Object> chat = new WeakReference<>(null);
    // Main thread only: set while a selection is converted, so one Send cannot start a second.
    private static boolean converting;

    private VoiceUpload() {
    }

    /** Main thread, from the mic's long press; true when the sheet opened instead of recording. Never throws. */
    public static boolean onMicLongPress(Object voiceController) {
        try {
            if (voiceController == null || !Pref.sendAudioAsVoice()) return false;
            Activity activity = Utils.getActivity();
            if (activity == null || activity.isFinishing()) return false;
            showSources(activity);
            chat = new WeakReference<>(voiceController);
            return true;
        } catch (Throwable t) {
            Logger.printException(() -> "VoiceUpload: cannot open the sheet", t);
            return false;
        }
    }

    // The patch replaces this body with a new file path in Instagram's voice message folder.
    public static String voiceFilePath() {
        return null;
    }

    // The patch replaces this body: the file goes to the voice controller as a finished recording, which sends it.
    public static void sendRecording(Object voiceController, String path, int durationMs, List<Float> waveform) {
        throw new IllegalStateException("Not patched");
    }

    private static void showSources(Activity activity) {
        CharSequence[] sources = Gallery.canOpen(activity)
                ? new CharSequence[]{str("spookd_voice_from_files"), str("spookd_gallery_picker_entry")}
                : new CharSequence[]{str("spookd_voice_from_files")};
        new AlertDialog.Builder(InstagramPreferenceStyle.dialogContext(activity))
                .setTitle(str("spookd_voice_sheet_title"))
                .setItems(sources, (shown, which) -> {
                    if (which == 0) pickFile(activity);
                    else pickFromGallery(activity);
                })
                .setNegativeButton(str("spookd_cancel"), null)
                .show();
    }

    private static void pickFile(Activity activity) {
        try {
            FragmentManager fragments = activity.getFragmentManager();
            if (activity.isFinishing() || fragments.findFragmentByTag(PICKER_TAG) != null) return;
            fragments.beginTransaction().add(new FilePicker(), PICKER_TAG).commitAllowingStateLoss();
        } catch (Exception e) {
            fail("open the file picker", e);
        }
    }

    /** Asks for the screen lock as the gallery does, then lists its audio on a background thread. */
    private static void pickFromGallery(Activity activity) {
        try {
            InboxLock.confirm(activity, () -> Utils.runOnBackgroundThread(() -> {
                try {
                    List<GalleryStore.Entry> audio = GalleryStore.get().list(null, GalleryItem.AUDIO, false, null, null);
                    Utils.runOnMainThread(() -> showGalleryAudio(activity, audio));
                } catch (Exception e) {
                    fail("list the gallery's audio", e);
                }
            }));
        } catch (Exception e) {
            fail("ask for the screen lock", e);
        }
    }

    private static void showGalleryAudio(Activity activity, List<GalleryStore.Entry> audio) {
        try {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            if (audio.isEmpty()) {
                Utils.showToastShort(str("spookd_voice_gallery_empty"));
                return;
            }
            CharSequence[] names = new CharSequence[audio.size()];
            for (int i = 0; i < names.length; i++) names[i] = String.valueOf(audio.get(i).fileName);
            AlertDialog dialog = new AlertDialog.Builder(InstagramPreferenceStyle.dialogContext(activity))
                    .setTitle(str("spookd_gallery_picker_entry"))
                    .setItems(names, (shown, which) -> openTrim(activity, Uri.fromFile(audio.get(which).file)))
                    .setNegativeButton(str("spookd_cancel"), null)
                    .create();
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            dialog.show();
        } catch (Exception e) {
            fail("show the gallery's audio", e);
        }
    }

    /** Main thread: prepares a preview player, whose length opens the trim sheet. */
    private static void openTrim(Activity activity, Uri uri) {
        MediaPlayer player = new MediaPlayer();
        try {
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            player.setDataSource(activity, uri);
            player.setOnErrorListener((mp, what, extra) -> {
                mp.release();
                Utils.showToastShort(str("spookd_voice_unreadable"));
                return true;
            });
            player.setOnPreparedListener(mp -> showTrim(activity, uri, mp));
            player.prepareAsync();
        } catch (Exception e) {
            player.release();
            Logger.printException(() -> "VoiceUpload: cannot read " + uri, e);
            Utils.showToastShort(str("spookd_voice_unreadable"));
        }
    }

    private static void showTrim(Activity activity, Uri uri, MediaPlayer player) {
        try {
            int duration = player.getDuration();
            if (activity.isFinishing() || activity.isDestroyed()) {
                player.release();
                return;
            }
            if (duration < VoiceAudio.MIN_MS) {
                player.release();
                // An unknown length is -1.
                Utils.showToastShort(str(duration <= 0 ? "spookd_voice_unreadable" : "spookd_voice_too_short"));
                return;
            }
            new Trim(activity, uri, player, duration).show();
        } catch (Exception e) {
            player.release();
            fail("show the trim sheet", e);
        }
    }

    /** Main thread, once Send was confirmed: converts on a background thread, then sends on the main thread. */
    private static void convertAndSend(Context app, Uri uri, long startMs, long endMs) {
        if (converting) {
            Utils.showToastShort(str("spookd_voice_busy"));
            return;
        }
        converting = true;
        Utils.showToastShort(str("spookd_voice_preparing"));
        Utils.runOnBackgroundThread(() -> {
            String path = null;
            VoiceAudio.Result result = null;
            try {
                path = voiceFilePath();
                if (path == null) throw new IOException("No voice message path");
                result = VoiceAudio.convert(app, uri, startMs, endMs, path);
            } catch (Throwable t) {
                if (path != null) new File(path).delete();
                Logger.printException(() -> "VoiceUpload: cannot convert " + uri, t);
            }
            String converted = path;
            VoiceAudio.Result done = result;
            Utils.runOnMainThread(() -> {
                converting = false;
                if (done == null) Utils.showToastShort(str("spookd_voice_failed"));
                else send(converted, done);
            });
        });
    }

    /** Main thread. */
    private static void send(String path, VoiceAudio.Result result) {
        try {
            Object voiceController = chat.get();
            if (voiceController == null) throw new IllegalStateException("The chat was closed");
            sendRecording(voiceController, path, result.durationMs, result.waveform);
        } catch (Throwable t) {
            new File(path).delete();
            Logger.printException(() -> "VoiceUpload: cannot send " + path, t);
            Utils.showToastShort(str("spookd_voice_failed"));
        }
    }

    private static void fail(String what, Throwable t) {
        Logger.printException(() -> "VoiceUpload: cannot " + what, t);
        Utils.showToastShort(str("spookd_gallery_action_failed"));
    }

    /** The trim sheet: start and end bars, a preview of the selection, and Send. Main thread. */
    private static final class Trim implements SeekBar.OnSeekBarChangeListener {
        private final Activity activity;
        private final Uri uri;
        private final MediaPlayer player;
        private final long durationMs;
        private final Handler ui = new Handler(Looper.getMainLooper());
        private final Runnable tick = this::tick;
        private long start;
        private long end;
        private boolean playing;
        private boolean released;
        private TextView range;
        private SeekBar startBar;
        private SeekBar endBar;
        private Button play;

        Trim(Activity activity, Uri uri, MediaPlayer player, long durationMs) {
            this.activity = activity;
            this.uri = uri;
            this.player = player;
            this.durationMs = durationMs;
            long[] selection = VoiceAudio.clampRange(0, VoiceAudio.MAX_MS, durationMs, false);
            start = selection[0];
            end = selection[1];
        }

        void show() {
            Context context = InstagramPreferenceStyle.dialogContext(activity);
            float density = activity.getResources().getDisplayMetrics().density;
            LinearLayout layout = new LinearLayout(context);
            layout.setOrientation(LinearLayout.VERTICAL);
            int padding = (int) (24 * density);
            layout.setPadding(padding, (int) (8 * density), padding, 0);
            range = new TextView(context);
            layout.addView(range);
            startBar = bar(context, layout, "spookd_video_editor_start_label");
            endBar = bar(context, layout, "spookd_video_editor_end_label");
            TextView note = new TextView(context);
            note.setText(str("spookd_voice_limits"));
            layout.addView(note);
            update();
            AlertDialog dialog = new AlertDialog.Builder(context)
                    .setTitle(str("spookd_voice_trim_title"))
                    .setView(layout)
                    .setNegativeButton(str("spookd_cancel"), null)
                    .setNeutralButton(str("spookd_video_editor_play"), null)
                    .setPositiveButton(str("spookd_confirm_voice_action"), (shown, which) -> confirm())
                    .create();
            dialog.setOnDismissListener(shown -> release());
            player.setOnErrorListener((mp, what, extra) -> {
                dialog.dismiss();
                Utils.showToastShort(str("spookd_voice_unreadable"));
                return true;
            });
            // The seek is asynchronous, so playback starts once it lands on the start.
            player.setOnSeekCompleteListener(mp -> {
                if (!playing || released) return;
                try {
                    mp.start();
                    ui.postDelayed(tick, TICK_MS);
                } catch (IllegalStateException e) {
                    pause();
                }
            });
            player.setOnCompletionListener(mp -> pause());
            dialog.show();
            play = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            play.setOnClickListener(v -> toggle());
        }

        private SeekBar bar(Context context, LinearLayout layout, String label) {
            TextView title = new TextView(context);
            title.setText(str(label));
            layout.addView(title);
            SeekBar bar = new SeekBar(context);
            bar.setMax((int) (durationMs / STEP_MS));
            bar.setContentDescription(str(label));
            bar.setOnSeekBarChangeListener(this);
            layout.addView(bar);
            return bar;
        }

        @Override
        public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
            if (!fromUser) return;
            long ms = progress >= bar.getMax() ? durationMs : progress * STEP_MS;
            boolean startMoved = bar == startBar;
            long[] selection = VoiceAudio.clampRange(startMoved ? ms : start, startMoved ? end : ms, durationMs, startMoved);
            start = selection[0];
            end = selection[1];
            pause();
            update();
        }

        @Override
        public void onStartTrackingTouch(SeekBar bar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar bar) {
        }

        private void update() {
            range.setText(str("spookd_video_editor_range", VoiceAudio.time(start), VoiceAudio.time(end),
                    VoiceAudio.time(end - start)));
            startBar.setProgress((int) (start / STEP_MS));
            endBar.setProgress(end >= durationMs ? endBar.getMax() : (int) (end / STEP_MS));
        }

        private void toggle() {
            if (playing) {
                pause();
                return;
            }
            try {
                playing = true;
                play.setText(str("spookd_video_editor_pause"));
                player.seekTo((int) start);
            } catch (IllegalStateException e) {
                Logger.printException(() -> "VoiceUpload: cannot play the preview", e);
                pause();
            }
        }

        private void tick() {
            if (!playing || released) return;
            try {
                if (player.getCurrentPosition() >= end) pause();
                else ui.postDelayed(tick, TICK_MS);
            } catch (IllegalStateException e) {
                pause();
            }
        }

        private void pause() {
            ui.removeCallbacks(tick);
            try {
                if (playing && !released && player.isPlaying()) player.pause();
            } catch (IllegalStateException e) {
                Logger.printException(() -> "VoiceUpload: cannot pause the preview", e);
            }
            playing = false;
            if (play != null) play.setText(str("spookd_video_editor_play"));
        }

        private void release() {
            pause();
            released = true;
            player.release();
        }

        private void confirm() {
            long from = start;
            long to = end;
            Context app = activity.getApplicationContext();
            if (!ActionConfirm.askVoiceSend(() -> convertAndSend(app, uri, from, to), null)) {
                Utils.showToastShort(str("spookd_gallery_action_failed"));
            }
        }
    }

    /** Headless: opens the system file picker for one audio file and opens the trim sheet with it. */
    public static final class FilePicker extends Fragment {
        private static final int REQUEST = 0x5ad0;

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            // A recreated picker has lost its chat with the old screen.
            if (savedInstanceState != null) {
                done();
                return;
            }
            try {
                startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("audio/*"), REQUEST);
            } catch (Exception e) {
                Logger.printException(() -> "VoiceUpload: no file picker", e);
                Utils.showToastShort(str(e instanceof ActivityNotFoundException
                        ? "spookd_file_picker_unavailable" : "spookd_gallery_action_failed"));
                done();
            }
        }

        @Override
        public void onActivityResult(int requestCode, int resultCode, Intent data) {
            super.onActivityResult(requestCode, resultCode, data);
            if (requestCode != REQUEST) return;
            Activity activity = getActivity();
            Uri uri = resultCode == Activity.RESULT_OK && data != null ? data.getData() : null;
            done();
            if (uri != null && activity != null && !activity.isFinishing()) openTrim(activity, uri);
        }

        private void done() {
            FragmentManager fragments = getFragmentManager();
            if (fragments != null) fragments.beginTransaction().remove(this).commitAllowingStateLoss();
        }
    }
}
