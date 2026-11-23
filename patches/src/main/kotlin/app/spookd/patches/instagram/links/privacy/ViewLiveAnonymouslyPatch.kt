/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.links.privacy

import app.spookd.patches.instagram.links.interceptUriPatch
import app.spookd.patches.instagram.misc.actionBar.chatActionBarButton.chatActionBarButtonPatch
import app.spookd.patches.instagram.misc.actionBar.inboxActionBarButton.inboxActionBarButtonPatch
import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.patch.patch

@Suppress("unused")
val viewLiveAnonymouslyPatch =
    patch(
        name = "View live anonymously",
        default = true,
    ) {
        dependsOn(settingsPatch, interceptUriPatch, chatActionBarButtonPatch, inboxActionBarButtonPatch)
        compatibleWith(COMPATIBILITY_INSTAGRAM)

        execute {
            enableSettings("viewLiveAnonymously")
        }
    }
