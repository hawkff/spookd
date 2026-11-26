/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.navigation

import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.Constants.PATCHES_DESCRIPTOR
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.string
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val REFRESH_CONTROL = "$PATCHES_DESCRIPTOR/navigation/RefreshControl;"

// Home's scroll to top with a reason; after scrolling it reloads only when the cooldown check passes.
internal object HomeScrollToTopFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        string("MainFeedFragment.scrollToTopWithReason."),
        string("refresh_cooldown_ms"),
        string("pass_cooldown_check"),
    ),
)

// The alarm that reloads Home while the app is in the background.
private object HomeBackgroundRefreshAlarmFingerprint : Fingerprint(
    name = "onAlarm",
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(
        string("bg_refresh_fired"),
        string("ms_since_backgrounded"),
    ),
)

// The Reels tab's scroll to top; it hands over to the shown Reels viewer.
private object ReelsTabScrollToTopFingerprint : Fingerprint(
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(
        string("android_purge_26_q2_ClipsTabFragment_scrollToTop"),
        methodCall(opcode = Opcode.INVOKE_INTERFACE, parameters = emptyList(), returnType = "V"),
    ),
)

private object ClipsViewerFragmentFingerprint : Fingerprint(
    filters = listOf(string("ClipsViewerFragment.onCreate:storiesController")),
)

@Suppress("unused")
val refreshControlPatch =
    patch(
        name = "Refresh controls",
        description = "Adds options to stop Home and Reels from reloading when you tap their tab again, " +
            "and Home from reloading while the app is in the background.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch)

        execute {
            HomeScrollToTopFingerprint.let {
                val method = it.method
                val check = it.instructionMatches[2].index - 1
                val branch = method.implementation!!.instructions[check]
                if (branch.opcode != Opcode.IF_EQZ) throw PatchException("Home reload check not found")
                val refresh = (branch as OneRegisterInstruction).registerA
                // The reason is p1, the last register of this one-parameter instance method.
                val reason = method.implementation!!.registerCount - 1
                if (refresh > 15 || reason > 15) throw PatchException("Home reload check uses high registers")
                method.addInstructions(
                    check,
                    """
                    invoke-static {v$refresh, p1}, $REFRESH_CONTROL->allowHomeScrollRefresh(ZLjava/lang/Object;)Z
                    move-result v$refresh
                    """.trimIndent(),
                )
            }

            HomeBackgroundRefreshAlarmFingerprint.method.apply {
                if (implementation!!.registerCount < 2) throw PatchException("Home background refresh has no free register")
                addInstructionsWithLabels(
                    0,
                    """
                    invoke-static {}, $REFRESH_CONTROL->allowHomeBackgroundRefresh()Z
                    move-result v0
                    if-nez v0, :refresh_allowed
                    return-void
                    """.trimIndent(),
                    ExternalLabel("refresh_allowed", getInstruction(0)),
                )
            }

            val scrollToTop = ReelsTabScrollToTopFingerprint.instructionMatches[1].instruction
            val viewerScrollToTop = (scrollToTop as ReferenceInstruction).reference as MethodReference
            val viewerClass = ClipsViewerFragmentFingerprint.originalClassDef
            if (viewerScrollToTop.definingClass !in viewerClass.interfaces) {
                throw PatchException("Reels viewer does not handle the tab's scroll to top")
            }
            mutableClassDefBy(viewerClass.type).methods.single {
                it.name == viewerScrollToTop.name && it.parameterTypes.isEmpty() && it.returnType == "V"
            }.apply {
                val instructions = implementation!!.instructions
                // The first check is whether a reload is allowed: Boolean.booleanValue(), compared with const/4 1.
                val check = instructions.indexOfFirst { it.opcode == Opcode.IF_NE }
                val one = instructions.getOrNull(check - 1)
                val result = instructions.getOrNull(check - 2)
                val unbox = (instructions.getOrNull(check - 3) as? ReferenceInstruction)?.reference as? MethodReference
                val compared = (instructions.getOrNull(check) as? TwoRegisterInstruction)
                val refresh = compared?.registerA ?: -1
                if (check < 3 || one?.opcode != Opcode.CONST_4 || (one as NarrowLiteralInstruction).narrowLiteral != 1 ||
                    (one as OneRegisterInstruction).registerA != compared!!.registerB ||
                    result?.opcode != Opcode.MOVE_RESULT || (result as OneRegisterInstruction).registerA != refresh ||
                    unbox?.definingClass != "Ljava/lang/Boolean;" || unbox?.name != "booleanValue" || refresh > 15
                ) {
                    throw PatchException("Reels tab reload check not found")
                }
                addInstructions(
                    check - 1,
                    """
                    invoke-static {v$refresh}, $REFRESH_CONTROL->allowReelsTabRefresh(Z)Z
                    move-result v$refresh
                    """.trimIndent(),
                )
            }

            enableSettings("refreshControls")
        }
    }
