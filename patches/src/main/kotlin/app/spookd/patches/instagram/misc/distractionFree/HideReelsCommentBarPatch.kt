/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.distractionFree

import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction

// Builds the Reels viewer comment bar's UI state. Its second boolean is the switch Instagram sets to
// hide the bar on audio page Reels.
private object ReelsCommentBarStateFingerprint : Fingerprint(
    filters = listOf(
        string("android_purge_26_q2_ClipsViewerCommentBarController_setUpCta"),
        methodCall(
            opcode = Opcode.INVOKE_DIRECT_RANGE,
            name = "<init>",
            parameters = listOf(
                "Ljava/lang/Integer;", "Ljava/lang/Integer;", "Z", "Z", "Z", "Z", "Z", "Z", "Z", "Z", "Z",
            ),
        ),
    ),
)

// The state object, both hint texts and the first boolean come before it.
private const val HIDE_BAR_ARGUMENT = 4

@Suppress("unused")
val hideReelsCommentBarPatch =
    patch(
        name = "Hide Reels comment bar",
        description = "Adds an option to hide the comment bar at the bottom of the Reels viewer.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch)

        execute {
            ReelsCommentBarStateFingerprint.let {
                val init = it.instructionMatches.last()
                val call = init.getInstruction<RegisterRangeInstruction>()
                if (call.registerCount != 12) throw PatchException("Unexpected Reels comment bar state constructor")
                val hide = call.startRegister + HIDE_BAR_ARGUMENT
                it.method.addInstructions(
                    init.index,
                    """
                    invoke-static/range {v$hide .. v$hide}, $DISTRACTION_FREE_HIDES->hideReelsCommentBar(Z)Z
                    move-result v$hide
                    """.trimIndent(),
                )
            }

            enableSettings("hideReelsCommentBar")
        }
    }
