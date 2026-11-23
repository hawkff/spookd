/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.confirm

import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.Constants.PATCHES_DESCRIPTOR
import app.spookd.patches.instagram.utils.Constants.USER_SESSION_CLASS
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.classDefByOrNull
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

internal const val ACTION_CONFIRM = "$PATCHES_DESCRIPTOR/confirm/ActionConfirm;"

private const val FOLLOW_BUTTON = "Lcom/instagram/user/follow/FollowButtonBase;"
private const val STRING = "Ljava/lang/String;"
private const val CLICK_LISTENER = "Landroid/view/View\$OnClickListener;"

private object FollowButtonTextTypeFingerprint : Fingerprint(
    definingClass = FOLLOW_BUTTON,
    name = "getCurrentTextType",
    returnType = "L",
    parameters = emptyList(),
)

// Binds the button to an account's follow state; the String is the name its label is built from.
private object FollowButtonBindFingerprint : Fingerprint(
    definingClass = FOLLOW_BUTTON,
    returnType = "V",
    parameters = listOf("L", USER_SESSION_CLASS, STRING, "Z", "Z", "Z", "Z", "Z"),
)

/**
 * Gives [classType] a performClick that runs [gate] first, which leaves true in v0 to let the click
 * through, and a setOnClickListener that reports each bind. False when the class is missing or
 * already has either.
 */
internal fun addConfirmedPerformClick(classType: String, gate: String): Boolean {
    val classDef = classDefByOrNull(classType) ?: return false
    val superclass = classDef.superclass ?: return false
    if (classDef.methods.any {
            (it.name == "performClick" && it.parameterTypes.isEmpty()) ||
                (it.name == "setOnClickListener" && it.parameterTypes.map(CharSequence::toString) == listOf(CLICK_LISTENER))
        }) {
        return false
    }
    val performClick = ImmutableMethod(
        classType, "performClick", emptyList(), "Z", AccessFlags.PUBLIC.value,
        null, null, MutableMethodImplementation(2),
    ).toMutable()
    performClick.addInstructions(
        0,
        gate.trimIndent() + "\n" +
            """
            if-eqz v0, :confirm_pending
            invoke-super {p0}, $superclass->performClick()Z
            move-result v0
            return v0
            :confirm_pending
            const/4 v0, 0x1
            return v0
            """.trimIndent(),
    )
    val setOnClickListener = ImmutableMethod(
        classType, "setOnClickListener", listOf(ImmutableMethodParameter(CLICK_LISTENER, emptySet(), null)), "V",
        AccessFlags.PUBLIC.value, null, null, MutableMethodImplementation(2),
    ).toMutable()
    setOnClickListener.addInstructions(
        0,
        """
        invoke-static {p0}, $ACTION_CONFIRM->onClickListenerSet(Landroid/view/View;)V
        invoke-super {p0, p1}, $superclass->setOnClickListener($CLICK_LISTENER)V
        return-void
        """.trimIndent(),
    )
    mutableClassDefBy(classType).methods.apply {
        add(performClick)
        add(setOnClickListener)
    }
    return true
}

/**
 * Runs [gate] at the start of the method, which leaves true in v0 to go on and may use v0 up to
 * v[locals] - 1; otherwise the method returns. False when the method has fewer free registers.
 */
internal fun MutableMethod.addConfirmGate(locals: Int, gate: String): Boolean {
    val registers = implementation?.registerCount ?: return false
    val parameterRegisters = parameterTypes.sumOf { if (it.toString() == "J" || it.toString() == "D") 2 else 1 } +
        if (AccessFlags.STATIC.isSet(accessFlags)) 0 else 1
    if (registers - parameterRegisters < locals) return false
    addInstructionsWithLabels(
        0,
        gate.trimIndent() + "\n" +
            """
            if-nez v0, :confirm_allowed
            return-void
            """.trimIndent(),
        ExternalLabel("confirm_allowed", getInstruction(0)),
    )
    return true
}

@Suppress("unused")
val confirmFollowPatch =
    patch(
        name = "Confirm follows",
        description = "Adds an option to ask before following an account with a Follow button.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch)

        execute {
            val textType = FollowButtonTextTypeFingerprint.matchOrNull()?.method ?: return@execute
            // Called with invoke-virtual and read with move-result-object below.
            if (AccessFlags.STATIC.isSet(textType.accessFlags) || AccessFlags.PRIVATE.isSet(textType.accessFlags) ||
                !textType.returnType.startsWith("L")
            ) {
                return@execute
            }
            val added = addConfirmedPerformClick(
                FOLLOW_BUTTON,
                """
                invoke-virtual {p0}, $FOLLOW_BUTTON->getCurrentTextType()${textType.returnType}
                move-result-object v0
                invoke-static {p0, v0}, $ACTION_CONFIRM->allowFollowClick(Landroid/view/View;Ljava/lang/Object;)Z
                move-result v0
                """,
            )
            if (!added) return@execute

            // Without it the dialog names no account.
            FollowButtonBindFingerprint.matchOrNull()?.method?.let { bind ->
                bind.addInstruction(
                    0,
                    "invoke-static/range {p0 .. p3}, $ACTION_CONFIRM->rememberFollowName(Landroid/view/View;Ljava/lang/Object;Ljava/lang/Object;$STRING)V",
                )
                // Without it any rebind while the dialog is open drops an approved follow.
                bind.addFollowAccountHook(FOLLOW_BUTTON)
            }

            enableSettings("confirmFollow")
        }
    }
