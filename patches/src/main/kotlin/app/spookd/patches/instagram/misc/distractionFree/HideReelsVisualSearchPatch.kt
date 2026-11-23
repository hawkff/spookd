/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.distractionFree

import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.Constants.PREF_CALL_DESCRIPTOR
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.patch
import app.morphe.patcher.string
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

// Reels side buttons: the visual search button is built only when its UI state is the visible one.
private object ReelsVisualSearchButtonFingerprint : Fingerprint(
    filters = listOf(
        string("android_purge_26_q2_ClipsUfiComponent_maybeCreateVisualSearchButtonComponent"),
        // The button's UI state.
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterWithin(3)),
        opcode(Opcode.INSTANCE_OF, location = MatchAfterImmediately()),
    ),
)

@Suppress("unused")
val hideReelsVisualSearchPatch =
    patch(
        name = "Hide Reels visual search button",
        description = "Adds an option to hide the visual search button on Reels.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch)

        execute {
            ReelsVisualSearchButtonFingerprint.let {
                val state = it.instructionMatches[1].getInstruction<OneRegisterInstruction>().registerA
                val check = it.instructionMatches.last().index
                it.method.apply {
                    val free = findFreeRegister(check, state)
                    // Without a state the button is skipped, as Instagram does when it is unavailable.
                    addInstructionsWithLabels(
                        check,
                        """
                        $PREF_CALL_DESCRIPTOR->hideReelsVisualSearch()Z
                        move-result v$free
                        if-eqz v$free, :spookd
                        const/16 v$state, 0x0
                        """.trimIndent(),
                        ExternalLabel("spookd", getInstruction(check)),
                    )
                }
            }

            enableSettings("hideReelsVisualSearch")
        }
    }
