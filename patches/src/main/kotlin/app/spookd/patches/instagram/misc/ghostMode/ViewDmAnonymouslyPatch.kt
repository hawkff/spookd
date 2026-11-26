/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.ghostMode

import app.spookd.patches.instagram.misc.actionBar.chatActionBarButton.chatActionBarButtonPatch
import app.spookd.patches.instagram.misc.actionBar.inboxActionBarButton.inboxActionBarButtonPatch
import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.Constants.PATCHES_DESCRIPTOR
import app.spookd.patches.instagram.utils.Constants.USER_SESSION_CLASS
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patcher.util.getFreeRegisterProvider

@Suppress("unused")
val viewDmAnonymouslyPatch =
    patch(
        name = "View DMs anonymously",
        default = true,
    ) {
        dependsOn(settingsPatch, chatActionBarButtonPatch, inboxActionBarButtonPatch)
        compatibleWith(COMPATIBILITY_INSTAGRAM)

        execute {
            DMSeenFingerprint.method.apply {
                // The account and chat id decide whether the chosen-chats mode keeps this receipt back.
                if (parameterTypes.getOrNull(0)?.toString() != USER_SESSION_CLASS ||
                    parameterTypes.getOrNull(2)?.toString() != "Ljava/lang/String;"
                ) {
                    throw PatchException("Unexpected DM seen request parameters")
                }
                val shouldDisableRegister =
                    getFreeRegisterProvider(
                        index = 1,
                        numberOfFreeRegistersNeeded = 1,
                    ).getFreeRegister()

                addInstructionsWithLabels(
                    1,
                    """
                    invoke-static/range {p0 .. p2}, $PATCHES_DESCRIPTOR/dm/DmGhostChats;->blocksSeen(${USER_SESSION_CLASS}Ljava/lang/Object;Ljava/lang/String;)Z
                    move-result v$shouldDisableRegister
                    if-eqz v$shouldDisableRegister, :spookd_continue
                    return-void
                    """.trimIndent(),
                    ExternalLabel("spookd_continue", getInstruction(1)),
                )
            }
            enableSettings("viewDmAnonymously")
        }
    }
