/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.dm.mediaVault

import app.crimera.patches.instagram.utils.Constants.ENTITY_CLASS
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode

private const val DIRECT_ITEM_WRAPPERS_CLASS = "$ENTITY_CLASS/DirectItemWrappers;"

// --- Extension getters whose placeholder strings are rewritten at patch time. ---
internal object ItemClassNameExtension : Fingerprint(name = "itemClassName", definingClass = DIRECT_ITEM_WRAPPERS_CLASS)
internal object VisualFieldsExtension : Fingerprint(name = "visualFields", definingClass = DIRECT_ITEM_WRAPPERS_CLASS)
internal object VoiceFieldsExtension : Fingerprint(name = "voiceFields", definingClass = DIRECT_ITEM_WRAPPERS_CLASS)
internal object VisualModeFieldExtension : Fingerprint(name = "visualModeField", definingClass = DIRECT_ITEM_WRAPPERS_CLASS)
internal object VoiceModeFieldExtension : Fingerprint(name = "voiceModeField", definingClass = DIRECT_ITEM_WRAPPERS_CLASS)

/** Index in [wrapperSerializerFingerprint] of the String field written under "view_mode". */
internal const val VIEW_MODE_FIELD_FILTER = 4

/**
 * Static JSON serializer of the DM media wrapper [wrapperType].
 */
internal fun wrapperSerializerFingerprint(wrapperType: String) = Fingerprint(
    returnType = "V",
    parameters = listOf("L", wrapperType),
    filters = listOf(
        string("seen_count"),
        fieldAccess(definingClass = wrapperType, type = "I", opcode = Opcode.IGET, location = MatchAfterImmediately()),
        fieldAccess(definingClass = wrapperType, type = "Ljava/lang/Long;", opcode = Opcode.IGET_OBJECT),
        string("url_expire_at_secs", location = MatchAfterWithin(4)),
        fieldAccess(definingClass = wrapperType, type = "Ljava/lang/String;", opcode = Opcode.IGET_OBJECT),
        string("view_mode", location = MatchAfterWithin(2)),
    ),
)
