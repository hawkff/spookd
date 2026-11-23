/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.download

import app.spookd.patches.instagram.links.privacy.ConsumedStoryFingerprint
import app.spookd.patches.instagram.links.privacy.REEL_ITEM_CLASS
import app.spookd.patches.instagram.links.privacy.ReelTypeFingerprint
import app.spookd.patches.instagram.misc.extension.sharedExtensionPatch
import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.Constants.PATCHES_DESCRIPTOR
import app.spookd.patches.instagram.utils.Constants.USER_SESSION_CLASS
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.patch.patch

private const val STORY_AUTO_SAVE_DESCRIPTOR = "$PATCHES_DESCRIPTOR/story/StoryAutoSave;"

private fun storyAutoSaveFingerprint(name: String) = Fingerprint(
    definingClass = STORY_AUTO_SAVE_DESCRIPTOR,
    name = name,
)

/**
 * Saves story items when Instagram marks them viewed. When the viewed-item method is not found,
 * nothing is hooked and the setting stays hidden.
 */
val storyAutoSavePatch = patch {
    compatibleWith(COMPATIBILITY_INSTAGRAM)
    dependsOn(sharedExtensionPatch, settingsPatch)

    execute {
        if (ConsumedStoryFingerprint.matchOrNull() == null) return@execute
        val consumed = ConsumedStoryFingerprint.instructionMatches
        val loggerClass = ConsumedStoryFingerprint.originalClassDef
        val loggerSessionField = loggerClass.fields.single { it.type == USER_SESSION_CLASS }
        val reelParentClass = ConsumedStoryFingerprint.originalMethod.parameterTypes[1].toString()
        val reelModelField = consumed[6].fieldReference
        // Ads come as their own reel type, which the extension tells apart from user stories.
        if (ReelTypeFingerprint.matchOrNull() == null) return@execute
        val reelTypeField = classDefBy(reelModelField.type).fields.singleOrNull {
            it.type == ReelTypeFingerprint.originalClassDef.type
        } ?: return@execute

        storyAutoSaveFingerprint("sessionNative").method.addInstructions(
            0,
            """
            check-cast p0, ${loggerClass.type}
            iget-object p0, p0, $loggerSessionField
            return-object p0
            """.trimIndent(),
        )
        storyAutoSaveFingerprint("mediaNative").method.addInstructions(
            0,
            """
            check-cast p0, $REEL_ITEM_CLASS
            iget-object p0, p0, ${consumed[17].fieldReference}
            return-object p0
            """.trimIndent(),
        )
        storyAutoSaveFingerprint("reelTypeNative").method.addInstructions(
            0,
            """
            check-cast p0, $reelParentClass
            iget-object p0, p0, $reelModelField
            iget-object p0, p0, $reelTypeField
            return-object p0
            """.trimIndent(),
        )
        // Before Instagram queues the item for its receipt; the story ghost list hooks here too.
        ConsumedStoryFingerprint.method.addInstruction(
            consumed[24].index,
            "invoke-static/range {p0 .. p2}, $STORY_AUTO_SAVE_DESCRIPTOR->" +
                "onStoryViewed(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V",
        )

        enableSettings("storyAutoSave")
    }
}
