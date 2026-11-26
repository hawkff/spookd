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
import app.morphe.patcher.literal
import app.morphe.patcher.patch.patch

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

@Suppress("unused")
val galleryPickerPatch =
    patch(
        name = "Private gallery in pickers",
        description = "Adds a From private gallery entry to the photo and video picker in chats and to the comment photo button.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch)

        execute {
            MediaPickerSheetViewCreatedFingerprint.method.addInstruction(
                0,
                "invoke-static/range { p0 .. p1 }, $GALLERY_PICKER_DESCRIPTOR->" +
                    "onPickerViewCreated(Landroidx/fragment/app/Fragment;Landroid/view/View;)V",
            )

            enableSettings("galleryPicker")
        }
    }
