/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.comment.saveMediaComment

import app.spookd.patches.instagram.entity.commentDataEntity.commentDataEntity
import app.spookd.patches.instagram.entity.decoder.decoderEntity
import app.spookd.patches.instagram.entity.mediadata.mediaDataEntity
import app.spookd.patches.instagram.misc.comment.addButtonAttribute
import app.spookd.patches.instagram.misc.comment.addButtonInterface
import app.spookd.patches.instagram.misc.comment.addCommentPatch
import app.spookd.patches.instagram.misc.comment.chatButtonConstructorFingerprint
import app.spookd.patches.instagram.misc.comment.commentButtonClickCheckPatch
import app.spookd.patches.instagram.misc.comment.debugComment.debugCommentPatch
import app.spookd.patches.instagram.misc.comment.nativeChatButtonResources
import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.patch.patch

@Suppress("unused")
val saveMediaCommentPatch =
    patch(
        name = "Save media comment",
        description = "Adds a button to save media comments on posts and reels.",
        default = true,
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(
            settingsPatch,
            addCommentPatch,
            commentButtonClickCheckPatch,
            commentDataEntity,
            mediaDataEntity,
            decoderEntity,
            debugCommentPatch,
        )
        execute {

            val action = ChatButtonActionsFingerprint.instructionMatches.last().fieldReference
            val (stringLateral, drawableLateral) = nativeChatButtonResources(chatButtonConstructorFingerprint(action))

            addButtonAttribute(
                stringLateral,
                drawableLateral,
                InitSaveMediaButtonExtensionFingerprint,
                InitSaveMediaButtonInitExtensionFingerprint,
            )

            addButtonInterface(InitSaveMediaButtonExtensionFingerprint)

            enableSettings("saveMediaCommentButton")
        }
    }
