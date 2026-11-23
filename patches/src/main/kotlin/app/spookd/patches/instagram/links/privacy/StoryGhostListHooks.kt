/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.links.privacy

import app.spookd.patches.instagram.utils.Constants.USER_SESSION_CLASS
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

internal const val STORY_GHOST_LIST_DESCRIPTOR =
    "Lapp/morphe/extension/instagram/patches/story/StoryGhostList;"

/**
 * Likes or unlikes the story: like state, reel model, item and reel.
 */
internal object StoryLikeFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L", "L", REEL_ITEM_CLASS, "L"),
    strings = listOf("storyLikesDelegate"),
)

/**
 * Logs a reply or quick reaction sent from the story viewer.
 */
internal fun storyReplySentFingerprint(loggerClass: String) = Fingerprint(
    definingClass = loggerClass,
    returnType = "V",
    parameters = listOf("L", STRING_CLASS, STRING_CLASS, "F", "F", "Z", "Z"),
    filters = listOf(
        // Current item of the reel.
        methodCall(parameters = listOf(USER_SESSION_CLASS), returnType = REEL_ITEM_CLASS),
        string("reel_send_message"),
    ),
)

private fun storyGhostListFingerprint(name: String) = Fingerprint(
    definingClass = STORY_GHOST_LIST_DESCRIPTOR,
    name = name,
)

/**
 * Sends the receipts of owners the story ghost mode leaves out, and of liked or replied stories.
 * The like and reply hooks are optional; their setting only shows when both apply.
 */
internal fun addStoryGhostListHooks(
    reelParentClass: String,
    reelModelField: FieldReference,
    reelOwnerIdField: FieldReference,
) {
    val loggerClass = ConsumedStoryFingerprint.originalClassDef
    val loggerSessionField = loggerClass.fields.single { it.type == USER_SESSION_CLASS }

    storyGhostListFingerprint("ownerIdNative").method.addInstructions(
        0,
        """
        check-cast p0, $reelParentClass
        iget-object p0, p0, $reelModelField
        iget-object p0, p0, $reelOwnerIdField
        return-object p0
        """.trimIndent(),
    )
    storyGhostListFingerprint("loggerSessionNative").method.addInstructions(
        0,
        """
        check-cast p0, ${loggerClass.type}
        iget-object p0, p0, $loggerSessionField
        return-object p0
        """.trimIndent(),
    )
    // Right before Instagram queues the item for its own receipt, so only items it decided to report get one.
    ConsumedStoryFingerprint.method.addInstruction(
        ConsumedStoryFingerprint.instructionMatches[24].index,
        "invoke-static/range {p0 .. p2}, $STORY_GHOST_LIST_DESCRIPTOR->" +
            "onStoryConsumed(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V",
    )

    val likeHooked = StoryLikeFingerprint.matchOrNull()?.method?.let { like ->
        val sessionField = classDefBy(like.definingClass).fields.singleOrNull { it.type == USER_SESSION_CLASS }
        if (sessionField == null || like.parameterTypes[3].toString() != reelParentClass) return@let false
        storyGhostListFingerprint("controllerSessionNative").method.addInstructions(
            0,
            """
            check-cast p0, ${like.definingClass}
            iget-object p0, p0, $sessionField
            return-object p0
            """.trimIndent(),
        )
        like.addInstruction(
            0,
            "invoke-static/range {p0 .. p4}, $STORY_GHOST_LIST_DESCRIPTOR->onStoryLiked(" +
                "Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V",
        )
        true
    } ?: false

    val replyHooked = storyReplySentFingerprint(loggerClass.type).matchOrNull()?.let { reply ->
        val currentItem = reply.instructionMatches.first().methodReference
        if (currentItem.definingClass != reelParentClass ||
            reply.method.parameterTypes[0].toString() != reelParentClass
        ) {
            return@let false
        }
        storyGhostListFingerprint("currentItemNative").method.addInstructions(
            0,
            """
            check-cast p0, $USER_SESSION_CLASS
            check-cast p1, $reelParentClass
            invoke-virtual {p1, p0}, $currentItem
            move-result-object p0
            return-object p0
            """.trimIndent(),
        )
        reply.method.addInstruction(
            0,
            "invoke-static/range {p0 .. p1}, $STORY_GHOST_LIST_DESCRIPTOR->" +
                "onStoryReplySent(Ljava/lang/Object;Ljava/lang/Object;)V",
        )
        true
    } ?: false

    if (likeHooked && replyHooked) enableSettings("markStorySeenOnInteraction")
}
