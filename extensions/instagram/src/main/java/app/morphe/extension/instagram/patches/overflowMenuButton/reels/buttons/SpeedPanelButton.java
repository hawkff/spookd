/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.overflowMenuButton.reels.buttons;

import android.content.Context;
import android.view.View;

import app.morphe.extension.instagram.patches.PlaybackSpeed;

public class SpeedPanelButton extends ReelButton {
    public SpeedPanelButton(Context context, Object mediaObject) {
        super(context, mediaObject, 0);
    }

    @Override
    public void onClick(View view) {
        PlaybackSpeed.openPanel(this.context, this.mediaObject);
    }
}
