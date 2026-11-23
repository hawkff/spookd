/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.directMessage.downloadVoiceMessagePatch

import app.spookd.patches.instagram.entity.messageInfoEntity.messageInfoEntity
import app.spookd.patches.instagram.misc.directMessage.saveAllMessages.saveAllMessagesPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.patch.patch

@Suppress("unused")
val downloadVoiceMessagePatch =
    patch(
        name = "Download voice message",
        description = "Enables ability to download voice messages",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(messageInfoEntity, saveAllMessagesPatch)
        execute {
            enableSettings("downloadVoiceMessage")
        }
    }
