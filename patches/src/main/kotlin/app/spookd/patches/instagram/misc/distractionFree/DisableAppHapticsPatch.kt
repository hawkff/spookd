/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.distractionFree

import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.Constants.PATCHES_DESCRIPTOR
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.registersUsed
import app.morphe.util.findInstructionIndicesReversed
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val APP_HAPTICS = "$PATCHES_DESCRIPTOR/distractionFree/AppHaptics;"
private const val VIEW = "Landroid/view/View;"

// View.performHapticFeedback(int) and (int, int), keyed by their parameters.
private val VIEW_HAPTIC_STAND_INS = mapOf(
    listOf("I") to "performHapticFeedback(Landroid/view/View;I)Z",
    listOf("I", "I") to "performHapticFeedback(Landroid/view/View;II)Z",
)

private object HapticFeedbackCallFingerprint : Fingerprint(
    filters = listOf(methodCall(definingClass = VIEW, name = "performHapticFeedback", returnType = "Z")),
    custom = { _, classDef -> !classDef.type.startsWith("Lapp/morphe/extension/") },
)

/** The extension method standing in for this instruction, if it is a View haptic feedback call. */
private fun Instruction.hapticStandIn(): String? {
    if (opcode != Opcode.INVOKE_VIRTUAL && opcode != Opcode.INVOKE_VIRTUAL_RANGE) return null
    val reference = getReference<MethodReference>() ?: return null
    if (reference.definingClass != VIEW || reference.name != "performHapticFeedback" || reference.returnType != "Z") return null
    return VIEW_HAPTIC_STAND_INS[reference.parameterTypes.map(CharSequence::toString)]
}

@Suppress("unused")
val disableAppHapticsPatch =
    patch(
        name = "Disable app haptics",
        description = "Adds an option to stop the haptic feedback Instagram plays on taps and gestures.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch)

        execute {
            HapticFeedbackCallFingerprint.matchAll().forEach { match ->
                match.method.apply {
                    // The stand-ins take the same registers, so the indices stay valid.
                    findInstructionIndicesReversed { hapticStandIn() != null }.forEach { index ->
                        val call = getInstruction(index)
                        val registers = call.registersUsed
                        val standIn = "$APP_HAPTICS->${call.hapticStandIn()}"
                        replaceInstruction(
                            index,
                            if (call.opcode == Opcode.INVOKE_VIRTUAL_RANGE) {
                                "invoke-static/range {v${registers.first()} .. v${registers.last()}}, $standIn"
                            } else {
                                "invoke-static {${registers.joinToString { "v$it" }}}, $standIn"
                            },
                        )
                    }
                }
            }

            enableSettings("disableAppHaptics")
        }
    }
