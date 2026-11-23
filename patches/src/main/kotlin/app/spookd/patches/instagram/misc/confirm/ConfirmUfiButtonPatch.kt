/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.confirm

import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.patch.patch

// The feed repost button (reposts_ufi_icon) and the story heart (toolbar_like_button) are both this class.
private const val UFI_BUTTON = "Lcom/instagram/ui/widget/bouncyufibutton/IgBouncyUfiButtonImageView;"

@Suppress("unused")
val confirmUfiButtonPatch =
    patch(
        name = "Confirm feed reposts and story likes",
        description = "Adds an option to ask before reposting a post from the feed or liking a story with its heart.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch)

        execute {
            val added = addConfirmedPerformClick(
                UFI_BUTTON,
                """
                invoke-static {p0}, $ACTION_CONFIRM->allowUfiClick(Landroid/view/View;)Z
                move-result v0
                """,
            )
            if (!added) return@execute
            enableSettings("confirmFeedRepost")
            enableSettings("confirmStoryLike")
        }
    }
