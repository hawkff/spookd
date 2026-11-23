/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.overflowMenuButton.posts

import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.Constants.FEED_OVERFLOW_MENU_BUTTON_CLASS
import app.spookd.patches.instagram.utils.Constants.MEDIA_OPTIONS_CLASS
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.patch
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

@Suppress("unused")
val includeButtonsInOverflowMenuArrayPatch =
    patch(
        description = "This patch hooks array values initialisation in overflow menu button constructor.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        execute {
            OverflowMenuButtonEnumInitialiser.let {
                val valuesIndex = it.instructionMatches.last().index
                val arrayRegister = it.instructionMatches.last().getInstruction<OneRegisterInstruction>().registerA

                it.method.addInstructions(
                    valuesIndex,
                    """
                    invoke-static {}, $FEED_OVERFLOW_MENU_BUTTON_CLASS->addToMenuOptionArray()[$MEDIA_OPTIONS_CLASS
                    move-result-object v$arrayRegister
                    """.trimIndent(),
                )
            }
        }
    }
