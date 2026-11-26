/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.distractionFree

import app.spookd.patches.instagram.misc.hookFlags.hookFlagsPatch
import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.addFlags
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.patch.patch

@Suppress("unused")
val hideChatBlendButtonPatch =
    patch(
        name = "Hide Blend button in chats",
        description = "Adds an option to turn off Blend and its button in chat headers.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch, hookFlagsPatch)

        execute {
            addFlags("hideChatBlendButtonFlags")
            enableSettings("hideChatBlendButton")
        }
    }
