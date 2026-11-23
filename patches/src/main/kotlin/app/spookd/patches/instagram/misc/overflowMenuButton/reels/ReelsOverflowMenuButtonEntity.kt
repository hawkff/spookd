/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.overflowMenuButton.reels

import app.spookd.patches.instagram.misc.overflowMenuButton.posts.AddReelButtonExtensionFingerprint
import app.spookd.patches.instagram.misc.overflowMenuButton.posts.FeedReplaceAudioDialogHelperFingerprint
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.crimera.utils.changeFirstString
import app.morphe.patcher.patch.patch

@Suppress("unused")
val reelsOverflowMenuButtonEntity =
    patch(
        description = "Entity class for reels overflow menu button",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        execute {
            AddReelButtonExtensionFingerprint.changeFirstString(
                FeedReplaceAudioDialogHelperFingerprint.instructionMatches.last().methodReference.name,
            )
        }
    }
