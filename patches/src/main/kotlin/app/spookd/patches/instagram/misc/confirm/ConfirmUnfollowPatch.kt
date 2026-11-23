/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.confirm

import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.Constants.USER_SESSION_CLASS
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patcher.string
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val STRING = "Ljava/lang/String;"
private const val USER_EXT = "Lcom/instagram/user/model/UserExtKt;"

// Static click of the follow button's controller: it follows, unfollows directly, or shows
// Instagram's own unfollow dialog for an account that is not public.
private object FollowControllerClickFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L", USER_SESSION_CLASS, "L", "L"),
    filters = listOf(
        string("tags_list_feed_timeline"),
        string("tags_list_clips_viewer_clips_tab"),
    ),
)

@Suppress("unused")
val confirmUnfollowPatch =
    patch(
        name = "Confirm unfollows",
        description = "Adds an option to ask before a Following or Requested button unfollows a public account or withdraws a follow request.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch)

        execute {
            val method = FollowControllerClickFingerprint.matchOrNull()?.method ?: return@execute
            // The replay re-invokes the method on the controller's class.
            if (!AccessFlags.STATIC.isSet(method.accessFlags) || method.parameterTypes[2].toString() != method.definingClass) {
                return@execute
            }
            val instructions = method.implementation!!.instructions
            val calls = instructions.map { (it as? ReferenceInstruction)?.reference as? MethodReference }
            val privacyTypes = calls.filter { it?.definingClass == USER_EXT }.map { it!!.returnType }.toSet()
            // The status lookup takes (status, privacy, user id) and returns the status the button shows.
            val lookup = calls.indexOfFirst { call ->
                call != null && call.parameterTypes.size == 3 && call.returnType == call.parameterTypes[0].toString() &&
                    call.parameterTypes[1].toString() in privacyTypes && call.parameterTypes[2].toString() == STRING
            }
            if (lookup < 0 || instructions[lookup].opcode != Opcode.INVOKE_VIRTUAL) return@execute
            val privacy = (instructions[lookup] as FiveRegisterInstruction).registerE
            val result = instructions.getOrNull(lookup + 1)
            if (result?.opcode != Opcode.MOVE_RESULT_OBJECT) return@execute
            val status = (result as OneRegisterInstruction).registerA
            // The two constants written next are free to use before them.
            val scratch = (lookup + 2..lookup + 3).mapNotNull { index ->
                instructions.getOrNull(index)?.takeIf { it.opcode == Opcode.CONST_4 } as? OneRegisterInstruction
            }.map { it.registerA }.distinct()
            if (scratch.size != 2 || status in scratch || privacy in scratch || status > 15 || privacy > 15) return@execute
            val (array, name) = scratch

            method.addInstructionsWithLabels(
                lookup + 2,
                """
                filled-new-array/range {p0 .. p3}, [Ljava/lang/Object;
                move-result-object v$array
                const-string v$name, "${method.name}"
                invoke-static {v$array, v$status, v$privacy, v$name}, $ACTION_CONFIRM->allowUnfollow([Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;$STRING)Z
                move-result v$array
                if-nez v$array, :confirm_allowed
                return-void
                """.trimIndent(),
                ExternalLabel("confirm_allowed", method.getInstruction(lookup + 2)),
            )
            enableSettings("confirmUnfollow")
        }
    }
