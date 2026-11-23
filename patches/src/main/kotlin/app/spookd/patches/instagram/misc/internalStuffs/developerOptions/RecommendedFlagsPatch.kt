/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.internalStuffs.developerOptions

import app.spookd.patches.instagram.misc.hookFlags.hookFlagsPatch
import app.spookd.patches.instagram.misc.internalStuffs.checkMappingsPatch
import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.patch.patch

@Suppress("unused")
val recommendedFlagsPatch =
    patch(
        name = "Recommended flags",
        description = "Developer flags suggested by the community",
        default = true,
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch, hookFlagsPatch, checkMappingsPatch)
        execute {
            enableSettings("recommendedFlags")
        }
    }
