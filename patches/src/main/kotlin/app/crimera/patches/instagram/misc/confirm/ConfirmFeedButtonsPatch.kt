/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.confirm

import app.crimera.patches.instagram.misc.settings.settingsPatch
import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.crimera.patches.instagram.utils.enableSettings
import app.morphe.patcher.patch.patch

// The feed's like and repost buttons; the extension tells them apart by their view ids.
private const val UFI_BUTTON = "Lcom/instagram/ui/widget/bouncyufibutton/IgBouncyUfiButtonImageView;"

@Suppress("unused")
val confirmFeedButtonsPatch =
    patch(
        name = "Confirm feed likes and reposts",
        description = "Adds options to ask before liking or reposting a post with its buttons in the feed.",
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
            if (added) enableSettings("confirmFeedButtons")
        }
    }
