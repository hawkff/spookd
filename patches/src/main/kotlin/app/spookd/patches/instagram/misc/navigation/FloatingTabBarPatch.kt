/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.navigation

import app.spookd.patches.instagram.misc.extension.hooks.instagramInitHook
import app.spookd.patches.instagram.misc.hookFlags.hookFlagsPatch
import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.Constants.PATCHES_DESCRIPTOR
import app.spookd.patches.instagram.utils.addFlags
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.patch
import app.morphe.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode

private const val EXTENSION_CLASS = "$PATCHES_DESCRIPTOR/navigation/FloatingTabBar;"

@Suppress("unused")
val floatingTabBarPatch =
    patch(
        name = "Floating tab bar",
        description = "Turns on Instagram's floating tab bar over the feed and slides it away while you scroll the feed down.",
        default = true,
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch, hookFlagsPatch)

        execute {
            addFlags("floatingTabBarFlags")

            instagramInitHook.fingerprint.method.apply {
                val superIndex = indexOfFirstInstructionOrThrow(Opcode.INVOKE_SUPER)
                addInstruction(superIndex + 1, "invoke-static {}, $EXTENSION_CLASS->init()V")
            }

            enableSettings("floatingTabBar")
        }
    }
