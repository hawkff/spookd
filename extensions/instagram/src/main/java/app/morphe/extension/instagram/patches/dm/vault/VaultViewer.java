package app.morphe.extension.instagram.patches.dm.vault;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.app.Activity;
import android.app.Dialog;
import android.app.KeyguardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.MediaDataSource;
import android.media.MediaPlayer;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.MediaController;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import app.morphe.extension.instagram.patches.dm.InboxLock;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Full-screen viewer of one stored DM media item, owned by the deleted-messages activity.
 * Decrypted bytes stay in memory. Everything here runs on the main thread except the open and decode.
 */
public final class VaultViewer extends Dialog {
    // Bitmaps above this many pixels are sampled down; drawing a much larger one throws.
    private static final long MAX_PIXELS = 16_000_000;
    private static final Executor LOADER = Executors.newSingleThreadExecutor();
    private static final Map<Activity, VaultViewer> shown = new WeakHashMap<>();
    private static final Set<Activity> confirmed = Collections.newSetFromMap(new WeakHashMap<>());
    // The token of each activity's outstanding unlock request; only that request's success may show media.
    private static final Map<Activity, Object> pending = new WeakHashMap<>();

    private final Activity activity;
    private final FrameLayout root;
    private volatile boolean released;
    private VaultFile file;
    private MediaPlayer player;
    private MediaController controller;
    private boolean prepared;

    /**
     * Shows the stored media of a saved message once the device credential is confirmed; the
     * confirmation lasts until {@link #close} runs when the activity stops.
     */
    public static void open(Activity activity, String messageId) {
        try {
            if (confirmed.contains(activity)) {
                showFor(activity, messageId);
                return;
            }
            KeyguardManager keyguard = (KeyguardManager) activity.getSystemService(Context.KEYGUARD_SERVICE);
            // InboxLock lets everyone through without a secure lock screen, so it is never asked then.
            // Android 10 confirms on a separate screen, which stops this activity and lets Inbox lock close it mid-check.
            if (Build.VERSION.SDK_INT < 30 || keyguard == null || !keyguard.isDeviceSecure()) {
                showFor(activity, null);
                return;
            }
            // InboxLock drops a request while any prompt is up and keeps that prompt's callback.
            if (InboxLock.isPrompting()) {
                Utils.showToastShort(str("piko_vault_finish_unlocking"));
                return;
            }
            Object request = new Object();
            pending.put(activity, request);
            InboxLock.confirm(activity, () -> {
                if (pending.get(activity) != request) return;
                pending.remove(activity);
                if (activity.isFinishing() || activity.isDestroyed()) return;
                confirmed.add(activity);
                open(activity, messageId);
            });
        } catch (Exception e) {
            Logger.printException(() -> "VaultViewer.open failed", e);
        }
    }

    /**
     * Dismisses the activity's viewer and ends its confirmation and any outstanding unlock request;
     * call when the activity stops or is destroyed. An unlock that completes afterwards shows nothing.
     */
    public static void close(Activity activity) {
        try {
            pending.remove(activity);
            confirmed.remove(activity);
            VaultViewer viewer = shown.remove(activity);
            if (viewer != null) viewer.dismiss();
        } catch (Exception e) {
            Logger.printException(() -> "VaultViewer.close failed", e);
        }
    }

    private static void showFor(Activity activity, String messageId) {
        VaultViewer previous = shown.remove(activity);
        if (previous != null) previous.dismiss();
        VaultViewer viewer = new VaultViewer(activity);
        shown.put(activity, viewer);
        viewer.show();
        if (messageId == null) {
            viewer.showText(str("piko_vault_needs_screen_lock"));
        } else {
            LOADER.execute(() -> viewer.load(messageId));
        }
    }

