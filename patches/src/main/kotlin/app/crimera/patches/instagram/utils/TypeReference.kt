/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.utils

import app.morphe.patcher.Match
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/**
 * Type referenced by a matched instruction such as a check-cast or new-instance.
 */
internal val Match.InstructionMatch.typeReference: String
    get() = instruction.getReference<TypeReference>()!!.type
