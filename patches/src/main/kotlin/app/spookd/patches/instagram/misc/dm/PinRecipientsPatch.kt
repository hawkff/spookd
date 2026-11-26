/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.dm

import app.spookd.patches.instagram.entity.userdata.userDataEntity
import app.spookd.patches.instagram.misc.distractionFree.RECENT_SEARCH_DATA_SOURCE
import app.spookd.patches.instagram.misc.distractionFree.RecentSearchResultsFingerprint
import app.spookd.patches.instagram.misc.settings.SetCurrentUserSessionFingerprint
import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.Constants.PATCHES_DESCRIPTOR
import app.spookd.patches.instagram.utils.Constants.USER_SESSION_CLASS
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
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
import app.morphe.util.findInstructionIndicesReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

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
        description = "Adds an option to pin chats from the chat menu in the inbox; pinned chats move to the top of the share sheet, and their people to the top of your recent searches, when Instagram lists them.",
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
                val queryType = parameters.first().type
                val dataSource = parameters.indexOfFirst { it.type == RECENT_SEARCH_DATA_SOURCE }
                // Whether the query is empty: the no-argument boolean of the query the method asks first.
                val emptyQuery = instructions.firstNotNullOfOrNull { instruction ->
                    if (instruction.opcode != Opcode.INVOKE_VIRTUAL) return@firstNotNullOfOrNull null
                    (instruction as ReferenceInstruction).reference.let { it as? MethodReference }?.takeIf {
                        it.definingClass == queryType && it.returnType == "Z" && it.parameterTypes.isEmpty()
                    }
                } ?: throw PatchException("Recent searches do not check their query")
                // Instagram reuses the data source's parameter register later, so the account is taken at the start.
                addInstructions(
                    0,
                    """
                    invoke-virtual/range {p0 .. p0}, $emptyQuery
                    move-result v0
                    move-object/from16 v1, p$dataSource
                    invoke-static {v1, v0}, $PINNED_RECIPIENTS_CLASS->noteRecentSearch(Ljava/lang/Object;Z)V
                    """.trimIndent(),
                )
                val returns = instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }.map { it.index }
                if (returns.isEmpty()) throw PatchException("Recent searches have no return to hook")
                // Every return, an early one added by another patch included, hands its list through and ends the noted call.
                for (index in returns.reversed()) {
                    val result = getInstruction<OneRegisterInstruction>(index).registerA
                    addInstructionsAtControlFlowLabel(
                        index,
                        """
                        invoke-static/range {v$result .. v$result}, $PINNED_RECIPIENTS_CLASS->orderRecentSearches(Ljava/util/List;)Ljava/util/List;
                        move-result-object v$result
                        """.trimIndent(),
                    )
                }
            }

            // Kept entries are dropped when another account becomes current; optional, they also expire on their own.
            SetCurrentUserSessionFingerprint.matchOrNull()?.method?.apply {
                val returnIndex = findInstructionIndicesReversedOrThrow(Opcode.RETURN_OBJECT).singleOrNull()
                    ?: return@apply
                val register = getInstruction<OneRegisterInstruction>(returnIndex).registerA
                addInstruction(
                    returnIndex,
                    "invoke-static/range { v$register .. v$register }, " +
                        "$PINNED_RECIPIENTS_CLASS->onCurrentSession($USER_SESSION_CLASS)V",
                )
            }

            enableSettings("pinRecipients")
        }
    }
