/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.moreOptionsOnPost

import app.spookd.patches.instagram.entity.dialogbox.instagramDialogBoxEntity
import app.spookd.patches.instagram.entity.mediadata.mediaDataEntity
import app.spookd.patches.instagram.misc.overflowMenuButton.posts.addOverflowMenuButtonAttributes
import app.spookd.patches.instagram.misc.overflowMenuButton.posts.debugOverflowButton.debugOverflowMenuButtonPatch
import app.spookd.patches.instagram.misc.overflowMenuButton.posts.hookOverflowMenuButton
import app.spookd.patches.instagram.misc.overflowMenuButton.reels.hookReelOverflowMenuButton
import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.patch.patch

@Suppress("unused")
val moreOptionsOnPostPatch =
    patch(
        name = "More options on post",
        description = "Adds an overflow menu button to get more options on post/reels, like copy description, copy username etc",
        default = true,
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(
            settingsPatch,
            mediaDataEntity,
            instagramDialogBoxEntity,
            hookOverflowMenuButton,
            debugOverflowMenuButtonPatch,
            hookReelOverflowMenuButton,
        )
        execute {

            addOverflowMenuButtonAttributes("SPOOKD_MORE_POST_OPTION", "morePostOptionOverflowButton")

            enableSettings("moreOptionsOnPost")
        }
    }
