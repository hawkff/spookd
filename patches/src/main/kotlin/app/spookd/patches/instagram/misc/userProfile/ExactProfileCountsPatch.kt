/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.userProfile

import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.Constants.PATCHES_DESCRIPTOR
import app.spookd.patches.instagram.utils.Constants.USER_SESSION_CLASS
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction

private const val RESOURCES = "Landroid/content/res/Resources;"
private const val INTEGER = "Ljava/lang/Integer;"
private const val STRING = "Ljava/lang/String;"

// Profile header helper that binds one count's label and value.
internal object ProfileMetricBinderFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(
        "Landroid/content/Context;", "Landroid/view/View\$OnTouchListener;", "Landroid/view/View;",
        "Landroid/widget/TextView;", "Landroid/widget/TextView;", USER_SESSION_CLASS,
        INTEGER, STRING, "Lkotlin/jvm/functions/Function0;", "Z",
    ),
    filters = listOf(
        // Count formatter of the server-flagged branch.
        methodCall(returnType = STRING, parameters = listOf(RESOURCES, INTEGER)),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        // Count formatter of the default branch, which abbreviates from 10,000.
        methodCall(returnType = STRING, parameters = listOf(RESOURCES, INTEGER, "I", "Z", "Z", "Z")),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        string("UserMetricsBinderHelper.shrinkVanityMetrics"),
    ),
)

@Suppress("unused")
val exactProfileCountsPatch =
    patch(
        name = "Exact profile counts",
        description = "Shows the full number of posts, followers and following on profiles instead of rounded ones such as 12.3K.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch)

        execute {
            ProfileMetricBinderFingerprint.let {
                val matches = it.instructionMatches
                // Later call first, so the earlier index stays valid.
                for ((call, result) in listOf(matches[2] to matches[3], matches[0] to matches[1])) {
                    val countRegister = when (val instruction = call.instruction) {
                        is FiveRegisterInstruction -> instruction.registerD
                        is RegisterRangeInstruction -> instruction.startRegister + 1
                        else -> throw PatchException("Unexpected profile count formatter call")
                    }
                    val resultRegister = result.getInstruction<OneRegisterInstruction>().registerA
                    if (countRegister > 15 || resultRegister > 15) {
                        throw PatchException("Profile count registers out of range")
                    }
                    it.method.addInstructions(
                        result.index + 1,
                        """
                        invoke-static {v$resultRegister, v$countRegister}, $PATCHES_DESCRIPTOR/userprofile/ProfileTweaks;->exactProfileCount(Ljava/lang/String;Ljava/lang/Integer;)Ljava/lang/String;
                        move-result-object v$resultRegister
                        """.trimIndent(),
                    )
                }
            }

            enableSettings("exactProfileCounts")
        }
    }
