/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.ghostMode

import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.Constants.PATCHES_DESCRIPTOR
import app.spookd.patches.instagram.utils.Constants.USER_SESSION_CLASS
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.string
import app.morphe.patcher.util.getFreeRegisterProvider
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val KEEP_SEEN_LOCALLY_CLASS = "$PATCHES_DESCRIPTOR/dm/KeepSeenLocally;"

@Suppress("unused")
val keepSeenLocallyPatch = patch(
    name = "Keep DMs seen locally",
    description = "Adds an option to show chats as read on this device while viewing DMs anonymously, " +
        "without sending the read receipt.",
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)
    dependsOn(viewDmAnonymouslyPatch)

    execute {
        DMSeenFingerprint.method.apply {
            val instructions = implementation!!.instructions.toList()
            // The seen request ends by queueing its receipt: queue getter(session), then queue.add(receipt).
            val queueIndex = instructions.indexOfLast { it.opcode == Opcode.INVOKE_VIRTUAL }
            if (queueIndex < 2 || instructions.getOrNull(queueIndex + 1)?.opcode != Opcode.RETURN_VOID ||
                instructions[queueIndex - 2].opcode != Opcode.INVOKE_VIRTUAL ||
                instructions[queueIndex - 1].opcode != Opcode.MOVE_RESULT_OBJECT
            ) {
                throw PatchException("Unexpected DM seen receipt queue call")
            }
            val queue = getInstruction<FiveRegisterInstruction>(queueIndex)
            val queueCall = getInstruction(queueIndex).getReference<MethodReference>()
            val getter = getInstruction<FiveRegisterInstruction>(queueIndex - 2)
            val getterCall = getInstruction(queueIndex - 2).getReference<MethodReference>()
            if (queueCall == null || getterCall == null ||
                queue.registerCount != 2 || queueCall.returnType != "Z" || queueCall.parameterTypes.size != 1 ||
                getter.registerCount != 2 || getterCall.returnType != queueCall.definingClass ||
                getterCall.parameterTypes.map { it.toString() } != listOf(USER_SESSION_CLASS) ||
                getInstruction<OneRegisterInstruction>(queueIndex - 1).registerA != queue.registerC
            ) {
                throw PatchException("Unexpected DM seen receipt queue call")
            }
            val sessionRegister = getter.registerD
            val receiptRegister = queue.registerD
            if (sessionRegister > 15 || receiptRegister > 15) throw PatchException("DM seen receipt registers too high")
            val receiptType = instructions.subList(0, queueIndex).lastOrNull {
                it.opcode == Opcode.NEW_INSTANCE && (it as OneRegisterInstruction).registerA == receiptRegister
            }?.getReference<TypeReference>()?.type ?: throw PatchException("Could not resolve the DM seen receipt type")

            // Instagram's handler of the receipt's queue states; "queued" marks the chat read in its thread store.
            val lifecycle = Fingerprint(
                accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
                returnType = "V",
                parameters = listOf(receiptType, "L", "Ljava/lang/String;"),
                filters = listOf(string("queued"), string("executing")),
            ).originalMethod
            val store = lifecycle.parameterTypes[1].toString()
            val storeGetter = Fingerprint(
                returnType = "V",
                parameters = listOf("L", "L"),
                filters = listOf(methodCall(reference = lifecycle)),
            ).originalMethod.implementation!!.instructions.firstNotNullOfOrNull { instruction ->
                if (instruction.opcode != Opcode.INVOKE_STATIC) return@firstNotNullOfOrNull null
                instruction.getReference<MethodReference>()?.takeIf {
                    it.returnType == store && it.parameterTypes.map { type -> type.toString() } == listOf(USER_SESSION_CLASS)
                }
            } ?: throw PatchException("Could not resolve Instagram's DM thread store")

            Fingerprint(definingClass = KEEP_SEEN_LOCALLY_CLASS, name = "markSeen").method.addInstructions(
                0,
                """
                check-cast p1, $receiptType
                invoke-static {p0}, $storeGetter
                move-result-object p0
                invoke-static {p1, p0, p2}, $lifecycle
                """.trimIndent(),
            )

            val keptRegister = getFreeRegisterProvider(index = queueIndex, numberOfFreeRegistersNeeded = 1).getFreeRegister()
            addInstructionsWithLabels(
                queueIndex,
                """
                invoke-static {v$sessionRegister, v$receiptRegister}, $KEEP_SEEN_LOCALLY_CLASS->keptLocally(${USER_SESSION_CLASS}Ljava/lang/Object;)Z
                move-result v$keptRegister
                if-eqz v$keptRegister, :spookd_queue_receipt
                return-void
                """.trimIndent(),
                ExternalLabel("spookd_queue_receipt", getInstruction(queueIndex)),
            )
        }
        enableSettings("keepSeenLocally")
    }
}
