/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.download

import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.Constants.DOWNLOAD_DESCRIPTOR
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstructions
import app.morphe.patcher.literal
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val GALLERY_PICKER_DESCRIPTOR = "$DOWNLOAD_DESCRIPTOR/gallery/picker/GalleryPicker;"

/**
 * The result handler of Instagram's media picker sheet, which the DM composer and the comment photo
 * button open. Request 1002 (0x3ea) carries a media URI picked in another app, which the sheet
 * imports and hands to its listener; the extension starts its gallery with that request code.
 */
internal object MediaPickerSheetResultFingerprint : Fingerprint(
    name = "onActivityResult",
    returnType = "V",
    parameters = listOf("I", "I", "Landroid/content/Intent;"),
    filters = listOf(
        literal(0x68),
        literal(0x3e9),
        literal(0x3ea),
        literal(0x1341),
    ),
)

internal object MediaPickerSheetViewCreatedFingerprint : Fingerprint(
    classFingerprint = MediaPickerSheetResultFingerprint,
    name = "onViewCreated",
    returnType = "V",
    parameters = listOf("Landroid/view/View;", "Landroid/os/Bundle;"),
)

/**
 * Stores the sheet's options. Its 16th parameter says whether the sheet offers videos: the DM
 * composer passes true, the comment photo button false, and the folder list gets its negation as
 * "photo only".
 */
internal object MediaPickerSheetConfigureFingerprint : Fingerprint(
    classFingerprint = MediaPickerSheetResultFingerprint,
    returnType = "V",
    parameters = listOf(
        "Lcom/instagram/direct/fragment/permanentmedia/DirectAggregatedMediaViewerController;",
        "L", "L", "L",
        "Lcom/instagram/model/direct/camera/DirectCameraViewModel;",
        "L", "L",
        "Ljava/lang/Boolean;", "Ljava/lang/String;", "Ljava/util/List;",
        "I", "I", "Z", "Z", "Z", "Z", "Z", "Z", "Z",
    ),
)

/** The boolean field the configure method stores its 16th parameter in, directly or after a move. */
private fun videosAllowedField(): FieldReference {
    val method = MediaPickerSheetConfigureFingerprint.method
    // Every parameter is one register wide, and p0 is this.
    val parameter = method.implementation!!.registerCount - method.parameters.size - 1 + 16
    val list = method.instructions.toList()
    list.forEachIndexed { index, instruction ->
        if (instruction.opcode != Opcode.IPUT_BOOLEAN) return@forEachIndexed
        val source = (instruction as TwoRegisterInstruction).registerA
        val previous = list.getOrNull(index - 1)
        val moved = previous != null && previous.opcode == Opcode.MOVE_FROM16 &&
            (previous as TwoRegisterInstruction).registerA == source && previous.registerB == parameter
        if (source == parameter || moved) {
            return (instruction as ReferenceInstruction).reference as FieldReference
        }
    }
    throw PatchException("Media picker sheet: no field for its videos option")
}

@Suppress("unused")
val galleryPickerPatch =
    patch(
        name = "Private gallery in pickers",
        description = "Adds a From private gallery entry to the photo and video picker in chats and to the comment photo button.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch)

        execute {
            val videosAllowed = videosAllowedField()
            Fingerprint(definingClass = GALLERY_PICKER_DESCRIPTOR, name = "allowsVideo").method.apply {
                removeInstructions(0, instructions.size)
                addInstructions(
                    0,
                    """
                    check-cast p0, ${videosAllowed.definingClass}
                    iget-boolean p0, p0, $videosAllowed
                    return p0
                    """.trimIndent(),
                )
            }

            MediaPickerSheetViewCreatedFingerprint.method.addInstruction(
                0,
                "invoke-static/range { p0 .. p1 }, $GALLERY_PICKER_DESCRIPTOR->" +
                    "onPickerViewCreated(Landroidx/fragment/app/Fragment;Landroid/view/View;)V",
            )

            enableSettings("galleryPicker")
        }
    }
