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
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.string
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val STRING = "Ljava/lang/String;"
private const val COMMENT_LIST = "Lcom/instagram/comments/mvvm/data/MediaCommentListRepository;"

// Likes or unlikes a comment: (current reaction, post, comment id, String, Z). The state change runs later in a coroutine.
private object CommentLikeActionFingerprint : Fingerprint(
    definingClass = COMMENT_LIST,
    returnType = "V",
    parameters = listOf("L", "L", STRING, STRING, "Z"),
    filters = listOf(literal(0x81102a000756f2L)),
)

// A lambda whose case 0 is the like button's tap: it updates the post's cached comment previews, then calls the like action.
private object CommentLikeTapFingerprint : Fingerprint(
    name = "invoke",
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;"),
    filters = listOf(
        methodCall(definingClass = COMMENT_LIST, returnType = "V"),
        string("double_tap"),
    ),
)

private fun MutableMethod.field(index: Int) =
    (implementation!!.instructions.getOrNull(index) as? ReferenceInstruction)?.reference as? FieldReference

/**
 * Asks at the start of the tap lambda, before any cached preview changes, for its like case. The case
 * field is read right before the method's first branch; the bound reaction is the own field compared with
 * the first static read of the like action's reaction type.
 */
private fun MutableMethod.gateLikeTap(action: MethodReference) {
    val instructions = implementation!!.instructions
    val caseRead = instructions.indexOfFirst { it.opcode == Opcode.IGET }
    val caseField = field(caseRead)?.takeIf {
        it.definingClass == definingClass && it.type == "I" &&
            instructions.getOrNull(caseRead + 1)?.opcode == Opcode.IF_EQZ &&
            (instructions[caseRead + 1] as OneRegisterInstruction).registerA == (instructions[caseRead] as TwoRegisterInstruction).registerA
    } ?: throw PatchException("Comment like tap case not found")

    val reactionType = action.parameterTypes.first().toString()
    val constantRead = instructions.indices.firstOrNull {
        instructions[it].opcode == Opcode.SGET_OBJECT && field(it)?.type == reactionType
    } ?: -1
    val constant = (instructions.getOrNull(constantRead) as? OneRegisterInstruction)?.registerA
        ?: throw PatchException("Comment like tap reaction check not found")
    val compare = (constantRead + 1..minOf(constantRead + 8, instructions.size - 1)).firstOrNull {
        instructions[it].opcode == Opcode.IF_EQ &&
            constant in listOf((instructions[it] as TwoRegisterInstruction).registerA, (instructions[it] as TwoRegisterInstruction).registerB)
    } ?: throw PatchException("Comment like tap reaction check not found")
    val compared = (instructions[compare] as TwoRegisterInstruction).let { if (it.registerA == constant) it.registerB else it.registerA }
    val reactionRead = (constantRead - 1 downTo 0).firstOrNull {
        instructions[it].opcode == Opcode.IGET_OBJECT && (instructions[it] as OneRegisterInstruction).registerA == compared
    } ?: throw PatchException("Comment like tap reaction not found")
    val reactionField = field(reactionRead)?.takeIf { it.definingClass == definingClass }
        ?: throw PatchException("Comment like tap reaction is not its own field")

    // The lambda's own Unit, returned when the prompt holds the tap back.
    val unitRead = instructions.indices.firstOrNull {
        instructions[it].opcode == Opcode.SGET_OBJECT && instructions.getOrNull(it + 1)?.opcode == Opcode.RETURN_OBJECT
    } ?: throw PatchException("Comment like tap result not found")
    val unit = field(unitRead)!!
    if (implementation!!.registerCount - 2 < 5) throw PatchException("Comment like tap has too few registers")

    addInstructionsWithLabels(
        0,
        """
        move-object/from16 v0, p0
        iget v1, v0, ${caseField.definingClass}->${caseField.name}:${caseField.type}
        iget-object v2, v0, ${reactionField.definingClass}->${reactionField.name}:${reactionField.type}
        move-object/from16 v3, p1
        const-string v4, "$name"
        invoke-static {v0, v1, v2, v3, v4}, $ACTION_CONFIRM->allowCommentLikeTap(Ljava/lang/Object;ILjava/lang/Object;Ljava/lang/Object;$STRING)Z
        move-result v0
        if-nez v0, :confirm_allowed
        sget-object v0, ${unit.definingClass}->${unit.name}:${unit.type}
        return-object v0
        """.trimIndent(),
        ExternalLabel("confirm_allowed", getInstruction(0)),
    )
}

@Suppress("unused")
val confirmCommentLikePatch =
    patch(
        name = "Confirm comment likes",
        description = "Adds an option to ask before liking a comment.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch)

        execute {
            val method = CommentLikeActionFingerprint.method
            if (AccessFlags.STATIC.isSet(method.accessFlags)) throw PatchException("Comment like action is static")
            // The tap updates the post's cached previews before the action, so it is asked first.
            val tapCall = CommentLikeTapFingerprint.instructionMatches[0].instruction
            val called = (tapCall as ReferenceInstruction).reference as MethodReference
            if (called.name != method.name ||
                called.parameterTypes.map(CharSequence::toString) != method.parameterTypes.map(CharSequence::toString)
            ) {
                throw PatchException("Comment like tap does not call the like action")
            }
            CommentLikeTapFingerprint.method.gateLikeTap(called)
            // Other callers, such as a double tap in the comment list, still pass here.
            val added = method.addConfirmGate(
                3,
                """
                filled-new-array/range {p0 .. p4}, [Ljava/lang/Object;
                move-result-object v0
                move/from16 v1, p5
                const-string v2, "${method.name}"
                invoke-static {v0, v1, v2}, $ACTION_CONFIRM->allowCommentLike([Ljava/lang/Object;Z$STRING)Z
                move-result v0
                """,
            )
            if (!added) throw PatchException("Comment like action has too few registers")
            enableSettings("confirmCommentLike")
        }
    }
