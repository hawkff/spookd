/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.story

import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.Constants.DOWNLOAD_DESCRIPTOR
import app.spookd.patches.instagram.utils.Constants.PATCHES_DESCRIPTOR
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.literal
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.getReference
import app.morphe.util.insertLiteralOverride
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val STORY_STICKER_VIDEOS_DESCRIPTOR = "$PATCHES_DESCRIPTOR/story/sticker/StoryStickerVideos;"

// MobileConfig bool 0x810fa500015446, read only when the grid is the story layout's picker.
private const val LAYOUT_PICKER_VIDEOS_FLAG = 0x810fa500015446L

/** The photo sticker's gallery grid names itself after the editor it is in. */
internal object GalleryStickerGridModuleFingerprint : Fingerprint(
    name = "getModuleName",
    returnType = "Ljava/lang/String;",
    parameters = emptyList(),
    strings = listOf("feed-sticker-gallery", "clips-sticker-gallery", "story-sticker-gallery"),
)

/**
 * The grid's refresh. It offers videos when its host is not editing a reel and either takes videos
 * only, or is the story layout's picker in a story with the flag on; otherwise photos only.
 */
internal fun galleryStickerGridRefreshFingerprint(gridType: String) = Fingerprint(
    definingClass = gridType,
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(literal(LAYOUT_PICKER_VIDEOS_FLAG)),
)

/** The only match, else null, so a changed app skips the patch instead of failing the run. */
internal fun Fingerprint.uniqueMatch() = matchAllOrNull()?.singleOrNull()

internal fun galleryStickerGridType(): String? = GalleryStickerGridModuleFingerprint.uniqueMatch()?.originalClassDef?.type

internal fun List<Instruction>.indexOfLayoutPickerFlag() =
    indexOfFirst { (it as? WideLiteralInstruction)?.wideLiteral == LAYOUT_PICKER_VIDEOS_FLAG }

@Suppress("unused")
val storyStickerVideosPatch =
    patch(
        name = "Videos in story photo sticker",
        description = "Adds an option to list videos in the story photo sticker's gallery, which Instagram then trims and adds as a video sticker.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch)

        execute {
            val grid = galleryStickerGridType() ?: return@execute
            val refresh = galleryStickerGridRefreshFingerprint(grid).uniqueMatch()?.method ?: return@execute
            val code = refresh.instructions.toList()
            val flag = code.indexOfLayoutPickerFlag()
            if (flag < 0) return@execute
            val checks = code.subList(0, flag).withIndex().filter { (_, instruction) ->
                instruction.opcode == Opcode.INVOKE_INTERFACE &&
                    instruction.getReference<MethodReference>()?.let {
                        it.returnType == "Z" && it.parameterTypes.isEmpty()
                    } == true
            }.map { it.index }
            // The last two checks before the flag: is the layout picker, then is a story.
            val layoutPicker = checks.getOrNull(checks.size - 2) ?: return@execute
            val result = code[layoutPicker + 1]
            if (result.opcode != Opcode.MOVE_RESULT || checks.last() != layoutPicker + 3 ||
                code[layoutPicker + 2].opcode != Opcode.IF_EQZ
            ) {
                return@execute
            }
            val register = (result as OneRegisterInstruction).registerA

            // The flag's read comes after the layout check, so the override there leaves its index as is.
            refresh.insertLiteralOverride(LAYOUT_PICKER_VIDEOS_FLAG, "$STORY_STICKER_VIDEOS_DESCRIPTOR->videos(Z)Z")
            refresh.addInstructions(
                layoutPicker + 2,
                """
                invoke-static/range { v$register .. v$register }, $STORY_STICKER_VIDEOS_DESCRIPTOR->videos(Z)Z
                move-result v$register
                """.trimIndent(),
            )

            enableSettings("storyStickerVideos")
        }
    }

private const val STICKER_GALLERY_PICKER_DESCRIPTOR = "$DOWNLOAD_DESCRIPTOR/gallery/picker/StickerGalleryPicker;"
private const val MEDIUM_CLASS = "Lcom/instagram/common/gallery/Medium;"

private fun stickerGalleryPickerStub(name: String) =
    Fingerprint(definingClass = STICKER_GALLERY_PICKER_DESCRIPTOR, name = name)

@Suppress("unused")
val storyStickerGalleryPatch =
    patch(
        name = "Private gallery in story photo sticker",
        description = "Adds an option for a From private gallery entry in the story photo sticker's gallery.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch)

        execute {
            val gridClass = GalleryStickerGridModuleFingerprint.uniqueMatch()?.originalClassDef ?: return@execute
            val grid = gridClass.type
            val refresh = galleryStickerGridRefreshFingerprint(grid).uniqueMatch()?.method ?: return@execute
            // The grid's handler for a tapped cell.
            val pick = Fingerprint(definingClass = grid, returnType = "V", parameters = listOf(MEDIUM_CLASS))
                .uniqueMatch()?.method ?: return@execute
            val code = refresh.instructions.toList()
            val flag = code.indexOfLayoutPickerFlag()
            if (flag < 0) return@execute
            // The refresh stores whether picks may be videos right after it reads the flag.
            val videosField = code.drop(flag).firstNotNullOfOrNull { instruction ->
                if (instruction.opcode != Opcode.IPUT_BOOLEAN) return@firstNotNullOfOrNull null
                instruction.getReference<FieldReference>()?.takeIf { it.definingClass == grid }
            } ?: return@execute
            val container = gridClass.fields.singleOrNull { it.type == "Landroid/view/View;" } ?: return@execute

            stickerGalleryPickerStub("containerNative").method.addInstructions(
                0,
                """
                check-cast p0, $grid
                iget-object p0, p0, $grid->${container.name}:Landroid/view/View;
                return-object p0
                """.trimIndent(),
            )
            stickerGalleryPickerStub("moduleNameNative").method.addInstructions(
                0,
                """
                check-cast p0, $grid
                invoke-virtual { p0 }, $grid->getModuleName()Ljava/lang/String;
                move-result-object p0
                return-object p0
                """.trimIndent(),
            )
            stickerGalleryPickerStub("allowsVideo").method.addInstructions(
                0,
                """
                check-cast p0, $grid
                iget-boolean p0, p0, $videosField
                return p0
                """.trimIndent(),
            )
            stickerGalleryPickerStub("pickNative").method.addInstructions(
                0,
                """
                check-cast p0, $grid
                check-cast p1, $MEDIUM_CLASS
                invoke-virtual { p0, p1 }, $grid->${pick.name}($MEDIUM_CLASS)V
                return-void
                """.trimIndent(),
            )

            refresh.addInstructions(
                0,
                "invoke-static/range { p0 .. p0 }, $STICKER_GALLERY_PICKER_DESCRIPTOR->onGridShown(Ljava/lang/Object;)V",
            )

            enableSettings("storyStickerGallery")
        }
    }
