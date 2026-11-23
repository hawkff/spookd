/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.utils

import app.spookd.patches.instagram.misc.settings.HookFlagsLoadFingerprint
import app.spookd.patches.instagram.misc.settings.SettingsStatusLoadFingerprint
import app.spookd.patches.instagram.utils.Constants.LOAD_FLAGS_DESCRIPTOR
import app.spookd.patches.instagram.utils.Constants.SSTS_DESCRIPTOR
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

fun enableSettings(functionName: String) {
    SettingsStatusLoadFingerprint.method.addInstruction(
        0,
        SSTS_DESCRIPTOR.format(functionName),
    )
}

fun addFlags(functionName: String) {
    HookFlagsLoadFingerprint.method.addInstruction(
        0,
        LOAD_FLAGS_DESCRIPTOR.format(functionName),
    )
}
