/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.links.privacy

import app.spookd.patches.instagram.utils.Constants.USER_SESSION_CLASS
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

internal const val STORY_GHOST_LIST_DESCRIPTOR =
    "Lapp/morphe/extension/instagram/patches/story/StoryGhostList;"

private fun storyGhostListFingerprint(name: String) = Fingerprint(
    definingClass = STORY_GHOST_LIST_DESCRIPTOR,
    name = name,
)

/**
 * Sends the receipts of owners the story ghost mode leaves out.
 */
internal fun addStoryGhostListHooks(
    reelParentClass: String,
    reelModelField: FieldReference,
    reelOwnerIdField: FieldReference,
    reelTypeField: FieldReference,
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
    storyGhostListFingerprint("sourceNative").method.addInstructions(
        0,
        """
        check-cast p0, $reelParentClass
        iget-object p0, p0, $reelModelField
        iget-object p0, p0, $reelTypeField
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
    ConsumedStoryFingerprint.method.addInstruction(
        0,
        "invoke-static/range {p0 .. p2}, $STORY_GHOST_LIST_DESCRIPTOR->" +
            "onStoryConsumed(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V",
    )
}
