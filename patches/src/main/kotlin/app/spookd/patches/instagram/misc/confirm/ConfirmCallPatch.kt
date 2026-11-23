/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.confirm

import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.patch
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val STRING = "Ljava/lang/String;"

// Static click of a chat header button; it switches on the button's type enum.
private object ThreadHeaderButtonClickFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/view/View;", "L", "L"),
    filters = listOf(
        string("Does not use this click handler"),
        string("standalone_video"),
        string("standalone_audio"),
    ),
)

@Suppress("unused")
val confirmCallPatch =
    patch(
        name = "Confirm calls",
        description = "Adds an option to ask before the audio or video call button at the top of a chat starts a call.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch)

        execute {
            val method = ThreadHeaderButtonClickFingerprint.matchOrNull()?.method ?: return@execute
            if (!AccessFlags.STATIC.isSet(method.accessFlags)) return@execute
            val button = method.parameterTypes[1].toString()
            val instructions = method.implementation!!.instructions
            val typeRead = instructions.indexOfFirst {
                it.opcode == Opcode.IGET_OBJECT &&
                    ((it as ReferenceInstruction).reference as FieldReference).definingClass == button
            }
            if (typeRead < 0) return@execute
            val ordinal = (instructions.getOrNull(typeRead + 1) as? ReferenceInstruction)?.reference as? MethodReference
            if (ordinal?.definingClass != "Ljava/lang/Enum;" || ordinal?.name != "ordinal") return@execute
            val type = (instructions[typeRead] as ReferenceInstruction).reference as FieldReference

            val added = method.addConfirmGate(
                4,
                """
                move-object/from16 v1, p1
                iget-object v1, v1, ${type.definingClass}->${type.name}:${type.type}
                filled-new-array/range {p0 .. p2}, [Ljava/lang/Object;
                move-result-object v0
                const-class v2, ${method.definingClass}
                const-string v3, "${method.name}"
                invoke-static {v0, v1, v2, v3}, $ACTION_CONFIRM->allowCall([Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Class;$STRING)Z
                move-result v0
                """,
            )
            if (added) enableSettings("confirmCall")
        }
    }
