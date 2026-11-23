/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.userProfile

import app.spookd.patches.instagram.entity.dialogbox.instagramDialogBoxEntity
import app.spookd.patches.instagram.entity.userdata.userDataEntity
import app.spookd.patches.instagram.misc.actionBar.userProfileActionBarButton.userProfileActionBarButtonPatch
import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.patch.patch

@Suppress("unused")
val profileMoreOptionsPatch =
    patch(
        name = "More options on profile",
        description = "Adds a new button to handle user related data like copy handle, download profile picture etc",
    ) {
        dependsOn(settingsPatch, userProfileButtonPatch, userProfileActionBarButtonPatch, userDataEntity, instagramDialogBoxEntity)

        compatibleWith(COMPATIBILITY_INSTAGRAM)

        execute {
            enableSettings("moreOptionsOnProfile")
        }
    }
