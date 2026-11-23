/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.dm

import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.Constants.PATCHES_DESCRIPTOR
import app.spookd.patches.instagram.utils.Constants.USER_SESSION_CLASS
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patcher.string
import app.morphe.patcher.util.smali.ExternalLabel

private const val EXTENSION_CLASS = "$PATCHES_DESCRIPTOR/dm/HiddenChatsShare;"
private const val SHARE_SHEET_CLASS = "Linstagram/features/direct/fragment/sharesheet/DirectShareSheetFragment;"

// Gives the share sheet the recipients it shows before anything is typed; search results take another path.
private object ShareSheetDefaultRecipientsFingerprint : Fingerprint(
    definingClass = SHARE_SHEET_CLASS,
    isStatic = true,
    returnType = "V",
    parameters = listOf(SHARE_SHEET_CLASS, "Ljava/lang/String;", "Ljava/util/List;"),
    filters = listOf(
        string("reshare_share_sheet"),
        string("add_to_channel_button"),
    ),
)

// Builds the rows of the recipient pickers, like the new message screen; its class is the only one with this row key.
private object RecipientRowsFingerprint : Fingerprint(
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(string("_community_row_")),
)

// Puts one recipient into the sections of a picker; the flag is true for a suggestion and false for a search result.
private object RecipientAddFingerprint : Fingerprint(
    classFingerprint = RecipientRowsFingerprint,
    returnType = "V",
    parameters = listOf("Lcom/instagram/model/direct/DirectShareTarget;", "Z"),
)

@Suppress("unused")
val hiddenChatsSharePatch =
    patch(
        name = "Hide hidden chats from share sheet",
        description = "Leaves hidden chats out of the share sheet's suggested recipients and the new message suggestions.",
        default = true,
    ) {
        dependsOn(hiddenChatsPatch)
        compatibleWith(COMPATIBILITY_INSTAGRAM)

        execute {
            // The parameters are above v15 and nothing is live yet, so v0 and v1 carry them.
            ShareSheetDefaultRecipientsFingerprint.method.addInstructions(
                0,
                """
                invoke-virtual/range {p0 .. p0}, $SHARE_SHEET_CLASS->getSession()$USER_SESSION_CLASS
                move-result-object v0
                move-object/from16 v1, p2
                invoke-static {v1, v0}, $EXTENSION_CLASS->filterTargets(Ljava/util/List;Ljava/lang/Object;)Ljava/util/List;
                move-result-object v1
                move-object/from16 p2, v1
                """.trimIndent(),
            )

            val session = RecipientRowsFingerprint.classDef.fields.first { it.type == USER_SESSION_CLASS }
            RecipientAddFingerprint.method.apply {
                addInstructionsWithLabels(
                    0,
                    """
                    iget-object v0, p0, $session
                    invoke-static {v0, p1, p2}, $EXTENSION_CLASS->skipRecipient(Ljava/lang/Object;Ljava/lang/Object;Z)Z
                    move-result v0
                    if-eqz v0, :spookd_recipient
                    return-void
                    """.trimIndent(),
                    ExternalLabel("spookd_recipient", getInstruction(0)),
                )
            }
        }
    }
