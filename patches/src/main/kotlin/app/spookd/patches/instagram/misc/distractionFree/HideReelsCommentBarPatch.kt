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
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.string
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
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

// Shows the bar directly, for example when a drawer over the viewer is dismissed. Its first view field
// read starts the branch that sets the bar and its CTA container GONE.
private object ShowReelsCommentBarFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        string("android_purge_26_q2_ClipsViewerCommentBarController_showCommentBar"),
        fieldAccess(opcode = Opcode.IGET_OBJECT, definingClass = "this", type = "Landroid/view/View;"),
    ),
)

// Shows or hides the CTA container next to the bar.
private object ReelsCommentBarCtaFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        string("android_purge_26_q3_CommentBarActionHandler_handleShouldShowCtaButton"),
        methodCall(name = "setVisibility", parameters = listOf("I")),
    ),
)

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

            ShowReelsCommentBarFingerprint.let {
                val hideBranch = it.instructionMatches.last().index
                it.method.apply {
                    val free = findFreeRegister(0)
                    addInstructionsWithLabels(
                        0,
                        """
                        invoke-static {}, $DISTRACTION_FREE_HIDES->hideReelsCommentBar()Z
                        move-result v$free
                        if-nez v$free, :spookd_hide_comment_bar
                        """.trimIndent(),
                        ExternalLabel("spookd_hide_comment_bar", getInstruction(hideBranch)),
                    )
                }
            }

            ReelsCommentBarCtaFingerprint.let {
                val call = it.instructionMatches.last()
                val visibility = call.getInstruction<FiveRegisterInstruction>().registerD
                it.method.addInstructions(
                    call.index,
                    """
                    invoke-static/range {v$visibility .. v$visibility}, $DISTRACTION_FREE_HIDES->reelsCommentBarCtaVisibility(I)I
                    move-result v$visibility
                    """.trimIndent(),
                )
            }

            enableSettings("hideReelsCommentBar")
        }
    }
