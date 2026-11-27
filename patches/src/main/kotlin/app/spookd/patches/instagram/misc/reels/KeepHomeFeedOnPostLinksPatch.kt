/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.reels

import app.spookd.patches.instagram.misc.hookFlags.hookFlagsPatch
import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.addFlags
import app.morphe.patcher.patch.patch

@Suppress("unused")
val keepHomeFeedOnPostLinksPatch =
    patch(
        name = "Keep Home feed on post links",
        description = "Opening a post or Reel link inside the app, such as from the gallery or deleted messages, no longer refreshes the Home feed back to the top.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch, hookFlagsPatch)

        execute {
            addFlags("keepHomeFeedOnPostLinksFlags")
        }
    }
