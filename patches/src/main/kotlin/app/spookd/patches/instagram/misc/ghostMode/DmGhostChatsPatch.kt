/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.ghostMode

import app.spookd.patches.instagram.misc.dm.MenuButtonActionFingerprint
import app.spookd.patches.instagram.misc.dm.MenuButtonsEnumFingerprint
import app.spookd.patches.instagram.misc.dm.MenuButtonsLabelsFingerprint
import app.spookd.patches.instagram.misc.dm.MenuOpenFingerprint
import app.spookd.patches.instagram.misc.dm.passReturnedValues
import app.spookd.patches.instagram.misc.settings.accountSettingsPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.Constants.PATCHES_DESCRIPTOR
import app.spookd.patches.instagram.utils.Constants.USER_SESSION_CLASS
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.p0Register
import app.morphe.patcher.util.smali.ExternalLabel

private const val DM_GHOST_CHATS_CLASS = "$PATCHES_DESCRIPTOR/dm/DmGhostChats;"
private const val THREAD_KEY_CLASS = "Lcom/instagram/model/direct/DirectThreadKey;"

@Suppress("unused")
val dmGhostChatsPatch =
    patch(
        name = "Choose chats for DM ghost",
        description = "Adds an option to the chat menu in the inbox to choose chats, and a setting to view only the chosen chats anonymously, or all chats except them.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(viewDmAnonymouslyPatch, accountSettingsPatch)

        execute {
            MenuOpenFingerprint.method.apply {
                val list = parameters.indexOfFirst { it.type == "Ljava/util/List;" }
                val menu = parameters.indexOfFirst { it.type == MenuButtonsLabelsFingerprint.classDef.type }
                val key = parameters.indexOfFirst { it.type == THREAD_KEY_CLASS }
                // The title the menu shows for the chat, which names it in the list of chosen chats.
                val title = parameters.indexOfFirst { it.type == "Ljava/lang/String;" }
                if (list < 0 || menu < 0 || key < 0 || title < 0) {
                    throw PatchException("Chat menu opener has no list, menu, chat key or title")
                }
                // The method is static and its parameters sit far above v0, so they are copied down to be passed on.
                addInstructions(
                    0,
                    """
                    move-object/from16 v0, p$list
                    move-object/from16 v1, p$menu
                    move-object/from16 v2, p$key
                    move-object/from16 v3, p$title
                    invoke-static {v0, v1, v2, v3}, $DM_GHOST_CHATS_CLASS->menuOpening(Ljava/util/List;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;)Ljava/util/List;
                    move-result-object v0
                    move-object/from16 p$list, v0
                    """.trimIndent(),
                )
            }

            MenuButtonsLabelsFingerprint.method.apply {
                val list = p0Register + parameters.indexOfFirst { it.type == "Ljava/util/List;" }
                if (passReturnedValues(list, "$DM_GHOST_CHATS_CLASS->menuLabels(Ljava/util/List;[Ljava/lang/String;)[Ljava/lang/String;") == 0) {
                    throw PatchException("Chat menu labels have no return to hook")
                }
            }

            val buttonEnum = MenuButtonsEnumFingerprint.classDef.type
            MenuButtonActionFingerprint.apply {
                val session = classDef.fields.first { it.type == USER_SESSION_CLASS }
                method.apply {
                    val button = parameters.indexOfFirst { it.type == buttonEnum } + 1
                    val key = parameters.indexOfFirst { it.type == THREAD_KEY_CLASS } + 1
                    if (button < 1 || key < 1) throw PatchException("Chat menu press has no button or chat key")
                    // Registers are fixed because the code goes first and nothing is live yet.
                    addInstructionsWithLabels(
                        0,
                        """
                        move-object/from16 v0, p0
                        iget-object v1, v0, $session
                        move-object/from16 v2, p$button
                        move-object/from16 v3, p$key
                        invoke-static {v1, v2, v3}, $DM_GHOST_CHATS_CLASS->onMenuButton(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z
                        move-result v0
                        if-eqz v0, :spookd_dm_ghost_menu
                        return-void
                        """.trimIndent(),
                        ExternalLabel("spookd_dm_ghost_menu", getInstruction(0)),
                    )
                }
            }

            enableSettings("dmGhostChats")
        }
    }
