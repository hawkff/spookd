/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.links.misc

import app.spookd.patches.instagram.entity.decoder.CURRENT_MEDIA_FIELD
import app.spookd.patches.instagram.entity.decoder.MEDIA_ADD_INFO_CLASS_NAME
import app.spookd.patches.instagram.entity.decoder.MEDIA_CLASS_NAME
import app.spookd.patches.instagram.entity.decoder.decoderEntity
import app.spookd.patches.instagram.entity.mediadata.mediaDataEntity
import app.spookd.patches.instagram.misc.download.requireOne
import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.Constants.PATCHES_DESCRIPTOR
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstructions
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.patch.classDefByOrNull
import app.morphe.patcher.patch.patch
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resourceLiteral
import app.morphe.patcher.util.p0Register
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

private const val EXTENSION_CLASS = "$PATCHES_DESCRIPTOR/HoldSendLink;"
private const val VIEW_DESCRIPTOR = "Landroid/view/View;"

// The button row under a feed post; its constructor looks up the Send and save buttons.
private object FeedButtonRowConstructorFingerprint : Fingerprint(
    name = "<init>",
    returnType = "V",
    parameters = listOf(VIEW_DESCRIPTOR),
    filters = listOf(
        resourceLiteral(ResourceType.ID, "row_feed_button_share"),
        resourceLiteral(ResourceType.ID, "row_feed_button_save"),
    ),
)

// Sets the tap, long-press and touch listeners of the Send button and of its share count.
private fun shareButtonListenersFingerprint(stateType: String) = Fingerprint(
    returnType = "V",
    parameters = listOf(VIEW_DESCRIPTOR, "L", stateType),
    filters = listOf(
        methodCall(smali = "Landroid/view/View;->setOnLongClickListener(Landroid/view/View\$OnLongClickListener;)V"),
        methodCall(smali = "Landroid/view/View;->setOnTouchListener(Landroid/view/View\$OnTouchListener;)V"),
    ),
)

private fun replaceExtensionBody(name: String, smali: String) {
    Fingerprint(definingClass = EXTENSION_CLASS, name = name).method.apply {
        removeInstructions(0, instructions.size)
        addInstructions(0, smali.trimIndent())
    }
}

@Suppress("unused")
val holdSendCopyLinkPatch =
    patch(
        name = "Hold Send to copy link",
        description = "Adds an option to copy a feed post's link by long-pressing its Send button.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch, mediaDataEntity, decoderEntity)

        execute {
            val row = FeedButtonRowConstructorFingerprint.originalClassDef
            val stateType = requireOne(
                "post state type of ${row.type}",
                row.fields.map { it.type }.filter { type ->
                    classDefByOrNull(type)?.fields?.count { it.type == MEDIA_CLASS_NAME } == 1
                }.distinct(),
            )
            val state = classDefBy(stateType)
            val mediaField = requireOne("Media field of $stateType", state.fields.filter { it.type == MEDIA_CLASS_NAME })
            val viewStateField = requireOne(
                "view state field of $stateType",
                state.fields.filter { it.type == MEDIA_ADD_INFO_CLASS_NAME },
            )

            replaceExtensionBody(
                "mediaOf",
                """
                check-cast p0, $stateType
                iget-object p0, p0, $mediaField
                return-object p0
                """,
            )
            replaceExtensionBody(
                "viewStateOf",
                """
                check-cast p0, $stateType
                iget-object p0, p0, $viewStateField
                return-object p0
                """,
            )
            replaceExtensionBody(
                "currentIndexOf",
                """
                check-cast p0, $MEDIA_ADD_INFO_CLASS_NAME
                iget p0, p0, $CURRENT_MEDIA_FIELD
                return p0
                """,
            )

            shareButtonListenersFingerprint(stateType).method.apply {
                val returnIndex = instructions.size - 1
                if (AccessFlags.STATIC.isSet(accessFlags) || p0Register + 3 > 15 ||
                    getInstruction(returnIndex).opcode != Opcode.RETURN_VOID
                ) {
                    throw PatchException("Unexpected Send button listener method $this")
                }
                // After Instagram's own listeners, so the extension's long-press replaces Instagram's.
                addInstructions(
                    returnIndex,
                    "invoke-static {p1, p3}, $EXTENSION_CLASS->bindShareButton(${VIEW_DESCRIPTOR}Ljava/lang/Object;)V",
                )
            }

            enableSettings("holdSendCopyLink")
        }
    }
