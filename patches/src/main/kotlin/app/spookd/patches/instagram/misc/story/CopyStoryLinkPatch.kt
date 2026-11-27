/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.story

import app.spookd.patches.instagram.entity.decoder.MEDIA_CLASS_NAME
import app.spookd.patches.instagram.entity.mediadata.mediaDataEntity
import app.spookd.patches.instagram.entity.userdata.userDataEntity
import app.spookd.patches.instagram.links.privacy.REEL_ITEM_CLASS
import app.spookd.patches.instagram.misc.download.storyViewerItemList
import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.misc.stories.OnCLickStoryButtonFingerprint
import app.spookd.patches.instagram.misc.stories.handleStoryButtonPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.Constants.PATCHES_DESCRIPTOR
import app.spookd.patches.instagram.utils.Constants.USER_SESSION_CLASS
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.patch.classDefByOrNull
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.p0Register
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val STORY_LINK_CLASS = "$PATCHES_DESCRIPTOR/story/StoryLink;"
private const val CHAR_SEQUENCE = "Ljava/lang/CharSequence;"
private const val DISMISS_LISTENER = "Landroid/content/DialogInterface\$OnDismissListener;"

// Anchors of the two story menu click handlers handleStoryButtonPatch already hooks.
private val HOOKED_STORY_MENU_CLICK_STRINGS = setOf("explore_viewer", "story_interactions/bulk_story_like/")

private fun storyLinkStub(name: String) = Fingerprint(definingClass = STORY_LINK_CLASS, name = name)

/**
 * The menu of your own highlight is built from its own two entry lists and click handler in the story menu class.
 * Optional: when a part is not found, that menu goes without the entry.
 */
private fun addOwnerHighlightMenuHooks(reelType: String) {
    val menuClass = OnCLickStoryButtonFingerprint.originalClassDef
    val menuType = menuClass.type
    // The class's only methods returning an ArrayList: with a reel group (A05) or without (A04).
    val entryLists = listOf(listOf(menuType), listOf(menuType, "L")).map { parameters ->
        Fingerprint(definingClass = menuType, returnType = "Ljava/util/ArrayList;", parameters = parameters)
            .matchOrNull()?.method ?: return
    }
    val click = Fingerprint(
        definingClass = menuType,
        returnType = "V",
        strings = listOf("user_paused_video", "[INTERNAL] Resume Playback"),
        custom = { method, _ ->
            AccessFlags.STATIC.isSet(method.accessFlags) && method.implementation?.instructions?.none {
                it.getReference<StringReference>()?.string in HOOKED_STORY_MENU_CLICK_STRINGS
            } == true
        },
    ).matchOrNull()?.method ?: return

    val fields = menuClass.instanceFields
    val sessionField = fields.singleOrNull { it.type == USER_SESSION_CLASS } ?: return
    val itemField = fields.singleOrNull { it.type == REEL_ITEM_CLASS } ?: return
    val reelField = fields.singleOrNull { it.type == reelType } ?: return
    val dismissField = fields.singleOrNull { it.type == DISMISS_LISTENER } ?: return
    val mediaField = click.instructions.mapNotNull { it.getReference<FieldReference>() }
        .filter { it.definingClass == REEL_ITEM_CLASS && it.type == MEDIA_CLASS_NAME }
        .distinctBy { it.toString() }
        .singleOrNull() ?: return
    val parameters = click.parameterTypes.map { it.toString() }
    val textParameter = parameters.indexOfLast { it == CHAR_SEQUENCE }
    val menuParameter = parameters.indexOfLast { it == menuType }
    val dismissParameter = parameters.indices.singleOrNull { parameters[it] == DISMISS_LISTENER } ?: return
    // Static with no wide parameters, so pN is parameter N; v0..v2 are free at method entry.
    if (textParameter < 0 || menuParameter < 0 || parameters.any { it == "J" || it == "D" } || click.p0Register < 3) {
        return
    }

    storyLinkStub("menuSessionNative").method.addInstructions(
        0,
        """
        check-cast p0, $menuType
        iget-object p0, p0, $sessionField
        return-object p0
        """.trimIndent(),
    )
    storyLinkStub("menuReelNative").method.addInstructions(
        0,
        """
        check-cast p0, $menuType
        iget-object p0, p0, $reelField
        return-object p0
        """.trimIndent(),
    )
    storyLinkStub("menuMediaNative").method.addInstructions(
        0,
        """
        check-cast p0, $menuType
        iget-object p0, p0, $itemField
        iget-object p0, p0, $mediaField
        return-object p0
        """.trimIndent(),
    )
    entryLists.forEach { list ->
        list.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }.reversed().forEach {
            val register = (it.value as OneRegisterInstruction).registerA
            list.addInstruction(
                it.index,
                "invoke-static/range {v$register .. v$register}, $STORY_LINK_CLASS->" +
                    "addOwnerHighlightMenuItem(Ljava/util/ArrayList;)V",
            )
        }
    }
    // Like Instagram's own entries, a handled entry clears the menu's dismiss listener and resumes the story itself.
    click.addInstructionsWithLabels(
        0,
        """
        move-object/from16 v0, p$textParameter
        move-object/from16 v1, p$menuParameter
        move-object/from16 v2, p$dismissParameter
        invoke-static {v0, v1, v2}, $STORY_LINK_CLASS->onOwnerHighlightMenuClick(${CHAR_SEQUENCE}Ljava/lang/Object;$DISMISS_LISTENER)Z
        move-result v0
        if-eqz v0, :spookd_original
        const/4 v0, 0x0
        iput-object v0, v1, $dismissField
        return-void
        """.trimIndent(),
        ExternalLabel("spookd_original", click.getInstruction(0)),
    )
}

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
                storyLinkStub("reelIdNative").method.addInstructions(
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
            addOwnerHighlightMenuHooks(reelType)

            enableSettings("copyStoryLink")
        }
    }
