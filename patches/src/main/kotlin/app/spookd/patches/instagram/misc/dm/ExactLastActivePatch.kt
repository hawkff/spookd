/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.dm

import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.Constants.PATCHES_DESCRIPTOR
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstructions
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.registersUsed
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS = "$PATCHES_DESCRIPTOR/dm/LastActive;"

// Instagram's presence label of a person: "Active now", "Active 5m ago", "Active 2h ago", today, yesterday or none.
private object PresenceLabelFingerprint : Fingerprint(
    returnType = "Ljava/lang/String;",
    parameters = listOf("Landroid/content/res/Resources;", "Ljava/util/List;"),
    filters = listOf(
        methodCall(smali = "Ljava/util/List;->get(I)Ljava/lang/Object;"),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        // Presence state.
        opcode(Opcode.CHECK_CAST, location = MatchAfterImmediately()),
        // Last activity time in milliseconds.
        fieldAccess(type = "J", opcode = Opcode.IGET_WIDE, location = MatchAfterImmediately()),
        // Active now check.
        methodCall(parameters = listOf("L", "J"), returnType = "Z", opcode = Opcode.INVOKE_STATIC),
        methodCall(smali = "Ljava/util/concurrent/TimeUnit;->toMinutes(J)J"),
    ),
)

// Builds the chat header; the label it reads here is shown under the name of a one-to-one chat.
private fun chatHeaderPresenceLabelFingerprint(label: MethodReference) = Fingerprint(
    strings = listOf("igd_meta_ai_organic_iab"),
    filters = listOf(
        methodCall(reference = label),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
    ),
)

@Suppress("unused")
val exactLastActivePatch =
    patch(
        name = "Exact last active time",
        description = "Adds an option to show the time a person was last active in the chat header instead of \"Active 5m ago\".",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch)

        execute {
            val label = PresenceLabelFingerprint
            val lastActivity = label.instructionMatches[3].fieldReference
            val presence = lastActivity.definingClass
            val activeNow = label.instructionMatches[4].methodReference
            if (activeNow.parameterTypes.map { it.toString() } != listOf(presence, "J")) {
                throw PatchException("Unexpected presence active check $activeNow")
            }

            Fingerprint(definingClass = EXTENSION_CLASS, name = "presenceActive").method.apply {
                removeInstructions(0, instructions.size)
                addInstructions(
                    0,
                    """
                    check-cast p0, $presence
                    invoke-static {p0, p1, p2}, $activeNow
                    move-result v0
                    return v0
                    """.trimIndent(),
                )
            }
            Fingerprint(definingClass = EXTENSION_CLASS, name = "lastActivityMs").method.apply {
                removeInstructions(0, instructions.size)
                addInstructions(
                    0,
                    """
                    check-cast p0, $presence
                    iget-wide v0, p0, $lastActivity
                    return-wide v0
                    """.trimIndent(),
                )
            }

            val header = chatHeaderPresenceLabelFingerprint(label.originalMethod)
            header.method.apply {
                // The list is the last argument whether the label method is static or not.
                val presences = header.instructionMatches[0].instruction.registersUsed.last()
                val resultIndex = header.instructionMatches[1].index
                val result = getInstruction<OneRegisterInstruction>(resultIndex).registerA
                // The label overwriting the list leaves no list to pass; the switch then stays hidden.
                if (presences > 15 || result > 15 || presences == result) return@execute
                addInstructions(
                    resultIndex + 1,
                    """
                    invoke-static {v$result, v$presences}, $EXTENSION_CLASS->chatHeaderStatus(Ljava/lang/String;Ljava/util/List;)Ljava/lang/String;
                    move-result-object v$result
                    """.trimIndent(),
                )
            }

            enableSettings("exactLastActive")
        }
    }
