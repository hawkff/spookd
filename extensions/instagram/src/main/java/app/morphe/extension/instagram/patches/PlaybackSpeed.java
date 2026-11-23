/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.content.Context;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Iterator;
import java.util.Locale;
import java.util.WeakHashMap;

import app.morphe.extension.crimera.sharedPreference.SharedPref;
import app.morphe.extension.instagram.settings.Settings;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

public final class PlaybackSpeed {
    static final float[] PRESETS = {0.5f, 1f, 1.25f, 1.5f, 1.75f, 2f};
    static final int SKIP_MS = 10_000;
    // Instagram's player clamps speeds to this range.
    private static final float MIN_SPEED = 0.25f;
    private static final float MAX_SPEED = 4f;

    private static volatile WeakReference<Object> startedReel = new WeakReference<>(null);
    // Instagram's per-media playback state of Reels whose speed was picked in Instagram's menu.
    private static final WeakHashMap<Object, Boolean> menuPicks = new WeakHashMap<>();
    // Speeds picked in the panel, by the Reel item the player plays.
    private static final WeakHashMap<Object, Float> panelPicks = new WeakHashMap<>();

    // A holder, so the pure helpers below load without the settings classes.
    private static final class DefaultSpeed {
        static final Pref.Cached<Float> VALUE = new Pref.Cached<>(
                () -> parseSpeed(SharedPref.getStringPref(Settings.DEFAULT_PLAYBACK_SPEED)),
                Settings.DEFAULT_PLAYBACK_SPEED.key);
    }

    private PlaybackSpeed() {}

    /** Called when a Reels or feed video is prepared. 1 leaves Instagram's speed alone. */
    public static float defaultSpeed() {
        try {
            return DefaultSpeed.VALUE.get();
        } catch (Throwable t) {
            Logger.printException(() -> "Failed to read the default playback speed", t);
            return 1f;
        }
    }

    /** Called when a Reels player starts playing. */
    public static void onReelStarted(Object player) {
        if (startedReel.get() != player) startedReel = new WeakReference<>(player);
        ReelsPlayerOptions.onReelStarted(player);
    }

    /** Called on the main thread when a speed is picked in Instagram's Reels menu. */
    public static void onMenuSpeedPicked(Object speedOption, Object menuHelper) {
        try {
            Object state = menuMediaState(menuHelper);
            Object media = menuMedia(menuHelper);
            synchronized (PlaybackSpeed.class) {
                if (state != null) menuPicks.put(state, Boolean.TRUE);
                // The menu pick replaces an earlier panel pick for the same Reel.
                for (Iterator<Object> items = panelPicks.keySet().iterator(); items.hasNext(); ) {
                    if (holdsMedia(items.next(), media)) items.remove();
                }
            }
            // Instagram skips a pick equal to its stored 1x, while the player runs at the default.
            if (optionSpeed(speedOption) == 1f && defaultSpeed() != 1f) {
                Object player = startedReel.get();
                if (player != null && holdsMedia(playingContent(player), media)) setSpeed(player, 1f);
            }
        } catch (Throwable t) {
            Logger.printException(() -> "Failed to note a Reels menu speed", t);
        }
    }

    /** Called on the main thread when the panel sets a speed. */
    static void onPanelSpeedPicked(Object player, float speed) {
        Object item = playingContent(player);
        if (item == null) return;
        synchronized (PlaybackSpeed.class) {
            panelPicks.put(item, speed);
        }
    }

    /**
     * Called on the main thread with the speed Instagram restores when a Reel starts or resumes.
     * Returns the speed to apply instead.
     */
    public static Float reelResumeSpeed(Float nativeSpeed, Object item, Object itemState) {
        if (nativeSpeed == null) return null;
        try {
            Float panelSpeed;
            boolean menuPick;
            synchronized (PlaybackSpeed.class) {
                panelSpeed = panelPicks.get(item);
                Object state = itemMediaState(itemState);
                menuPick = state != null && menuPicks.containsKey(state);
            }
            return resolveReelSpeed(nativeSpeed, panelSpeed, menuPick, defaultSpeed());
        } catch (Throwable t) {
            Logger.printException(() -> "Failed to resolve the Reel speed", t);
            return nativeSpeed;
        }
    }

    // The default replaces only Instagram's 1x for a Reel without a pick; other restored speeds are left alone.
    static Float resolveReelSpeed(Float nativeSpeed, Float panelSpeed, boolean menuPick, float defaultSpeed) {
        if (nativeSpeed == null) return null;
        if (panelSpeed != null) return panelSpeed;
        if (menuPick || nativeSpeed != 1f) return nativeSpeed;
        return defaultSpeed;
    }

    /** Opens the panel for the reel whose menu row was tapped. Runs on the main thread. */
    public static void openPanel(Context context, Object media) {
        try {
            Object player = startedReel.get();
            if (player == null || !holdsMedia(playingContent(player), media)) {
                Utils.showToastShort(str("spookd_speed_panel_no_video"));
                return;
            }
            PlaybackSpeedPanel.show(context, player);
        } catch (Throwable t) {
            Logger.printException(() -> "Failed to open the speed panel", t);
        }
    }

    // The bodies below are replaced at patch time with calls into Instagram's video player.
    static Object playingContent(Object player) {
        return null;
    }

    private static Object menuMediaState(Object menuHelper) {
        return null;
    }

    private static Object itemMediaState(Object itemState) {
        return null;
    }

    private static Object menuMedia(Object menuHelper) {
        return null;
    }

    private static float optionSpeed(Object speedOption) {
        return 0f;
    }

    static int positionMs(Object player) {
        return 0;
    }

    static int durationMs(Object player) {
        return 0;
    }

    // Instagram's own seek flags; false for both is a plain seek.
    static void seekTo(Object player, int positionMs, boolean flag) {
    }

    static void setSpeed(Object player, float speed) {
    }

    static float parseSpeed(String value) {
        try {
            float speed = Float.parseFloat(value);
            return speed >= MIN_SPEED && speed <= MAX_SPEED ? speed : 1f;
        } catch (RuntimeException e) {
            return 1f;
        }
    }

    static String speedLabel(float speed) {
        return (speed == (int) speed ? Integer.toString((int) speed) : Float.toString(speed)) + "x";
    }

    static String formatTime(int ms) {
        int seconds = Math.max(ms, 0) / 1000;
        int hours = seconds / 3600;
        return hours > 0
                ? String.format(Locale.US, "%d:%02d:%02d", hours, seconds / 60 % 60, seconds % 60)
                : String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60);
    }

    // Stops one millisecond short of the end so a skip forward does not count as finishing the video.
    static int seekTarget(int positionMs, int deltaMs, int durationMs) {
        return Math.max(0, Math.min(positionMs + deltaMs, durationMs - 1));
    }

    // The Reels player plays a wrapper item; the media sits in one of its fields.
    static boolean holdsMedia(Object content, Object media) throws IllegalAccessException {
        if (content == null || media == null) return false;
        if (media.equals(content)) return true;
        for (Class<?> type = content.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                field.setAccessible(true);
                Object value = field.get(content);
                if (value != null && media.equals(value)) return true;
            }
        }
        return false;
    }
}
