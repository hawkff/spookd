/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.confirm

import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.patch
import app.morphe.patcher.string
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patcher.util.writeRegister
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction

private const val STRING = "Ljava/lang/String;"

// Like or unlike from a post's heart, behind every feed and post viewer like button.
private object FeedLikeToggleFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Lcom/instagram/feed/media/Media;", "L", STRING, "Lkotlin/jvm/functions/Function0;", "I"),
    filters = listOf(
        string("like_from_ufi"),
        string("ufi_like_clicked"),
        string("is_unlike"),
        // Its last argument is whether the post is liked already.
        methodCall(
            definingClass = "Lcom/facebook/quicklog/reliability/UserFlowLoggerImpl;",
            name = "flowAnnotate",
            parameters = listOf("J", STRING, "Z"),
        ),
    ),
)

@Suppress("unused")
val confirmFeedLikePatch =
    patch(
        name = "Confirm feed likes",
        description = "Adds an option to ask before liking a post with its heart in the feed and post viewers.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch)

        execute {
            val match = FeedLikeToggleFingerprint.matchOrNull() ?: return@execute
            val method = match.method
            if (AccessFlags.STATIC.isSet(method.accessFlags)) return@execute
            val annotate = match.instructionMatches.last()
            val liked = when (val instruction = annotate.instruction) {
                is FiveRegisterInstruction -> instruction.registerG
                is RegisterRangeInstruction -> instruction.startRegister + instruction.registerCount - 1
                else -> return@execute
            }
            val likedRead = runCatching {
                method.indexOfFirstInstructionReversedOrThrow(annotate.index - 1) { writeRegister == liked }
            }.getOrNull() ?: return@execute
            if (method.getInstruction(likedRead).opcode != Opcode.MOVE_RESULT || liked > 255) return@execute
            // The replay passes the original arguments, so they must still be in place here.
            val firstParameter = method.implementation!!.registerCount - 6
            if ((0..likedRead).any { (method.getInstruction(it).writeRegister ?: -1) >= firstParameter }) {
                return@execute
            }
            // Inserted first so the gate below can branch to it: puts the liked state back.
            method.addInstructions(
                likedRead + 1,
                """
                invoke-static {}, $ACTION_CONFIRM->stagedFeedLiked()Z
                move-result v$liked
                """.trimIndent(),
            )
            // Branches only to existing instructions: a label inside plain addInstructions
            // keeps the address it had in the snippet and lands elsewhere in the method.
            method.addInstructionsWithLabels(
                likedRead + 1,
                """
                invoke-static/range {v$liked .. v$liked}, $ACTION_CONFIRM->stageFeedLiked(Z)V
                invoke-static/range {p0 .. p5}, $ACTION_CONFIRM->allowFeedLike(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;${STRING}Ljava/lang/Object;I)Z
                move-result v$liked
                if-nez v$liked, :confirm_allowed
                return-void
                """.trimIndent(),
                ExternalLabel("confirm_allowed", method.getInstruction(likedRead + 1)),
            )
            enableSettings("confirmFeedLike")
        }
    }
