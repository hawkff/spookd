package app.morphe.extension.instagram.patches.dm.voice;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.app.Dialog;
import android.app.Fragment;
import android.app.FragmentManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
    private static final String COPY_DIR = "spookd_voice";
    private static final long COPY_KEEP_MS = 24 * 60 * 60_000L;
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
            // Without its chat screen the send could not be checked to still go to this chat.
            Object screen = chatScreenOf(voiceController);
            if (screen == null) return false;
            // Mirrors the recorder's own sample rate choice.
            VoiceAudio.Format format = VoiceAudio.Format.of(recorderSettings(voiceController), "Pixel 4".equals(Build.MODEL));
            if (format == null) {
                // Holding the mic then records as usual.
                Utils.showToastShort(str("spookd_voice_format_unsupported"));
                return false;
            }
            showSources(activity, new VoiceDestination(voiceController, screen, threadOf(voiceController), format));
            return true;
        } catch (Throwable t) {
            Logger.printException(() -> "VoiceUpload: cannot open the sheet", t);
            return false;
        }
    }

    /**
     * The patch replaces this body with the settings of the chat's recorder, {encoder, bit rate, sample rate,
     * Pixel 4 sample rate}, or null when the chat records with a recorder other than MediaRecorder.
     */
    public static int[] recorderSettings(Object voiceController) {
        return null;
    }

    // The patch replaces this body with a read of the voice controller's chat screen.
    public static Object chatScreenOf(Object voiceController) {
        return null;
    }

    // The patch replaces this body with a read of the voice controller's thread.
    public static Object chatThreadOf(Object voiceController) {
        return null;
    }

    private static String threadOf(Object voiceController) {
        return String.valueOf(chatThreadOf(voiceController));
    }

    /** Main thread: whether {@code screen}, a chat screen, is the one open now. */
    private static boolean isOpen(Object screen) {
        if (!(screen instanceof androidx.fragment.app.Fragment)) return false;
        androidx.fragment.app.Fragment chat = (androidx.fragment.app.Fragment) screen;
        Activity current = Utils.getActivity();
        return current != null && chat.getActivity() == current && chat.isResumed() && !chat.isRemoving();
    }

    // The patch replaces this body with a new file path in Instagram's voice message folder.
    public static String voiceFilePath() {
        return null;
    }

    // The patch replaces this body: the file goes to the voice controller as a finished recording, which sends it.
    public static void sendRecording(Object voiceController, String path, int durationMs, List<Float> waveform) {
        throw new IllegalStateException("Not patched");
    }

    private static void showSources(Activity activity, VoiceDestination destination) {
        CharSequence[] sources = Gallery.canOpen(activity)
                ? new CharSequence[]{str("spookd_voice_from_files"), str("spookd_gallery_picker_entry")}
                : new CharSequence[]{str("spookd_voice_from_files")};
        new AlertDialog.Builder(InstagramPreferenceStyle.dialogContext(activity))
                .setTitle(str("spookd_voice_sheet_title"))
                .setItems(sources, (shown, which) -> {
                    if (which == 0) pickFile(activity, destination);
                    else pickFromGallery(activity, destination);
                })
                .setNegativeButton(str("spookd_cancel"), null)
                .show();
    }

    private static void pickFile(Activity activity, VoiceDestination destination) {
        try {
            FragmentManager fragments = activity.getFragmentManager();
            if (activity.isFinishing() || fragments.findFragmentByTag(PICKER_TAG) != null) return;
            FilePicker picker = new FilePicker();
            picker.destination = destination;
            fragments.beginTransaction().add(picker, PICKER_TAG).commitAllowingStateLoss();
        } catch (Exception e) {
            fail("open the file picker", e);
        }
    }

    /** Asks for the screen lock as the gallery does, then lists its audio on a background thread. */
    private static void pickFromGallery(Activity activity, VoiceDestination destination) {
        try {
            InboxLock.confirm(activity, () -> {
                // Unlocked from here until the screen stops; every later step checks it.
                Session session = new Session(activity);
                Utils.runOnBackgroundThread(() -> {
                    try {
                        List<GalleryStore.Entry> audio = GalleryStore.get().list(null, GalleryItem.AUDIO, false, null, null);
                        Utils.runOnMainThread(() -> showGalleryAudio(activity, audio, destination, session));
                    } catch (Exception e) {
                        Utils.runOnMainThread(session::end);
                        fail("list the gallery's audio", e);
                    }
                });
            });
        } catch (Exception e) {
            fail("ask for the screen lock", e);
        }
    }

    private static void showGalleryAudio(Activity activity, List<GalleryStore.Entry> audio,
                                         VoiceDestination destination, Session session) {
        try {
            if (session.stopped || activity.isFinishing() || activity.isDestroyed()) {
                session.end();
                return;
            }
            if (audio.isEmpty()) {
                session.end();
                Utils.showToastShort(str("spookd_voice_gallery_empty"));
                return;
            }
            CharSequence[] names = new CharSequence[audio.size()];
            for (int i = 0; i < names.length; i++) names[i] = String.valueOf(audio.get(i).fileName);
            boolean[] picked = {false};
            AlertDialog dialog = new AlertDialog.Builder(InstagramPreferenceStyle.dialogContext(activity))
                    .setTitle(str("spookd_gallery_picker_entry"))
                    .setItems(names, (shown, which) -> {
                        picked[0] = true;
                        openTrim(activity, Uri.fromFile(audio.get(which).file), false, destination, session);
                    })
                    .setNegativeButton(str("spookd_cancel"), null)
                    .create();
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            dialog.setOnDismissListener(shown -> {
                if (!picked[0]) session.end();
            });
            session.show(dialog);
        } catch (Exception e) {
            session.end();
            fail("show the gallery's audio", e);
        }
    }

    /**
     * Main thread: on a background thread copies a picked document ({@code copy}), which may first have to
     * download, and prepares a preview player; its length then opens the trim sheet on the main thread.
     */
    private static void openTrim(Activity activity, Uri uri, boolean copy, VoiceDestination destination,
                                 Session session) {
        if (copy) Utils.showToastShort(str("spookd_voice_opening"));
        Context app = activity.getApplicationContext();
        Utils.runOnBackgroundThread(() -> {
            File temp = null;
            MediaPlayer player = null;
            try {
                if (copy) temp = copyToCache(app, uri);
                Uri source = temp == null ? uri : Uri.fromFile(temp);
                player = new MediaPlayer();
                player.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build());
                player.setDataSource(app, source);
                player.prepare();
                MediaPlayer prepared = player;
                File copied = temp;
                Utils.runOnMainThread(() -> showTrim(activity, source, copied, prepared, destination, session));
            } catch (Exception e) {
                if (player != null) player.release();
                if (temp != null) temp.delete();
                Logger.printException(() -> "VoiceUpload: cannot read " + uri, e);
                Utils.showToastShort(str("spookd_voice_unreadable"));
                Utils.runOnMainThread(session::end);
            }
        });
    }

    /** Background thread: a copy of document {@code uri} in the cache, so reading it never waits on its provider. */
    private static File copyToCache(Context app, Uri uri) throws IOException {
        File dir = new File(app.getCacheDir(), COPY_DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        // Copies a killed process left behind.
        File[] old = dir.listFiles();
        long now = System.currentTimeMillis();
        if (old != null) for (File file : old) if (now - file.lastModified() > COPY_KEEP_MS) file.delete();
        File copy = new File(dir, UUID.randomUUID().toString());
        try (InputStream in = app.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(copy)) {
            if (in == null) throw new IOException("No stream for " + uri);
            byte[] buffer = new byte[64 * 1024];
            for (int read; (read = in.read(buffer)) > 0; ) out.write(buffer, 0, read);
        } catch (IOException | RuntimeException e) {
            copy.delete();
            throw e;
        }
        return copy;
    }

    /** Main thread; {@code temp}, a copy made for this pick or null, goes with the trim sheet. */
    private static void showTrim(Activity activity, Uri uri, File temp, MediaPlayer player,
                                 VoiceDestination destination, Session session) {
        try {
            int duration = player.getDuration();
            if (session.stopped || activity.isFinishing() || activity.isDestroyed() || duration < VoiceAudio.MIN_MS) {
                player.release();
                if (temp != null) temp.delete();
                session.end();
                // An unknown length is -1.
                if (duration < VoiceAudio.MIN_MS && !session.stopped) {
                    Utils.showToastShort(str(duration <= 0 ? "spookd_voice_unreadable" : "spookd_voice_too_short"));
                }
                return;
            }
            new Trim(activity, uri, temp, player, duration, destination, session).show();
        } catch (Exception e) {
            player.release();
            if (temp != null) temp.delete();
            session.end();
            fail("show the trim sheet", e);
        }
    }

    /** Main thread, once Send was confirmed: converts on a background thread, then sends on the main thread. */
    private static void convertAndSend(Context app, Uri uri, File temp, long startMs, long endMs,
                                       VoiceDestination destination) {
        if (openController(destination) == null || converting) {
            if (temp != null) temp.delete();
            Utils.showToastShort(str(converting ? "spookd_voice_busy" : "spookd_voice_chat_changed"));
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
                result = VoiceAudio.convert(app, uri, startMs, endMs, destination.format, path);
            } catch (Throwable t) {
                if (path != null) new File(path).delete();
                Logger.printException(() -> "VoiceUpload: cannot convert " + uri, t);
            }
            if (temp != null) temp.delete();
            String converted = path;
            VoiceAudio.Result done = result;
            Utils.runOnMainThread(() -> {
                converting = false;
                if (done == null) Utils.showToastShort(str("spookd_voice_failed"));
                else send(converted, done, destination);
            });
        });
    }

    /** Main thread: the bound chat's controller while that chat is the open one, else null. */
    private static Object openController(VoiceDestination destination) {
        return destination.openController(VoiceUpload::isOpen, VoiceUpload::threadOf);
    }

    /** Main thread: sends only to the chat the file was picked for, and only while it is open. */
    private static void send(String path, VoiceAudio.Result result, VoiceDestination destination) {
        try {
            Object voiceController = openController(destination);
            if (voiceController == null) {
                new File(path).delete();
                Logger.printInfo(() -> "VoiceUpload: chat " + destination.thread + " is not open, dropped " + path);
                Utils.showToastShort(str("spookd_voice_chat_changed"));
                return;
            }
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
        // A copy made for this pick, deleted with the sheet unless Send takes it.
        private final File temp;
        private final MediaPlayer player;
        private final long durationMs;
        private final VoiceDestination destination;
        private final Session session;
        private final Handler ui = new Handler(Looper.getMainLooper());
        private final Runnable tick = this::tick;
        private long start;
        private long end;
        private boolean playing;
        private boolean released;
        private boolean handedOff;
        private TextView range;
        private SeekBar startBar;
        private SeekBar endBar;
        private Button play;

        Trim(Activity activity, Uri uri, File temp, MediaPlayer player, long durationMs,
             VoiceDestination destination, Session session) {
            this.activity = activity;
            this.temp = temp;
            this.destination = destination;
            this.session = session;
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
            if (!session.show(dialog)) {
                release();
                return;
            }
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
            if (released) return;
            pause();
            released = true;
            player.release();
            if (temp != null && !handedOff) temp.delete();
            session.end();
        }

        private void confirm() {
            long from = start;
            long to = end;
            Context app = activity.getApplicationContext();
            Runnable dropCopy = () -> {
                if (temp != null) temp.delete();
            };
            handedOff = ActionConfirm.askVoiceSend(() -> convertAndSend(app, uri, temp, from, to, destination), dropCopy);
            if (!handedOff) Utils.showToastShort(str("spookd_gallery_action_failed"));
        }
    }

    /**
     * The dialogs of one pick, closed when their screen stops: private items and the preview never stay
     * open after leaving Instagram, where the lock engages again. Main thread.
     */
    private static final class Session implements Application.ActivityLifecycleCallbacks {
        private final Activity host;
        private final List<Dialog> dialogs = new ArrayList<>();
        // Set once the screen stopped; a step that comes back later shows nothing.
        boolean stopped;
        private boolean ended;

        Session(Activity host) {
            this.host = host;
            host.getApplication().registerActivityLifecycleCallbacks(this);
        }

        /** Shows {@code dialog}, or nothing and false when the screen stopped meanwhile. */
        boolean show(Dialog dialog) {
            if (stopped) return false;
            dialogs.add(dialog);
            dialog.show();
            return true;
        }

        void end() {
            if (ended) return;
            ended = true;
            host.getApplication().unregisterActivityLifecycleCallbacks(this);
        }

        private void close(Activity activity) {
            if (activity != host || stopped) return;
            stopped = true;
            end();
            for (Dialog dialog : new ArrayList<>(dialogs)) dialog.dismiss();
            dialogs.clear();
        }

        @Override
        public void onActivityStopped(Activity activity) {
            close(activity);
        }

        @Override
        public void onActivityDestroyed(Activity activity) {
            close(activity);
        }

        @Override
        public void onActivityCreated(Activity activity, Bundle state) {
        }

        @Override
        public void onActivityStarted(Activity activity) {
        }

        @Override
        public void onActivityResumed(Activity activity) {
        }

        @Override
        public void onActivityPaused(Activity activity) {
        }

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle state) {
        }
    }

    /** Headless: opens the system file picker for one audio file and opens the trim sheet with it. */
    public static final class FilePicker extends Fragment {
        private static final int REQUEST = 0x5ad0;
        VoiceDestination destination;

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            // A recreated picker has lost the chat it picks for.
            if (savedInstanceState != null || destination == null) {
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
            if (uri != null && activity != null && !activity.isFinishing()) {
                openTrim(activity, uri, true, destination, new Session(activity));
            }
        }

        private void done() {
            FragmentManager fragments = getFragmentManager();
            if (fragments != null) fragments.beginTransaction().remove(this).commitAllowingStateLoss();
        }
    }
}
