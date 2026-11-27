/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.audio

import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.Constants.PATCHES_DESCRIPTOR
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.p0Register
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags

private const val EXTENSION_CLASS = "$PATCHES_DESCRIPTOR/audio/KeepOtherAudio;"
private const val FOCUS_LISTENER = "Landroid/media/AudioManager\$OnAudioFocusChangeListener;"

// Instagram's audio focus helper: asks Android for audio focus for a player that is about to play sound.
private object AudioFocusRequestFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(FOCUS_LISTENER),
    filters = listOf(
        literal(36328899274431384L),
        methodCall(smali = "Landroid/media/AudioManager;->requestAudioFocus(${FOCUS_LISTENER}II)I"),
    ),
)

// The helper subclass shared by an account's players, which hands focus from one player to the next.
private object SharedAudioFocusReleaseFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(FOCUS_LISTENER, "L", "Z"),
    filters = listOf(literal(36335109791904374L)),
)

// Its request, which remembers the player before it asks the helper for focus.
private object SharedAudioFocusRequestFingerprint : Fingerprint(
    classFingerprint = SharedAudioFocusReleaseFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(FOCUS_LISTENER),
    filters = listOf(methodCall(smali = "$FOCUS_LISTENER->onAudioFocusChange(I)V")),
)

// On foreground, the account's sound state turns sound on when another app is playing media.
private object SoundOnForOtherMediaFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Landroid/media/AudioManager;"),
    filters = listOf(
        methodCall(smali = "Landroid/media/AudioManager;->getActivePlaybackConfigurations()Ljava/util/List;"),
        literal(36335109791773300L),
    ),
)

private object SetSoundExtensionFingerprint : Fingerprint(
    definingClass = EXTENSION_CLASS,
    name = "setSound",
    returnType = "V",
    parameters = listOf("Ljava/lang/Object;", "Z"),
)

@Suppress("unused")
val keepOtherAudioPatch =
    patch(
        name = "Keep other audio playing",
        description = "Adds an option to keep music and other apps' audio playing when Instagram opens or plays " +
            "muted videos. Instagram takes audio focus only after you turn its sound on.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch)

        execute {
            val helper = AudioFocusRequestFingerprint.classDef
            val sharedRequest = SharedAudioFocusRequestFingerprint.method
            if (SharedAudioFocusRequestFingerprint.classDef.superclass != helper.type) {
                throw PatchException("Unexpected shared audio focus helper")
            }

            val soundState = SoundOnForOtherMediaFingerprint.classDef
            fun soundStateMethod(parameter: String) = soundState.methods.singleOrNull {
                !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" &&
                    it.parameterTypes.map(CharSequence::toString) == listOf(parameter)
            } ?: throw PatchException("Unexpected sound state class")
            // The setter every mute and unmute goes through, and the timed automatic sound on.
            val soundSetter = soundStateMethod("Z")
            val timedSoundOn = soundStateMethod("J")

            SetSoundExtensionFingerprint.method.addInstructions(
                0,
                """
                check-cast p0, ${soundState.type}
                invoke-virtual {p0, p1}, $soundSetter
                return-void
                """.trimIndent(),
            )

            listOf(AudioFocusRequestFingerprint.method, sharedRequest).forEach { request ->
                if (request.p0Register < 1) throw PatchException("Unexpected audio focus request")
                request.addInstructionsWithLabels(
                    0,
                    """
                    invoke-static {}, $EXTENSION_CLASS->allowFocusRequest()Z
                    move-result v0
                    if-nez v0, :spookd_audio_focus_original
                    return-void
                    """.trimIndent(),
                    ExternalLabel("spookd_audio_focus_original", request.getInstruction(0)),
                )
            }

            SoundOnForOtherMediaFingerprint.method.apply {
                if (p0Register < 1) throw PatchException("Unexpected sound state check")
                addInstructionsWithLabels(
                    0,
                    """
                    invoke-static {p0, p1}, $EXTENSION_CLASS->onForeground(Ljava/lang/Object;Landroid/media/AudioManager;)Z
                    move-result v0
                    if-eqz v0, :spookd_sound_on_original
                    return-void
                    """.trimIndent(),
                    ExternalLabel("spookd_sound_on_original", getInstruction(0)),
                )
            }

            timedSoundOn.apply {
                if (p0Register < 1) throw PatchException("Unexpected timed sound on")
                addInstructionsWithLabels(
                    0,
                    """
                    invoke-static {}, $EXTENSION_CLASS->skipTimedSoundOn()Z
                    move-result v0
                    if-eqz v0, :spookd_timed_sound_on_original
                    return-void
                    """.trimIndent(),
                    ExternalLabel("spookd_timed_sound_on_original", getInstruction(0)),
                )
            }

            soundSetter.addInstruction(0, "invoke-static {p1}, $EXTENSION_CLASS->onSoundSet(Z)V")

            enableSettings("keepOtherAudio")
        }
    }
