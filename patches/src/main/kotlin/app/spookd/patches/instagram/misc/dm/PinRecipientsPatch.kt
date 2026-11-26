/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.dm

import app.spookd.patches.instagram.entity.userdata.userDataEntity
import app.spookd.patches.instagram.misc.distractionFree.RECENT_SEARCH_DATA_SOURCE
import app.spookd.patches.instagram.misc.distractionFree.RecentSearchResultsFingerprint
import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.Constants.PATCHES_DESCRIPTOR
import app.spookd.patches.instagram.utils.Constants.USER_SESSION_CLASS
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.addInstructionsAtControlFlowLabel
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.p0Register
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val PINNED_RECIPIENTS_CLASS = "$PATCHES_DESCRIPTOR/dm/PinnedRecipients;"

/** Passes every object the method returns through [call], which takes [owner] and that object; returns how many were hooked. */
private fun MutableMethod.passReturnedValues(owner: Int, call: String): Int {
    val returns = instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }.map { it.index }
    var hooked = 0
    for (index in returns.reversed()) {
        val result = getInstruction<OneRegisterInstruction>(index).registerA
        if (result > 0xf) continue
        // Nothing runs after the return, so any other register can carry the owner there.
        val scratch = if (result == 0) 1 else 0
        addInstructionsAtControlFlowLabel(
            index,
            """
            move-object/from16 v$scratch, v$owner
            invoke-static {v$scratch, v$result}, $call
            move-result-object v$result
            """.trimIndent(),
        )
        hooked++
    }
    return hooked
}

@Suppress("unused")
val pinRecipientsPatch =
    patch(
        name = "Pin recipients",
        description = "Adds an option to pin chats from the chat menu in the inbox; pinned chats come first in the share sheet, and their people first in your recent searches.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch, hiddenChatsSharePatch, userDataEntity)

        execute {
            MenuOpenFingerprint.method.apply {
                val list = parameters.indexOfFirst { it.type == "Ljava/util/List;" }
                val menu = parameters.indexOfFirst { it.type == MenuButtonsLabelsFingerprint.classDef.type }
                val key = parameters.indexOfFirst { it.type == "Lcom/instagram/model/direct/DirectThreadKey;" }
                if (key < 0) throw PatchException("Chat menu opener has no chat key")
                // The method is static and its parameters sit far above v0, so they are copied down to be passed on.
                addInstructions(
                    0,
                    """
                    move-object/from16 v0, p$list
                    move-object/from16 v1, p$menu
                    move-object/from16 v2, p$key
                    invoke-static {v0, v1, v2}, $PINNED_RECIPIENTS_CLASS->menuOpening(Ljava/util/List;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/List;
                    move-result-object v0
                    move-object/from16 p$list, v0
                    """.trimIndent(),
                )
            }

            MenuButtonsLabelsFingerprint.method.apply {
                val list = p0Register + parameters.indexOfFirst { it.type == "Ljava/util/List;" }
                if (passReturnedValues(list, "$PINNED_RECIPIENTS_CLASS->menuLabels(Ljava/util/List;[Ljava/lang/String;)[Ljava/lang/String;") == 0) {
                    throw PatchException("Chat menu labels have no return to hook")
                }
            }

            val buttonEnum = MenuButtonsEnumFingerprint.classDef.type
            MenuButtonActionFingerprint.apply {
                val session = classDef.fields.first { it.type == USER_SESSION_CLASS }
                method.apply {
                    val button = parameters.indexOfFirst { it.type == buttonEnum } + 1
                    val key = parameters.indexOfFirst { it.type == "Lcom/instagram/model/direct/DirectThreadKey;" } + 1
                    if (button < 1 || key < 1) throw PatchException("Chat menu press has no button or chat key")
                    // Registers are fixed because the code goes first and nothing is live yet.
                    addInstructionsWithLabels(
                        0,
                        """
                        move-object/from16 v0, p0
                        iget-object v1, v0, $session
                        move-object/from16 v2, p$button
                        move-object/from16 v3, p$key
                        invoke-static {v1, v2, v3}, $PINNED_RECIPIENTS_CLASS->onMenuButton(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z
                        move-result v0
                        if-eqz v0, :spookd_pin_menu
                        return-void
                        """.trimIndent(),
                        ExternalLabel("spookd_pin_menu", getInstruction(0)),
                    )
                }
            }

            // The first list is the recipients the sheet suggests; the query tells a search apart.
            val query = ShareSheetQueryFingerprint.instructionMatches.first().instruction.getReference<FieldReference>()!!
            ShareSheetListsFingerprint.method.addInstructions(
                0,
                """
                iget-object v0, p0, $query
                invoke-static {p0, p1, v0}, $PINNED_RECIPIENTS_CLASS->orderShareTargets(Ljava/lang/Object;Ljava/util/List;Ljava/lang/String;)Ljava/util/List;
                move-result-object p1
                """.trimIndent(),
            )

            RecentSearchResultsFingerprint.method.apply {
                val dataSource = p0Register + parameters.indexOfFirst { it.type == RECENT_SEARCH_DATA_SOURCE }
                if (passReturnedValues(dataSource, "$PINNED_RECIPIENTS_CLASS->orderRecentSearches(Ljava/lang/Object;Ljava/util/List;)Ljava/util/List;") == 0) {
                    throw PatchException("Recent searches have no return to hook")
                }
            }

            enableSettings("pinRecipients")
        }
    }
