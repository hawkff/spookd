/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.playbackSpeed

import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.Constants.PREF_CALL_DESCRIPTOR
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.p0Register
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags

private const val VISUAL_MESSAGE_VIEWER = "Linstagram/features/direct/visual/internal/DirectVisualMessageViewerController;"

// Moves the viewer to the next message, or closes it after the last; false means the photo timer or the video ended.
internal object VisualMessageAdvanceFingerprint : Fingerprint(
    definingClass = VISUAL_MESSAGE_VIEWER,
    returnType = "V",
    parameters = listOf(VISUAL_MESSAGE_VIEWER, "Z"),
    filters = listOf(methodCall(smali = "Landroid/graphics/drawable/AnimationDrawable;->stop()V")),
)

@Suppress("unused")
val stopVisualMessageAutoAdvancePatch =
    patch(
        name = "Stop visual message auto-advance",
        description = "Adds an option to keep the DM photo and video viewer on the current message until you tap.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch)

        execute {
            VisualMessageAdvanceFingerprint.method.apply {
                if (!AccessFlags.STATIC.isSet(accessFlags) || p0Register < 1) {
                    throw PatchException("Unexpected visual message advance method")
                }
                addInstructionsWithLabels(
                    0,
                    """
                    if-nez p1, :spookd_advance
                    $PREF_CALL_DESCRIPTOR->stopVisualMessageAutoAdvance()Z
                    move-result v0
                    if-eqz v0, :spookd_advance
                    return-void
                    """.trimIndent(),
                    ExternalLabel("spookd_advance", getInstruction(0)),
                )
            }

            enableSettings("stopVisualMessageAutoAdvance")
        }
    }
