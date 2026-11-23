/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.externalDownloader

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
val externalDownloaderPatch =
    patch(
        name = "External downloader",
        description = "Adds support to share post links directly to external downloader",
        default = true,
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch, mediaDataEntity, hookOverflowMenuButton, debugOverflowMenuButtonPatch, hookReelOverflowMenuButton)
        execute {

            addOverflowMenuButtonAttributes("SPOOKD_EXTERNAL_DOWNLOADER", "externalDownloaderOverflowButton")

            enableSettings("downloadWithExternalDownloader")
        }
    }