    private VaultViewer(Activity activity) {
        super(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        this.activity = activity;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        root = new FrameLayout(activity);
        root.setBackgroundColor(Color.BLACK);
        root.addView(new ProgressBar(activity), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        root.setOnClickListener(v -> {
            if (controller == null) return;
            if (controller.isShowing()) controller.hide();
            else controller.show();
        });
        setContentView(root);
        setOnDismissListener(d -> release());
    }

    @Override
    public void dismiss() {
        // The controls live in their own window, which must go before this one.
        if (controller != null) controller.hide();
        super.dismiss();
        shown.remove(activity, this);
        release();
    }

    /** Runs on the loader thread. */
    private void load(String messageId) {
        try {
            MediaVault.Stored stored = MediaVault.get().openForMessage(messageId);
            if (stored == null) {
                Utils.runOnMainThread(() -> showText(str("piko_media_unreadable")));
                return;
            }
            if (!adopt(stored.file)) {
                closeQuietly(stored.file);
                return;
            }
            if ("photo".equals(stored.kind)) {
                Bitmap bitmap = decode(stored.file);
                Utils.runOnMainThread(() -> {
                    if (bitmap == null) showText(str("piko_media_unreadable"));
                    else showPhoto(bitmap);
                });
            } else {
                boolean video = "video".equals(stored.kind);
                Utils.runOnMainThread(() -> play(stored.file, video));
            }
        } catch (Exception | OutOfMemoryError e) {
            Logger.printException(() -> "VaultViewer: cannot open media of " + messageId, e);
            Utils.runOnMainThread(() -> showText(str("piko_media_unreadable")));
        }
    }

    /** Hands the open file to this viewer; false when it was already dismissed. */
    private synchronized boolean adopt(VaultFile opened) {
        if (released) return false;
        file = opened;
        return true;
    }

    private Bitmap decode(VaultFile vault) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeStream(vault.openStream(), null, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        DisplayMetrics screen = activity.getResources().getDisplayMetrics();
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, screen.widthPixels, screen.heightPixels);
        return BitmapFactory.decodeStream(vault.openStream(), null, options);
    }

    /** The largest power-of-two step that still covers the screen, raised until the bitmap fits MAX_PIXELS. */
    static int sampleSize(int width, int height, int screenWidth, int screenHeight) {
        int sample = 1;
        while ((width / (sample * 2) >= Math.max(1, screenWidth) && height / (sample * 2) >= Math.max(1, screenHeight))
                || (long) (width / sample) * (height / sample) > MAX_PIXELS) {
            sample *= 2;
        }
        return sample;
    }

    private void showText(String text) {
        if (released) return;
        root.removeAllViews();
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextColor(Color.WHITE);
        view.setTextSize(16);
        view.setGravity(Gravity.CENTER);
        int padding = (int) (24 * activity.getResources().getDisplayMetrics().density);
        view.setPadding(padding, padding, padding, padding);
        root.addView(view, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private void showPhoto(Bitmap bitmap) {
        if (released) return;
        root.removeAllViews();
        ImageView view = new ImageView(activity);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.setImageBitmap(bitmap);
        root.addView(view, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private void play(VaultFile vault, boolean video) {
        if (released) return;
        root.removeAllViews();
        MediaPlayer media = new MediaPlayer();
        player = media;
        try {
            media.setDataSource(new Source(vault));
        } catch (RuntimeException e) {
            Logger.printException(() -> "VaultViewer: player rejected the media", e);
            showText(str("piko_media_unreadable"));
            return;
        }
        media.setOnErrorListener((mp, what, extra) -> {
            Logger.printInfo(() -> "VaultViewer: playback error " + what + "/" + extra);
            prepared = false;
            if (controller != null) controller.hide();
            controller = null;
            showText(str("piko_media_unreadable"));
            return true;
        });
        if (video) {
            SurfaceView surface = new SurfaceView(activity);
            surface.setSecure(true);
            root.addView(surface, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));
            surface.getHolder().addCallback(new SurfaceHolder.Callback() {
                @Override
                public void surfaceCreated(SurfaceHolder holder) {
                    display(holder);
                }

                @Override
                public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                }

                @Override
                public void surfaceDestroyed(SurfaceHolder holder) {
                    display(null);
                }
            });
            media.setOnVideoSizeChangedListener((mp, width, height) -> fit(surface, width, height));
        }
        controller = new MediaController(activity);
        controller.setMediaPlayer(new Control());
        controller.setAnchorView(root);
        media.setOnPreparedListener(mp -> {
            if (released) return;
            prepared = true;
            mp.start();
            // Voice messages have nothing else on screen, so their controls stay up.
            if (controller != null) controller.show(video ? 3000 : 0);
        });
        media.prepareAsync();
    }

    private void display(SurfaceHolder holder) {
        try {
            if (player != null) player.setDisplay(holder);
        } catch (RuntimeException e) {
            Logger.printException(() -> "VaultViewer: cannot set the video surface", e);
        }
    }

    private void fit(View surface, int width, int height) {
        int maxWidth = root.getWidth();
        int maxHeight = root.getHeight();
        if (width <= 0 || height <= 0 || maxWidth <= 0 || maxHeight <= 0) return;
        float scale = Math.min(maxWidth / (float) width, maxHeight / (float) height);
        surface.setLayoutParams(new FrameLayout.LayoutParams(
                Math.round(width * scale), Math.round(height * scale), Gravity.CENTER));
    }

    /** Stops playback and closes the file; safe to call more than once. */
    private void release() {
        MediaPlayer media;
        VaultFile vault;
        synchronized (this) {
            released = true;
            media = player;
            player = null;
            vault = file;
            file = null;
        }
        controller = null;
        prepared = false;
        // The player stops reading before the file it reads from is closed.
        if (media != null) media.release();
        closeQuietly(vault);
    }

    private static void closeQuietly(VaultFile vault) {
        if (vault == null) return;
        try {
            vault.close();
        } catch (IOException ignored) {
        }
    }

    /** Feeds the player decrypted bytes straight from the vault file. Called on the player's threads. */
    private static final class Source extends MediaDataSource {
        private final VaultFile vault;

        Source(VaultFile vault) {
            this.vault = vault;
        }

        @Override
        public int readAt(long position, byte[] buffer, int offset, int size) throws IOException {
            return vault.readAt(position, buffer, offset, size);
        }

        @Override
        public long getSize() {
            return vault.length();
        }

        @Override
        public void close() {
            // The viewer closes the file after releasing the player.
        }
    }

    private final class Control implements MediaController.MediaPlayerControl {
        @Override public void start() { if (prepared && player != null) player.start(); }
        @Override public void pause() { if (prepared && player != null) player.pause(); }
        @Override public int getDuration() { return prepared && player != null ? player.getDuration() : 0; }
        @Override public int getCurrentPosition() { return prepared && player != null ? player.getCurrentPosition() : 0; }
        @Override public void seekTo(int position) { if (prepared && player != null) player.seekTo(position); }
        @Override public boolean isPlaying() { return prepared && player != null && player.isPlaying(); }
        @Override public int getBufferPercentage() { return 100; }
        @Override public boolean canPause() { return true; }
        @Override public boolean canSeekBackward() { return true; }
        @Override public boolean canSeekForward() { return true; }
        @Override public int getAudioSessionId() { return player != null ? player.getAudioSessionId() : 0; }
    }
}
