/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.metaAi

import app.spookd.patches.instagram.misc.hookFlags.hookFlagsPatch
import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.addFlags
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.patch.patch

@Suppress("unused")
val hideMetaAiPatch =
    patch(
        name = "Hide Meta AI",
        description = "Hides Meta AI prompts in search, the Meta AI entry in messages and the Reels menu, and AI suggestions.",
        default = true,
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch, hookFlagsPatch)

        execute {
            addFlags("hideMetaAiFlags")
            enableSettings("hideMetaAi")
        }
    }
