/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.settings

import app.crimera.patches.common.parameterRegisterStart
import app.crimera.patches.instagram.misc.internalStuffs.developerOptions.HomeIconOnClickListenerFingerprint
import app.crimera.patches.instagram.misc.internalStuffs.developerOptions.unlockDeveloperOptionPatch
import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.crimera.patches.instagram.utils.Constants.PATCHES_DESCRIPTOR
import app.crimera.patches.instagram.utils.enableSettings
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patcher.util.smali.ExternalLabel

@Suppress("unused")
val homeLongPressSettingsPatch =
    patch(
        name = "Open settings from Home",
        description = "Opens Piko settings when you long-press the Home tab, instead of a settings button on your profile",
        default = true,
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        // The developer options patch inserts at matched indices, so it must run before this insert at index 0.
        dependsOn(settingsPatch, unlockDeveloperOptionPatch)
        execute {
            HomeIconOnClickListenerFingerprint.method.apply {
                if (returnType != "Z" || parameterTypes.map { it.toString() } != listOf("Landroid/view/View;")) {
                    throw PatchException("Home long-click listener is not onLongClick(View)")
                }
                val viewRegister = parameterRegisterStart(this) + 1
                val resultRegister = findFreeRegister(0, viewRegister)

                addInstructionsWithLabels(
                    0,
                    """
                        invoke-static/range {v$viewRegister .. v$viewRegister}, $PATCHES_DESCRIPTOR/HomeLongPress;->onLongClick(Landroid/view/View;)Z
                        move-result v$resultRegister
                        if-eqz v$resultRegister, :original
                        return v$resultRegister
                    """.trimIndent(),
                    ExternalLabel("original", getInstruction(0)),
                )
            }
            enableSettings("homeLongPressSettings")
        }
    }
