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
import app.spookd.patches.instagram.utils.Constants.USER_SESSION_CLASS
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchFirst
import app.morphe.patcher.Match
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.addInstructionsAtControlFlowLabel
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.util.findInstructionIndicesReversed
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val MAIN_ACTIVITY = "Lcom/instagram/mainactivity/InstagramMainActivity;"
private const val TAB_SHORTCUTS = "$PATCHES_DESCRIPTOR/navigation/TabShortcuts;"
private const val LONG_CLICK_LISTENER = "Landroid/view/View\$OnLongClickListener;"

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

// Instagram's tab button builder; the view it returns carries each tab's long-press listener.
internal object CreateTabButtonFingerprint : Fingerprint(
    definingClass = MAIN_ACTIVITY,
    returnType = "Landroid/view/View;",
    strings = listOf("InstagramMainActivity.createTabButton("),
    filters = listOf(
        methodCall(opcode = Opcode.INVOKE_VIRTUAL, parameters = listOf(), returnType = "Landroid/view/View;"),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
    ),
)

// The Profile tab's hold gesture, which opens Instagram's account switcher.
internal object ProfileTabLongPressFingerprint : Fingerprint(
    name = "onLongPress",
    returnType = "V",
    parameters = listOf("Landroid/view/MotionEvent;"),
    filters = listOf(
        fieldAccess(type = MAIN_ACTIVITY, opcode = Opcode.IGET_OBJECT, location = MatchFirst()),
        fieldAccess(
            definingClass = MAIN_ACTIVITY,
            type = USER_SESSION_CLASS,
            opcode = Opcode.IGET_OBJECT,
            location = MatchAfterImmediately(),
        ),
        opcode(Opcode.IF_EQZ, location = MatchAfterImmediately()),
    ),
)

/** Hooks every tab but Home; false, with nothing changed, when either hook point has an unexpected shape. */
private fun installTabShortcuts(tabButton: Match, profileHold: MutableMethod): Boolean {
    val builder = tabButton.method
    val viewCall = tabButton.instructionMatches[0]
    val viewResult = tabButton.instructionMatches[1]
    val tabButtonView = viewCall.methodReference
    val tabButtonRegister = viewCall.getInstruction<FiveRegisterInstruction>().registerC
    val viewRegister = viewResult.getInstruction<OneRegisterInstruction>().registerA
    val insertIndex = viewResult.index + 1
    val tabRegister = builder.findFreeRegister(insertIndex, listOf(viewRegister, tabButtonRegister))
    // The tab enum, the builder's third parameter, as the tab button keeps it.
    val tabField = classDefBy(tabButtonView.definingClass).fields
        .singleOrNull { it.type == builder.parameterTypes[2].toString() }
    // Every place the builder sets a tab's long-press listener, last first so earlier indices stay valid.
    val listenerSetters = builder.findInstructionIndicesReversed(
        methodCall(
            definingClass = tabButtonView.definingClass,
            parameters = listOf(LONG_CLICK_LISTENER),
            returnType = "V",
            opcode = Opcode.INVOKE_VIRTUAL,
        ),
    ).map { index ->
        val call = builder.getInstruction<FiveRegisterInstruction>(index)
        Triple(index, call, builder.findFreeRegister(index, listOf(call.registerC, call.registerD)))
    }
    val profileTabButtonField = classDefBy(profileHold.definingClass).fields
        .singleOrNull { it.type == tabButtonView.definingClass }
    // The first instruction overwrites its target with an activity read from the gesture listener.
    val activityRead = profileHold.getInstruction<TwoRegisterInstruction>(0)
    val scratchRegister = activityRead.registerA
    val listenerRegister = activityRead.registerB
    if (tabField == null || profileTabButtonField == null || scratchRegister == listenerRegister ||
        listenerSetters.isEmpty() || listenerSetters.any { (index, _, _) -> index >= insertIndex } ||
        listOf(tabButtonRegister, viewRegister, tabRegister, scratchRegister, listenerRegister).any { it > 0xf } ||
        listenerSetters.any { (_, call, scratch) -> listOf(call.registerC, call.registerD, scratch).any { it > 0xf } }
    ) {
        return false
    }

    builder.addInstructions(
        insertIndex,
        """
            iget-object v$tabRegister, v$tabButtonRegister, $tabField
            invoke-static {v$viewRegister, v$tabRegister}, $TAB_SHORTCUTS->onTabCreated(Landroid/view/View;Ljava/lang/Object;)V
        """.trimIndent(),
    )
    // Wrapped, not replaced, so Default and a failed shortcut still reach Instagram's own long-press.
    listenerSetters.forEach { (index, call, scratch) ->
        builder.addInstructionsAtControlFlowLabel(
            index,
            """
                iget-object v$scratch, v${call.registerC}, $tabField
                invoke-static {v${call.registerD}, v$scratch}, $TAB_SHORTCUTS->wrapLongClick(${LONG_CLICK_LISTENER}Ljava/lang/Object;)$LONG_CLICK_LISTENER
                move-result-object v${call.registerD}
            """.trimIndent(),
        )
    }
    profileHold.addInstructionsWithLabels(
        0,
        """
            iget-object v$scratchRegister, v$listenerRegister, $profileTabButtonField
            invoke-virtual {v$scratchRegister}, $tabButtonView
            move-result-object v$scratchRegister
            invoke-static {v$scratchRegister}, $TAB_SHORTCUTS->onProfileLongPress(Landroid/view/View;)Z
            move-result v$scratchRegister
            if-eqz v$scratchRegister, :original
            return-void
        """.trimIndent(),
        ExternalLabel("original", profileHold.getInstruction(0)),
    )
    return true
}

@Suppress("unused")
val homeLongPressSettingsPatch =
    patch(
        name = "Open settings from Home",
        description = "Opens spookd settings when you long-press the Home tab, instead of a settings button on your profile. " +
            "Long-pressing the Search tab opens a copied Instagram link or @username inside the app; otherwise Search behaves as usual. " +
            "In Navigation bar settings, each tab can open a shortcut of your choice when you hold it",
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
            // Optional, both or neither: a miss leaves the other tabs and Profile's account switcher to Instagram.
            val tabButton = CreateTabButtonFingerprint.matchOrNull()
            val profileHold = ProfileTabLongPressFingerprint.matchOrNull()?.method
            val tabShortcuts = tabButton != null && profileHold != null && installTabShortcuts(tabButton, profileHold)
            enableSettings("homeLongPressSettings")
            if (tabShortcuts) enableSettings("tabShortcuts")
        }
    }
