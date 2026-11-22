/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.settings

import app.crimera.patches.instagram.utils.Constants.ACTIVITY_SETTINGS_CLASS
import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.crimera.patches.instagram.utils.Constants.USER_SESSION_CLASS
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.util.findInstructionIndicesReversedOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val ACCOUNT_SETTINGS_CLASS = "$ACTIVITY_SETTINGS_CLASS/AccountSettings;"

// The session manager method that makes an existing or new session the current one.
internal object SetCurrentUserSessionFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = USER_SESSION_CLASS,
    parameters = listOf("L", "L", "Ljava/lang/String;"),
    filters = listOf(
        fieldAccess(definingClass = USER_SESSION_CLASS, name = "sessionState", opcode = Opcode.IPUT_OBJECT),
        fieldAccess(definingClass = USER_SESSION_CLASS, name = "isManaged", opcode = Opcode.IPUT_BOOLEAN),
    ),
)

@Suppress("unused")
val accountSettingsPatch =
    patch(
        name = "Per-account settings",
        description = "Keeps separate settings for each logged-in account.",
        default = true,
    ) {
        dependsOn(settingsPatch)
        compatibleWith(COMPATIBILITY_INSTAGRAM)

        execute {
            SetCurrentUserSessionFingerprint.method.apply {
                val returnIndex = findInstructionIndicesReversedOrThrow(Opcode.RETURN_OBJECT).singleOrNull()
                    ?: throw PatchException("Expected a single return in the current session method")
                val register = getInstruction<OneRegisterInstruction>(returnIndex).registerA
                addInstruction(
                    returnIndex,
                    "invoke-static/range { v$register .. v$register }, " +
                        "$ACCOUNT_SETTINGS_CLASS->onCurrentSession($USER_SESSION_CLASS)V",
                )
            }

            SettingsStatusLoadFingerprint.method.addInstruction(
                0,
                "invoke-static {}, $ACCOUNT_SETTINGS_CLASS->onAppStart()V",
            )
        }
    }
