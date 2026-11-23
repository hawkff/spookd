/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import app.morphe.extension.instagram.settings.preference.widgets.InstagramPreferenceStyle;
import app.morphe.extension.shared.Logger;

/** A bottom panel with speed presets and a seek bar for one playing video. Main thread only. */
final class PlaybackSpeedPanel {
    private static final long REFRESH_MS = 500;

    private final Context context;
    private final Activity host;
    private final Object player;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final int textColor = InstagramPreferenceStyle.primaryTextColor();
    private final int accentColor = InstagramPreferenceStyle.selectionColor();
    private final TextView position;
    private final TextView duration;
    private final SeekBar bar;
    private final TextView[] speeds = new TextView[PlaybackSpeed.PRESETS.length];
    private boolean dragging;
    private boolean disposed;
    private Dialog dialog;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            if (!disposed && update()) handler.postDelayed(this, REFRESH_MS);
        }
    };

    private final Application.ActivityLifecycleCallbacks lifecycle = new Application.ActivityLifecycleCallbacks() {
        @Override
        public void onActivityDestroyed(Activity activity) {
            if (activity != host) return;
            dispose();
            try {
                dialog.dismiss();
            } catch (RuntimeException e) {
                Logger.printDebug(() -> "Speed panel window already gone: " + e);
            }
        }

        @Override
        public void onActivityCreated(Activity activity, Bundle state) {}

        @Override
        public void onActivityStarted(Activity activity) {}

        @Override
        public void onActivityResumed(Activity activity) {}

        @Override
        public void onActivityPaused(Activity activity) {}

        @Override
        public void onActivityStopped(Activity activity) {}

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
    };

    private PlaybackSpeedPanel(Context context, Activity host, Object player) {
        this.context = context;
        this.host = host;
        this.player = player;
        position = label();
        duration = label();
        bar = new SeekBar(context);
    }

    static void show(Context context, Object player) {
        Context themed = InstagramPreferenceStyle.dialogContext(context);
        new PlaybackSpeedPanel(themed, context instanceof Activity ? (Activity) context : null, player).open();
    }

    private void open() {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(16);
        root.setPadding(padding, padding, padding, padding);
        GradientDrawable background = new GradientDrawable();
        background.setColor(InstagramPreferenceStyle.backgroundColor());
        float corner = dp(16);
        background.setCornerRadii(new float[]{corner, corner, corner, corner, 0, 0, 0, 0});
        root.setBackground(background);

        TextView title = label();
        title.setText(str("spookd_speed_and_seek"));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        LinearLayout seekRow = row();
        bar.getProgressDrawable().setColorFilter(accentColor, PorterDuff.Mode.SRC_IN);
        if (bar.getThumb() != null) bar.getThumb().setColorFilter(accentColor, PorterDuff.Mode.SRC_IN);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                position.setText(PlaybackSpeed.formatTime(progress));
                // Keyboard and accessibility changes never reach onStopTrackingTouch.
                if (!dragging) seek(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                dragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                dragging = false;
                seek(seekBar.getProgress());
            }
        });
        seekRow.addView(position);
        seekRow.addView(bar, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        seekRow.addView(duration);
        root.addView(seekRow);

        LinearLayout skipRow = row();
        skipRow.setGravity(Gravity.CENTER);
        skipRow.addView(button(str("spookd_speed_panel_back"), () -> skip(-PlaybackSpeed.SKIP_MS)), weighted());
        skipRow.addView(button(str("spookd_speed_panel_forward"), () -> skip(PlaybackSpeed.SKIP_MS)), weighted());
        root.addView(skipRow);

        LinearLayout speedRow = row();
        for (int i = 0; i < speeds.length; i++) {
            float speed = PlaybackSpeed.PRESETS[i];
            TextView option = button(PlaybackSpeed.speedLabel(speed), () -> pickSpeed(speed));
            speeds[i] = option;
            speedRow.addView(option, weighted());
        }
        root.addView(speedRow);

        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View view) {}

            @Override
            public void onViewDetachedFromWindow(View view) {
                dispose();
            }
        });

        dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(root);
        dialog.setCanceledOnTouchOutside(true);
        dialog.setOnDismissListener(d -> dispose());
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            // The video stays visible behind the panel.
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setGravity(Gravity.BOTTOM);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        if (host != null) host.getApplication().registerActivityLifecycleCallbacks(lifecycle);
        dialog.show();
        handler.post(refresh);
    }

    // Runs once on dismissal, window detachment or host destruction; the panel never refreshes again.
    private void dispose() {
        if (disposed) return;
        disposed = true;
        handler.removeCallbacks(refresh);
        if (host != null) host.getApplication().unregisterActivityLifecycleCallbacks(lifecycle);
    }

    // False once the player is released, which reports no duration, which stops the refresh.
    private boolean update() {
        try {
            int total = PlaybackSpeed.durationMs(player);
            bar.setEnabled(total > 0);
            if (total <= 0) return false;
            duration.setText(PlaybackSpeed.formatTime(total));
            if (dragging) return true;
            int current = PlaybackSpeed.positionMs(player);
            bar.setMax(total);
            bar.setProgress(current);
            position.setText(PlaybackSpeed.formatTime(current));
            return true;
        } catch (Throwable t) {
            bar.setEnabled(false);
            Logger.printDebug(() -> "Speed panel player is gone: " + t);
            return false;
        }
    }

    private void seek(int positionMs) {
        try {
            PlaybackSpeed.seekTo(player, positionMs, false);
        } catch (Throwable t) {
            Logger.printException(() -> "Failed to seek", t);
        }
    }

    private void skip(int deltaMs) {
        try {
            int total = PlaybackSpeed.durationMs(player);
            if (total <= 0) return;
            int target = PlaybackSpeed.seekTarget(PlaybackSpeed.positionMs(player), deltaMs, total);
            seek(target);
            bar.setProgress(target);
            position.setText(PlaybackSpeed.formatTime(target));
        } catch (Throwable t) {
            Logger.printException(() -> "Failed to skip", t);
        }
    }

    private void pickSpeed(float speed) {
        try {
            PlaybackSpeed.setSpeed(player, speed);
            PlaybackSpeed.onPanelSpeedPicked(player, speed);
            for (int i = 0; i < speeds.length; i++) {
                style(speeds[i], PlaybackSpeed.PRESETS[i] == speed);
            }
        } catch (Throwable t) {
            Logger.printException(() -> "Failed to set the playback speed", t);
        }
    }

    private TextView label() {
        TextView view = new TextView(context);
        view.setTextColor(textColor);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        return view;
    }

    private TextView button(String text, Runnable action) {
        TextView view = label();
        view.setText(text);
        view.setGravity(Gravity.CENTER);
        view.setSingleLine(true);
        view.setPadding(0, dp(10), 0, dp(10));
        view.setOnClickListener(v -> action.run());
        style(view, false);
        return view;
    }

    private void style(TextView view, boolean selected) {
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(18));
        shape.setStroke(dp(1), accentColor);
        shape.setColor(selected ? accentColor : Color.TRANSPARENT);
        view.setBackground(shape);
        view.setTextColor(selected ? Color.WHITE : textColor);
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, 0);
        return row;
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(dp(3), 0, dp(3), 0);
        return params;
    }

    private int dp(float value) {
        return InstagramPreferenceStyle.dp(context, value);
    }
}
