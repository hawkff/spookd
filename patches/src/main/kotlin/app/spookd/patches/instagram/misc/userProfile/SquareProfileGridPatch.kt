/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.userProfile

import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.Constants.PATCHES_DESCRIPTOR
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.string
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.writeRegister
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

internal object MediaGridRowBindViewFingerprint : Fingerprint(
    name = "bindView",
    returnType = "V",
    parameters = listOf("I", "Landroid/view/View;", "Ljava/lang/Object;", "Ljava/lang/Object;"),
    filters = listOf(
        string("null cannot be cast to non-null type instagram.features.feed.ui.rows.mediagrid.MediaGridRowViewBinder.Holder"),
        // Cell aspect: 0.75 for 3:4, 1.0 for square.
        fieldAccess(
            opcode = Opcode.IPUT,
            definingClass = "Lcom/instagram/common/ui/widget/imageview/ConstrainedImageView;",
            type = "F",
        ),
    ),
)

internal object ProfileGridRowBindFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/view/View;", "L", "L"),
    filters = listOf(
        string("null cannot be cast to non-null type instagram.features.feed.ui.rows.profilegrid.ProfileGridRowViewBinder.Holder"),
        // 3:4 switch, the first boolean the binder reads from itself.
        fieldAccess(opcode = Opcode.IGET_BOOLEAN, definingClass = "this", type = "Z"),
    ),
)

private fun MutableMethod.squareAfterRead(readIndex: Int) {
    val read = getInstruction<TwoRegisterInstruction>(readIndex)
    if (read.opcode != Opcode.IGET_BOOLEAN) throw PatchException("Expected the grid 3:4 switch read")
    addInstructions(
        readIndex + 1,
        """
        invoke-static {v${read.registerA}}, $PATCHES_DESCRIPTOR/userprofile/ProfileTweaks;->squareProfileGrid(Z)Z
        move-result v${read.registerA}
        """.trimIndent(),
    )
}

@Suppress("unused")
val squareProfileGridPatch =
    patch(
        name = "Square profile grid",
        description = "Shows posts on profile grids as square thumbnails instead of tall 3:4 ones.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch)

        execute {
            MediaGridRowBindViewFingerprint.let {
                val method = it.method
                val aspectWrite = it.instructionMatches.last()
                val aspectRegister = aspectWrite.getInstruction<TwoRegisterInstruction>().registerA
                // The aspect constant is picked by a branch on the switch right before it.
                val constantIndex =
                    method.indexOfFirstInstructionReversedOrThrow(aspectWrite.index - 1) { writeRegister == aspectRegister }
                val branch = method.getInstruction(constantIndex - 1)
                if (branch.opcode != Opcode.IF_EQZ && branch.opcode != Opcode.IF_NEZ) {
                    throw PatchException("Unexpected grid aspect branch: ${branch.opcode}")
                }
                val switchRegister = (branch as OneRegisterInstruction).registerA
                method.squareAfterRead(
                    method.indexOfFirstInstructionReversedOrThrow(constantIndex - 2) { writeRegister == switchRegister },
                )
            }

            ProfileGridRowBindFingerprint.let {
                it.method.squareAfterRead(it.instructionMatches.last().index)
            }

            enableSettings("squareProfileGrid")
        }
    }
