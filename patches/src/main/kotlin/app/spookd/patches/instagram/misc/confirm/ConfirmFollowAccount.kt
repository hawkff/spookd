/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.confirm

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.classDefByOrNull
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val STRING = "Ljava/lang/String;"

/**
 * At the start of the follow button's bind (this method), reports the account id its helper was just
 * bound to: the helper's bind stores that id first and then calls this method. False when not found.
 */
internal fun MutableMethod.addFollowAccountHook(button: String): Boolean {
    val getHelper = classDefByOrNull(button)?.methods?.singleOrNull {
        it.name == "getHelper" && it.parameterTypes.isEmpty() && !AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: return false
    val helper = getHelper.returnType
    val bindName = name
    val bindTypes = parameterTypes.map(CharSequence::toString)
    val helperBind = classDefByOrNull(helper)?.methods?.singleOrNull { method ->
        method.implementation?.instructions?.any {
            val call = (it as? ReferenceInstruction)?.reference as? MethodReference
            call?.name == bindName && call?.parameterTypes?.map(CharSequence::toString) == bindTypes
        } == true
    } ?: return false
    val account = helperBind.implementation!!.instructions.firstNotNullOfOrNull {
        val field = (it as? ReferenceInstruction)?.reference as? FieldReference
        field?.takeIf { f -> it.opcode == Opcode.IPUT_OBJECT && f.definingClass == helper && f.type == STRING }
    } ?: return false
    val parameterRegisters = parameterTypes.sumOf { if (it.toString() == "J" || it.toString() == "D") 2 else 1 } + 1
    if (implementation!!.registerCount - parameterRegisters < 2) return false

    addInstructionsWithLabels(
        0,
        """
        move-object/from16 v1, p0
        invoke-virtual {v1}, $button->getHelper()$helper
        move-result-object v0
        if-eqz v0, :follow_account_unknown
        iget-object v0, v0, $helper->${account.name}:$STRING
        invoke-static {v1, v0}, $ACTION_CONFIRM->rememberFollowAccount(Landroid/view/View;$STRING)V
        """.trimIndent(),
        ExternalLabel("follow_account_unknown", getInstruction(0)),
    )
    return true
}
