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
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.string
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val STRING = "Ljava/lang/String;"
private const val CONFIRM_FOLLOWER_UTIL = "Lcom/instagram/user/follow/ConfirmFollowerUtil;"

// Static accept of a follow request, reached from Activity and the follow requests list.
private object FollowRequestAcceptFingerprint : Fingerprint(
    definingClass = CONFIRM_FOLLOWER_UTIL,
    returnType = "V",
    parameters = listOf("Landroidx/fragment/app/FragmentActivity;", "L", USER_SESSION_CLASS, "L", "L", "Z"),
)

// Click of a row in the follow requests list, which updates the row right after the accept call.
private object FollowRequestRowClickFingerprint : Fingerprint(
    name = "onClick",
    returnType = "V",
    parameters = listOf("Landroid/view/View;"),
    filters = listOf(
        string("follow_request_tapped"),
        string("follow_request_dismiss_tapped"),
    ),
)

// A lambda with several cases; one accepts a request and then raises the cached follower counts itself.
private object FollowRequestAcceptCallbackFingerprint : Fingerprint(
    name = "invoke",
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;"),
    filters = listOf(
        methodCall(definingClass = CONFIRM_FOLLOWER_UTIL, returnType = "V"),
        string("loadingSpinnerSubtitle"),
    ),
)

/**
 * Asks at the start of the lambda for the one switch case that runs straight into the accept call, so
 * a cancel also skips the count updates after it. False when that case cannot be told apart.
 */
private fun MutableMethod.gateAcceptCase(): Boolean {
    val instructions = implementation?.instructions ?: return false
    val accept = instructions.indexOfFirst {
        ((it as? ReferenceInstruction)?.reference as? MethodReference)?.definingClass == CONFIRM_FOLLOWER_UTIL
    }
    val switch = instructions.indexOfFirst { it.opcode == Opcode.PACKED_SWITCH }
    if (accept < 0 || switch !in 1 until accept) return false
    // The selector is the lambda's own case field, read right before the switch.
    val selector = (instructions[switch] as OneRegisterInstruction).registerA
    val read = instructions[switch - 1]
    val caseField = (read as? ReferenceInstruction)?.reference as? FieldReference ?: return false
    if (read.opcode != Opcode.IGET || (read as TwoRegisterInstruction).registerA != selector ||
        caseField.definingClass != definingClass
    ) {
        return false
    }
    val payloadIndex = (instructions[switch] as BuilderOffsetInstruction).target.location.index
    val payload = instructions[payloadIndex] as? SwitchPayload ?: return false
    val switchAddress = instructions[switch].location.codeAddress
    val cases = payload.switchElements.map { element ->
        element.key to instructions.indexOfFirst { it.location.codeAddress == switchAddress + element.offset }
    }
    val start = cases.map { it.second }.filter { it in 0..accept }.maxOrNull() ?: return false
    val acceptCase = cases.filter { it.second == start }.singleOrNull()?.first ?: return false
    if ((start until accept).any {
            instructions[it] is BuilderOffsetInstruction || instructions[it].opcode == Opcode.RETURN_OBJECT ||
                instructions[it].opcode == Opcode.THROW
        }
    ) {
        return false
    }
    // The lambda's own Unit, returned when the prompt holds the call back.
    val unitRead = instructions.indices.firstOrNull {
        instructions[it].opcode == Opcode.SGET_OBJECT && instructions.getOrNull(it + 1)?.opcode == Opcode.RETURN_OBJECT
    } ?: return false
    val unit = (instructions[unitRead] as ReferenceInstruction).reference as FieldReference
    if (implementation!!.registerCount - 2 < 5) return false

    addInstructionsWithLabels(
        0,
        """
        move-object/from16 v0, p0
        iget v1, v0, ${caseField.definingClass}->${caseField.name}:${caseField.type}
        const v2, $acceptCase
        move-object/from16 v3, p1
        const-string v4, "$name"
        invoke-static {v0, v1, v2, v3, v4}, $ACTION_CONFIRM->allowFollowRequestCallback(Ljava/lang/Object;IILjava/lang/Object;$STRING)Z
        move-result v0
        if-nez v0, :confirm_allowed
        sget-object v0, ${unit.definingClass}->${unit.name}:${unit.type}
        return-object v0
        """.trimIndent(),
        ExternalLabel("confirm_allowed", getInstruction(0)),
    )
    return true
}

@Suppress("unused")
val confirmFollowRequestPatch =
    patch(
        name = "Confirm accepting follow requests",
        description = "Adds an option to ask before a follow request is accepted in Activity or the follow requests list.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch)

        execute {
            val method = FollowRequestAcceptFingerprint.matchOrNull()?.method ?: return@execute
            if (!AccessFlags.STATIC.isSet(method.accessFlags)) return@execute
            // Asking before the list's click keeps a declined prompt from leaving the row looking accepted.
            val click = FollowRequestRowClickFingerprint.matchOrNull()?.method?.takeIf { row ->
                row.implementation!!.instructions.any {
                    ((it as? ReferenceInstruction)?.reference as? MethodReference)?.definingClass == CONFIRM_FOLLOWER_UTIL
                }
            } ?: throw PatchException("Follow request row click not found")
            // Without it a cancelled prompt would leave that callback's counts raised.
            val callback = FollowRequestAcceptCallbackFingerprint.matchOrNull()?.method
                ?: throw PatchException("Follow request accept callback not found")
            // Both callers are asked before the accept itself, so it is never held back under a caller that goes on.
            if (!callback.gateAcceptCase()) throw PatchException("Follow request accept callback has an unexpected shape")
            val clickAdded = click.addConfirmGate(
                2,
                """
                move-object/from16 v0, p1
                move-object/from16 v1, p0
                invoke-static {v0, v1}, $ACTION_CONFIRM->allowFollowRequestClick(Landroid/view/View;Ljava/lang/Object;)Z
                move-result v0
                """,
            )
            if (!clickAdded) throw PatchException("Follow request row click has too few registers")
            val added = method.addConfirmGate(
                4,
                """
                filled-new-array/range {p0 .. p4}, [Ljava/lang/Object;
                move-result-object v0
                move/from16 v1, p5
                const-class v2, $CONFIRM_FOLLOWER_UTIL
                const-string v3, "${method.name}"
                invoke-static {v0, v1, v2, v3}, $ACTION_CONFIRM->allowFollowRequest([Ljava/lang/Object;ZLjava/lang/Class;$STRING)Z
                move-result v0
                """,
            )
            if (!added) throw PatchException("Follow request accept has too few registers")
            enableSettings("confirmFollowRequest")
        }
    }
