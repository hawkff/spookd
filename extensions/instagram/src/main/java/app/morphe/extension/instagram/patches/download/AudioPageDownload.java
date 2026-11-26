/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.download;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import app.morphe.extension.crimera.sharedPreference.SharedPref;
import app.morphe.extension.instagram.constants.Constants;
import app.morphe.extension.instagram.constants.UI;
import app.morphe.extension.instagram.entity.OriginalSoundDataIntf;
import app.morphe.extension.instagram.settings.Settings;
import app.morphe.extension.instagram.settings.SettingsStatus;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.ui.Dim;

/** A download button in the action bar of an original audio page, using the 'Download audio' path. */
@SuppressWarnings("unused")
public final class AudioPageDownload {

    private static final String BUTTON_TAG = "spookd_audio_page_download";
    private static final String ACTION_BAR_CLASS = "com.instagram.igds.components.actionbar.IgdsActionBar";
    private static final String ORIGINAL_SOUND_CLASS = "com.instagram.api.schemas.OriginalSoundDataIntf";
    // The page handler reaches the audio through its page model and the model's audio asset.
    static final int MAX_DEPTH = 3;
    private static final int MAX_VISITED = 200;

    private AudioPageDownload() {
    }

    /** Main thread, at the start of the audio page's action bar bind; adds the button once that bind ran. */
    public static void onActionBarBound(Object handler) {
        if (handler == null || !SettingsStatus.audioPageDownloadButton) return;
        Utils.runOnMainThread(() -> {
            try {
                if (!Boolean.TRUE.equals(SharedPref.getBooleanPref(Settings.ENABLE_DOWNLOAD))
                        || !Boolean.TRUE.equals(SharedPref.getBooleanPref(Settings.AUDIO_PAGE_DOWNLOAD_BUTTON))) {
                    return;
                }
                ViewGroup actionBar = actionBar(handler);
                if (actionBar == null || actionBar.findViewWithTag(BUTTON_TAG) != null) return;
                ImageView button = new ImageView(actionBar.getContext());
                button.setTag(BUTTON_TAG);
                UI.setThemedIcon(button, UI.DRAWABLE_DOWNLOAD_ICON);
                button.setScaleType(ImageView.ScaleType.CENTER);
                button.setContentDescription(str("spookd_download_audio"));
                button.setOnClickListener(v -> download(v.getContext(), handler));
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(Dim.dp48, Dim.dp48);
                params.gravity = android.view.Gravity.CENTER_VERTICAL;
                // Before the last child, where the page puts its own extra action.
                actionBar.addView(button, Math.max(0, actionBar.getChildCount() - 1), params);
            } catch (Exception e) {
                Logger.printException(() -> "AudioPageDownload: button failed", e);
            }
        });
    }

    private static ViewGroup actionBar(Object handler) throws Exception {
        for (Class<?> type = handler.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())
                        || !ACTION_BAR_CLASS.equals(field.getType().getName())) continue;
                field.setAccessible(true);
                Object value = field.get(handler);
                return value instanceof LinearLayout ? (ViewGroup) value : null;
            }
        }
        return null;
    }

    /** Main thread, on tap: downloads the page's original audio, or says it can't. */
    private static void download(Context context, Object handler) {
        try {
            Object sound = findOriginalSound(handler, Class.forName(ORIGINAL_SOUND_CLASS));
            if (sound == null) {
                Utils.showToastShort(str("spookd_audio_page_download_unavailable"));
                return;
            }
            if (!Utils.isNetworkConnected()) {
                Utils.showToastShort(str("spookd_no_internet"));
                return;
            }
            OriginalSoundDataIntf audio = new OriginalSoundDataIntf(sound);
            String url = audio.getAudioUrl();
            if (url == null || !url.startsWith("http")) {
                Utils.showToastShort(str("spookd_audio_page_download_unavailable"));
                return;
            }
            DownloadUtils.downloadMediaUrl(context, url, Constants.DEFAULT_AUDIO_FOLDER,
                    audio.getDownloadName() + ".mp3", audio.getUserData().getUsername());
        } catch (Exception e) {
            Logger.printException(() -> "AudioPageDownload: download failed", e);
            Utils.showToastShort(str("spookd_download_failed_media"));
        }
    }

    /** The nearest object of soundType reachable from root through instance fields, or null; Views and platform types are not entered. */
    static Object findOriginalSound(Object root, Class<?> soundType) throws IllegalAccessException {
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Object[]> queue = new ArrayDeque<>();
        queue.add(new Object[]{root, 0});
        visited.add(root);
        while (!queue.isEmpty() && visited.size() <= MAX_VISITED) {
            Object[] next = queue.poll();
            Object value = next[0];
            int depth = (Integer) next[1];
            if (soundType.isInstance(value)) return value;
            if (depth == MAX_DEPTH) continue;
            for (Class<?> type = value.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                    field.setAccessible(true);
                    Object child = field.get(value);
                    if (child == null || !visited.add(child) || !enterable(child, soundType)) continue;
                    queue.add(new Object[]{child, depth + 1});
                }
            }
        }
        return null;
    }

    private static boolean enterable(Object value, Class<?> soundType) {
        if (soundType.isInstance(value)) return true;
        if (value instanceof View || value instanceof Context) return false;
        String name = value.getClass().getName();
        return !(name.startsWith("java.") || name.startsWith("android.") || name.startsWith("androidx.")
                || name.startsWith("kotlin.") || name.startsWith("com.instagram.common.session."));
    }
}
