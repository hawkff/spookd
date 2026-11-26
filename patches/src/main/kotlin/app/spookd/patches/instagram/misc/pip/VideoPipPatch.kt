/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.pip

import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.Constants.PATCHES_DESCRIPTOR
import app.spookd.patches.instagram.utils.Constants.USER_SESSION_CLASS
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.string
import app.morphe.patcher.util.p0Register
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags

// Whether Instagram's Reels PiP experiment (the version that remembers the choice) is on for the account.
private object ReelsPipAvailableFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf(USER_SESSION_CLASS, "Z"),
    filters = listOf(string("android_purge_26_q2_ClipsViewerExperimentUtil_isPictureInPictureEnabledPersistentVersion")),
)

// The user's stored Reels PiP choice, off until turned on in Instagram's Reels menu.
private object ReelsPipChosenFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf(USER_SESSION_CLASS, "Z"),
    filters = listOf(string("android_purge_26_q2_ClipsViewerPipUtil_isClipsPipEnabled")),
)

@Suppress("unused")
val videoPipPatch =
    patch(
        name = "Picture-in-picture for videos",
        description = "Adds an option to keep a full-screen Reel or feed video playing in a picture-in-picture window " +
            "when you leave the app. Uses Instagram's own Reels picture-in-picture and needs Android 15 or newer.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch)

        execute {
            val checks = listOf(ReelsPipAvailableFingerprint.method, ReelsPipChosenFingerprint.method)
            checks.forEach { if (it.p0Register < 1) throw PatchException("Unexpected Reels picture-in-picture check") }
            checks.forEach { method ->
                method.addInstructionsWithLabels(
                    0,
                    """
                    invoke-static {p1}, $PATCHES_DESCRIPTOR/pip/VideoPip;->forceOn(Z)Z
                    move-result v0
                    if-eqz v0, :spookd_pip_original
                    return v0
                    """.trimIndent(),
                    ExternalLabel("spookd_pip_original", method.getInstruction(0)),
                )
            }
            enableSettings("pipForVideos")
        }
    }
