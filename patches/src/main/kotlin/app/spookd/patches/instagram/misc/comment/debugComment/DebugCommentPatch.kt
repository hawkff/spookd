/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.comment.debugComment

import app.spookd.patches.instagram.entity.commentDataEntity.commentDataEntity
import app.spookd.patches.instagram.entity.decoder.decoderEntity
import app.spookd.patches.instagram.misc.comment.addButtonAttribute
import app.spookd.patches.instagram.misc.comment.addButtonInterface
import app.spookd.patches.instagram.misc.comment.addCommentPatch
import app.spookd.patches.instagram.misc.comment.chatButtonConstructorFingerprint
import app.spookd.patches.instagram.misc.comment.commentButtonClickCheckPatch
import app.spookd.patches.instagram.misc.comment.nativeChatButtonResources
import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.morphe.patcher.patch.patch
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resource.resourceId

@Suppress("unused")
val debugCommentPatch =
    patch(
        description = "Adds a debug button to comments on posts and reels.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)

        dependsOn(settingsPatch, addCommentPatch, commentButtonClickCheckPatch, commentDataEntity, decoderEntity)
        execute {

            // Temp use "Source" as string.
            // TODO Need to be changed to "Debug"
            val stringLateral = nativeChatButtonResources(
                chatButtonConstructorFingerprint(ViewSourcesChatButtonToStringFingerprint.originalClassDef.type),
            ).first
            val drawableLateral = resourceId(ResourceType.DRAWABLE, "instagram_app_instagram_pano_outline_24")

            addButtonAttribute(
                stringLateral,
                drawableLateral,
                InitDebugButtonExtensionFingerprint,
                InitDebugButtonInitExtensionFingerprint,
            )

            addButtonInterface(InitDebugButtonExtensionFingerprint)
        }
    }
