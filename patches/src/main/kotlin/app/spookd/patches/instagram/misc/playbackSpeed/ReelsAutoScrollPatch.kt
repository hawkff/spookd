/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.playbackSpeed

import app.spookd.patches.instagram.misc.hookFlags.hookFlagsPatch
import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.Constants.PREF_CALL_DESCRIPTOR
import app.spookd.patches.instagram.utils.Constants.USER_SESSION_CLASS
import app.spookd.patches.instagram.utils.addFlags
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.string
import app.morphe.patcher.util.p0Register
import app.morphe.patcher.util.smali.ExternalLabel

// Instagram's final check whether Reels auto-scroll is available: the 83371::0 flag or a server text setting.
private object ReelsAutoScrollAvailableFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf(USER_SESSION_CLASS, "Z"),
    filters = listOf(string("android_purge_26_q2_ClipsViewerExperimentUtil_isOptInAutoscrollEnabled")),
)

@Suppress("unused")
val reelsAutoScrollPatch =
    patch(
        name = "Reels auto-scroll",
        description = "Adds an option to turn Instagram's own Reels auto-scroll on or off, or leave it to Instagram.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch, hookFlagsPatch)

        execute {
            addFlags("reelsAutoScrollFlags")
            ReelsAutoScrollAvailableFingerprint.method.apply {
                if (p0Register < 1) throw PatchException("Unexpected Reels auto-scroll check")
                addInstructionsWithLabels(
                    0,
                    """
                    $PREF_CALL_DESCRIPTOR->reelsAutoScrollOff()Z
                    move-result v0
                    if-eqz v0, :spookd_auto_scroll
                    const/4 v0, 0x0
                    return v0
                    """.trimIndent(),
                    ExternalLabel("spookd_auto_scroll", getInstruction(0)),
                )
            }
            enableSettings("reelsAutoScroll")
        }
    }
