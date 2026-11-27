/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.distractionFree

import app.spookd.patches.instagram.misc.hookFlags.hookFlagsPatch
import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.addFlags
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.patch
import app.morphe.patcher.string
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

// Builds the Muse banner of a profile's banner row, or returns null when the profile has none.
private object MuseProfileBannerFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "L",
    parameters = listOf("Landroid/content/Context;", "L"),
    strings = listOf("tap_muse_banner", "impression_muse_banner"),
)

// Builds the share sheet's horizontal actions, one case per action key; Muse's key is "hatch".
private object ShareSheetActionsFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "Ljava/util/ArrayList;",
    parameters = listOf("L", "Ljava/lang/Object;"),
    strings = listOf("snapchat", "system_share_sheet"),
    filters = listOf(
        string("hatch"),
        methodCall(smali = "Ljava/lang/String;->equals(Ljava/lang/Object;)Z", location = MatchAfterImmediately()),
        // Whether the action key is Muse's.
        opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately()),
    ),
)

@Suppress("unused")
val hideMusePatch =
    patch(
        name = "Hide Muse",
        description = "Adds an option to hide Muse in the share sheet and on profiles.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch, hookFlagsPatch)

        execute {
            MuseProfileBannerFingerprint.method.apply {
                val free = findFreeRegister(0)
                addInstructionsWithLabels(
                    0,
                    """
                    invoke-static {}, $DISTRACTION_FREE_HIDES->hideMuseBanner()Z
                    move-result v$free
                    if-eqz v$free, :spookd_muse_banner
                    const/4 v$free, 0x0
                    return-object v$free
                    """.trimIndent(),
                    ExternalLabel("spookd_muse_banner", getInstruction(0)),
                )
            }

            ShareSheetActionsFingerprint.let {
                val isMuse = it.instructionMatches.last()
                val register = isMuse.getInstruction<OneRegisterInstruction>().registerA
                it.method.addInstructions(
                    isMuse.index + 1,
                    """
                    invoke-static/range {v$register .. v$register}, $DISTRACTION_FREE_HIDES->buildMuseShareAction(Z)Z
                    move-result v$register
                    """.trimIndent(),
                )
            }

            addFlags("hideMuseFlags")
            enableSettings("hideMuse")
        }
    }
