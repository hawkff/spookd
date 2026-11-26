/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.navigation

import app.spookd.patches.instagram.misc.hookFlags.hookFlagsPatch
import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.Constants.PATCHES_DESCRIPTOR
import app.spookd.patches.instagram.utils.Constants.USER_SESSION_CLASS
import app.spookd.patches.instagram.utils.addFlags
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

// Instagram's final check whether the main tabs swipe: a stored opt-in or opt-out, the 79665::16 flag, then the newer home layout.
private object SwipeableTabsCheckFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf(USER_SESSION_CLASS),
    filters = listOf(
        literal(0x2081080a00102a78L),
        methodCall(opcode = Opcode.INVOKE_STATIC, parameters = listOf(USER_SESSION_CLASS), returnType = "Z"),
    ),
)

@Suppress("unused")
val swipeBetweenTabsPatch =
    patch(
        name = "Swipe between tabs",
        description = "Adds an option to turn Instagram's own swiping between the main tabs on or off, or leave it to Instagram.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch, hookFlagsPatch)

        execute {
            // The flag keeps other readers of it in line; the check below has the final say.
            addFlags("swipeBetweenTabsFlags")
            SwipeableTabsCheckFingerprint.method.apply {
                val returns = instructions.withIndex().filter { it.value.opcode == Opcode.RETURN }.map { it.index }
                if (returns.isEmpty()) throw PatchException("Swipeable tabs check has no return")
                for (index in returns.reversed()) {
                    val result = getInstruction<OneRegisterInstruction>(index).registerA
                    addInstructionsAtControlFlowLabel(
                        index,
                        """
                        invoke-static/range {v$result .. v$result}, $PATCHES_DESCRIPTOR/navigation/TabSwipe;->swipeTabs(Z)Z
                        move-result v$result
                        """.trimIndent(),
                    )
                }
            }
            enableSettings("swipeBetweenTabs")
        }
    }
