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
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.addInstructionsAtControlFlowLabel
import app.morphe.patcher.util.findFreeRegister
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction

// Builds the profile tab names; the tab controller already knows "profile_saved".
internal object ProfileTabNamesFingerprint : Fingerprint(
    returnType = "Ljava/util/List;",
    parameters = listOf("Lcom/instagram/user/model/User;"),
    strings = listOf("profile_media_grid", "profile_reposts", "profile_tagged_media_photos_of_you"),
    filters = listOf(
        // Own-profile flag, the first boolean the builder reads from itself.
        fieldAccess(opcode = Opcode.IGET_BOOLEAN, definingClass = "this", type = "Z"),
        // Final call that wraps the finished list.
        methodCall(opcode = Opcode.INVOKE_STATIC, parameters = listOf("Ljava/util/List;")),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        opcode(Opcode.RETURN_OBJECT, location = MatchAfterImmediately()),
    ),
)

@Suppress("unused")
val profileSavedTabPatch =
    patch(
        name = "Profile Saved tab",
        description = "Adds a Saved tab to your own profile.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch)

        execute {
            ProfileTabNamesFingerprint.let {
                val ownProfileField = it.instructionMatches.first().fieldReference
                val wrapCall = it.instructionMatches[1]
                val tabsRegister = wrapCall.getInstruction<FiveRegisterInstruction>().registerC
                val ownProfileRegister = it.method.findFreeRegister(wrapCall.index, tabsRegister)
                // At the label, so every branch that ends the list passes through.
                it.method.addInstructionsAtControlFlowLabel(
                    wrapCall.index,
                    """
                    iget-boolean v$ownProfileRegister, p0, $ownProfileField
                    invoke-static {v$tabsRegister, v$ownProfileRegister}, $PATCHES_DESCRIPTOR/userprofile/ProfileTweaks;->addProfileSavedTab(Ljava/util/List;Z)V
                    """.trimIndent(),
                )
            }

            enableSettings("profileSavedTab")
        }
    }
