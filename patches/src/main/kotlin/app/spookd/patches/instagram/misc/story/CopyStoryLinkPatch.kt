/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.story

import app.spookd.patches.instagram.entity.mediadata.mediaDataEntity
import app.spookd.patches.instagram.entity.userdata.userDataEntity
import app.spookd.patches.instagram.links.privacy.REEL_ITEM_CLASS
import app.spookd.patches.instagram.misc.download.storyViewerItemList
import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.misc.stories.handleStoryButtonPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.Constants.PATCHES_DESCRIPTOR
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.patch.classDefByOrNull
import app.morphe.patcher.patch.patch
import com.android.tools.smali.dexlib2.AccessFlags

private object ReelIdNativeFingerprint : Fingerprint(
    definingClass = "$PATCHES_DESCRIPTOR/story/StoryLink;",
    name = "reelIdNative",
)

@Suppress("unused")
val copyStoryLinkPatch =
    patch(
        name = "Copy story link",
        description = "Adds an option to copy a story's link from the story menu, also on Close Friends stories. " +
            "On a highlight it copies the highlight link.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch, handleStoryButtonPatch, mediaDataEntity, userDataEntity)

        execute {
            val reelType = storyViewerItemList().definingClass
            // The reel model is the only field besides the item with an id ("highlight:<id>" for highlights).
            val reelModelField = classDefBy(reelType).instanceFields.singleOrNull { field ->
                field.type != REEL_ITEM_CLASS && classDefByOrNull(field.type)?.methods?.any {
                    it.name == "getId" && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;" &&
                        !AccessFlags.STATIC.isSet(it.accessFlags)
                } == true
            }
            // Optional: without it a highlight item copies its own story link.
            if (reelModelField != null) {
                ReelIdNativeFingerprint.method.addInstructions(
                    0,
                    """
                    check-cast p0, $reelType
                    iget-object p0, p0, $reelModelField
                    invoke-virtual {p0}, ${reelModelField.type}->getId()Ljava/lang/String;
                    move-result-object p0
                    return-object p0
                    """.trimIndent(),
                )
            }

            enableSettings("copyStoryLink")
        }
    }
