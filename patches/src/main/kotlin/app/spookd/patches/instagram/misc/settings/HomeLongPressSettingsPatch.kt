/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.settings

import app.crimera.patches.common.parameterRegisterStart
import app.spookd.patches.instagram.misc.internalStuffs.developerOptions.HomeIconOnClickListenerFingerprint
import app.spookd.patches.instagram.misc.internalStuffs.developerOptions.unlockDeveloperOptionPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.Constants.PATCHES_DESCRIPTOR
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchFirst
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode

// Instagram's Search tab long-press: select the Search tab, then open the search screen.
internal object SearchTabLongClickFingerprint : Fingerprint(
    name = "onLongClick",
    returnType = "Z",
    parameters = listOf("Landroid/view/View;"),
    filters = listOf(
        fieldAccess(type = "Lcom/instagram/mainactivity/InstagramMainActivity;", opcode = Opcode.IGET_OBJECT, location = MatchFirst()),
        opcode(Opcode.SGET_OBJECT, location = MatchAfterImmediately()),
        methodCall(definingClass = "Lcom/instagram/mainactivity/InstagramMainActivity;", returnType = "V", location = MatchAfterImmediately()),
        methodCall(name = "getLifecycle", location = MatchAfterImmediately()),
    ),
)

@Suppress("unused")
val homeLongPressSettingsPatch =
    patch(
        name = "Open settings from Home",
        description = "Opens spookd settings when you long-press the Home tab, instead of a settings button on your profile. " +
            "Long-pressing the Search tab opens a copied Instagram link or @username inside the app; otherwise Search behaves as usual",
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
            // Optional: a miss leaves Instagram's Search long-press and the Home gesture untouched.
            SearchTabLongClickFingerprint.matchOrNull()?.method?.apply {
                val viewRegister = parameterRegisterStart(this) + 1
                val resultRegister = findFreeRegister(0, viewRegister)

                addInstructionsWithLabels(
                    0,
                    """
                        invoke-static/range {v$viewRegister .. v$viewRegister}, $PATCHES_DESCRIPTOR/CopiedLink;->onSearchLongClick(Landroid/view/View;)Z
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
