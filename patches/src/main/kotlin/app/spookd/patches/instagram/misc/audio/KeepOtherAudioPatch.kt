/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.audio

import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.Constants.PATCHES_DESCRIPTOR
import app.spookd.patches.instagram.utils.Constants.USER_SESSION_CLASS
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
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
import com.android.tools.smali.dexlib2.iface.Method

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

private object SoundTurnedOnExtensionFingerprint : Fingerprint(
    definingClass = EXTENSION_CLASS,
    name = "soundTurnedOn",
    returnType = "Z",
    parameters = listOf(USER_SESSION_CLASS),
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
            val helperSession = helper.fields.singleOrNull {
                it.type == USER_SESSION_CLASS && !AccessFlags.STATIC.isSet(it.accessFlags)
            } ?: throw PatchException("Expected one session field in the audio focus helper")
            val sharedRequest = SharedAudioFocusRequestFingerprint.method
            if (SharedAudioFocusRequestFingerprint.classDef.superclass != helper.type) {
                throw PatchException("Unexpected shared audio focus helper")
            }

            val soundState = SoundOnForOtherMediaFingerprint.classDef
            fun soundStateMethod(predicate: (Method) -> Boolean) = soundState.methods.singleOrNull(predicate)
                ?: throw PatchException("Unexpected sound state class")
            val soundStateForSession = soundStateMethod {
                AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == soundState.type &&
                    it.parameterTypes.map(CharSequence::toString) == listOf(USER_SESSION_CLASS)
            }
            // True only once the sound was set on; unset means Instagram's per-screen default.
            val soundSetOn = soundStateMethod {
                !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "Z" && it.parameterTypes.isEmpty()
            }

            SoundTurnedOnExtensionFingerprint.method.addInstructions(
                0,
                """
                invoke-static {p0}, $soundStateForSession
                move-result-object p0
                invoke-virtual {p0}, $soundSetOn
                move-result p0
                return p0
                """.trimIndent(),
            )

            listOf(AudioFocusRequestFingerprint.method, sharedRequest).forEach { request ->
                if (request.p0Register < 1) throw PatchException("Unexpected audio focus request")
                request.addInstructionsWithLabels(
                    0,
                    """
                    iget-object v0, p0, $helperSession
                    invoke-static {v0}, $EXTENSION_CLASS->allowFocusRequest($USER_SESSION_CLASS)Z
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
                    invoke-static {}, $EXTENSION_CLASS->skipSoundOnForOtherAudio()Z
                    move-result v0
                    if-eqz v0, :spookd_sound_on_original
                    return-void
                    """.trimIndent(),
                    ExternalLabel("spookd_sound_on_original", getInstruction(0)),
                )
            }

            enableSettings("keepOtherAudio")
        }
    }
