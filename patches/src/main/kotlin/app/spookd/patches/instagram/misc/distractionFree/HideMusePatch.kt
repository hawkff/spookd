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
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags

// Builds the Muse banner of a profile's banner row, or returns null when the profile has none.
private object MuseProfileBannerFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "L",
    parameters = listOf("Landroid/content/Context;", "L"),
    strings = listOf("tap_muse_banner", "impression_muse_banner"),
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

            addFlags("hideMuseFlags")
            enableSettings("hideMuse")
        }
    }
