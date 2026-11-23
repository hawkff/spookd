/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.distractionFree

import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.Constants.PREF_DESCRIPTOR
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.patch
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

// Reads the comment sheet arguments; the emoji bar flag is the one Instagram sets for watch-and-comment.
private object CommentThreadArgumentsFingerprint : Fingerprint(
    parameters = listOf("Landroid/os/Bundle;"),
    filters = listOf(
        string("CommentThreadFragment.HIDE_EMOJI_BAR"),
        methodCall(
            definingClass = "Landroid/os/BaseBundle;",
            name = "getBoolean",
            returnType = "Z",
            location = MatchAfterImmediately(),
        ),
        opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately()),
    ),
)

@Suppress("unused")
val hideCommentEmojiBarPatch =
    patch(
        name = "Hide comment emoji bar",
        description = "Adds an option to hide the quick emoji row above the comment box.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch)

        execute {
            CommentThreadArgumentsFingerprint.let {
                val read = it.instructionMatches.last()
                val register = read.getInstruction<OneRegisterInstruction>().registerA
                it.method.addInstructions(
                    read.index + 1,
                    """
                    invoke-static/range {v$register .. v$register}, $PREF_DESCRIPTOR->hideCommentEmojiBar(Z)Z
                    move-result v$register
                    """.trimIndent(),
                )
            }

            enableSettings("hideCommentEmojiBar")
        }
    }
