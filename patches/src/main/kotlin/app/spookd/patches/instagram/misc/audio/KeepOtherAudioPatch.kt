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
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.string
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.p0Register
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

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

private const val AUDIO_FOCUS_REQUEST = "Landroid/media/AudioFocusRequest;"
private const val AUDIO_MANAGER = "Landroid/media/AudioManager;"
private const val PIP_FOCUS_STRING = "android_purge_26_q2_ClipsPiPFragment_requestAudioFocus"

// Reels picture-in-picture's onCreate: builds its focus request and asks for it when the player does not manage focus.
private object PipFocusSetupFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(string("android_purge_26_q2_ClipsPiPFragment_setupAudioFocus"), string(PIP_FOCUS_STRING)),
)

// Its later check, which asks again through the player or directly.
private object PipFocusCheckFingerprint : Fingerprint(
    name = "run",
    returnType = "V",
    parameters = listOf(),
    filters = listOf(string("android_purge_26_q2_ClipsPiPFragment_run"), string(PIP_FOCUS_STRING)),
)

private object RequestPipFocusExtensionFingerprint : Fingerprint(
    definingClass = EXTENSION_CLASS,
    name = "requestPipFocus",
    returnType = "V",
    parameters = listOf("Ljava/lang/Object;", "Ljava/lang/Object;", "Ljava/lang/Object;", "Ljava/lang/Object;"),
)

private fun MutableMethod.reference(index: Int) = getInstruction<ReferenceInstruction>(index).reference

// Skips a request when not allowed, jumping to skipTo; temp is a register that is not read before it is written.
private fun MutableMethod.gatePipFocus(index: Int, pip: Int, temp: Int, skipTo: Int) {
    addInstructionsWithLabels(
        index,
        """
        invoke-static/range {v$pip .. v$pip}, $EXTENSION_CLASS->allowPipFocusRequest(Ljava/lang/Object;)Z
        move-result v$temp
        if-eqz v$temp, :spookd_pip_focus_skip
        """.trimIndent(),
        ExternalLabel("spookd_pip_focus_skip", getInstruction(skipTo)),
    )
}

// Gates the direct request: the two flags it sets, then the request, which is skipped when the request is null.
private fun MutableMethod.gatePipDirectFocus(pipType: String): Pair<FieldReference, FieldReference> {
    val list = instructions
    val string = list.indexOfFirst {
        it.opcode == Opcode.CONST_STRING && (it as ReferenceInstruction).reference.toString() == PIP_FOCUS_STRING
    }
    if (string < 3 || list[string - 2].opcode != Opcode.IPUT_BOOLEAN || list[string - 1].opcode != Opcode.IPUT_BOOLEAN) {
        throw PatchException("Unexpected picture-in-picture focus request")
    }
    val requested = reference(string - 2) as FieldReference
    val held = reference(string - 1) as FieldReference
    if (requested.definingClass != pipType || held.definingClass != pipType) {
        throw PatchException("Unexpected picture-in-picture focus flags")
    }
    val value = getInstruction<TwoRegisterInstruction>(string - 2).registerA
    val pip = getInstruction<TwoRegisterInstruction>(string - 2).registerB
    val nullCheck = (string until list.size).first { list[it].opcode == Opcode.IF_EQZ }
    val skipTo = getInstruction<BuilderOffsetInstruction>(nullCheck).target.location.index
    val before = list[string - 3]
    if (before.opcode == Opcode.CONST_4 && (before as OneRegisterInstruction).registerA == value) {
        gatePipFocus(string - 3, pip, value, skipTo)
    } else {
        val temp = getInstruction<OneRegisterInstruction>(string).registerA
        if (temp == value || temp == pip) throw PatchException("No register for the picture-in-picture gate")
        gatePipFocus(string - 2, pip, temp, skipTo)
    }
    return requested to held
}

// Gates the player-managed request: from the read of the player's focus handler to the call.
private fun MutableMethod.gatePipPlayerFocus(handlerField: FieldReference, request: MethodReference) {
    val list = instructions
    val call = list.indexOfFirst {
        it.opcode == Opcode.INVOKE_VIRTUAL && (it as ReferenceInstruction).reference == request
    }
    if (call < 0) throw PatchException("Unexpected picture-in-picture player focus request")
    val pip = getInstruction<FiveRegisterInstruction>(call).registerD
    val handlerRead = (call - 1 downTo 0).first {
        list[it].opcode == Opcode.IGET_OBJECT && reference(it) == handlerField &&
            getInstruction<TwoRegisterInstruction>(it).registerB == pip
    }
    gatePipFocus(handlerRead, pip, getInstruction<TwoRegisterInstruction>(handlerRead).registerA, call + 1)
}

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
            // The helper's entry for a player, which goes on to the request.
            val requestName = AudioFocusRequestFingerprint.originalMethod.name
            val helperEntry = helper.methods.singleOrNull { method ->
                method.name != requestName && !AccessFlags.STATIC.isSet(method.accessFlags) &&
                    method.returnType == "V" && method.parameterTypes.map(CharSequence::toString) == listOf(FOCUS_LISTENER) &&
                    method.implementation?.instructions?.any { instruction ->
                        instruction.getReference<MethodReference>()
                            ?.let { it.definingClass == helper.type && it.name == requestName } == true
                    } == true
            } ?: throw PatchException("Could not find the audio focus helper entry")
            // The router players call: it hands the request to the helper, or to the player's own focus
            // handling when Instagram's player-managed focus is on.
            val router = Fingerprint(
                accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
                returnType = "V",
                parameters = listOf(FOCUS_LISTENER, "L", "Z"),
                filters = listOf(methodCall(smali = "${helper.type}->${helperEntry.name}(${FOCUS_LISTENER})V")),
            ).method

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

            listOf(AudioFocusRequestFingerprint.method, sharedRequest, router).forEach { request ->
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

            // Reels picture-in-picture asks for focus on its own, directly or through the player.
            val pipCreate = PipFocusSetupFingerprint.method
            val pipType = pipCreate.definingClass
            val pipCheck = PipFocusCheckFingerprint.method
            val checkList = pipCheck.instructions
            val playerCall = checkList.indexOfFirst { instruction ->
                instruction.opcode == Opcode.INVOKE_VIRTUAL &&
                    instruction.getReference<MethodReference>()?.let {
                        it.returnType == "V" && it.parameterTypes.map(CharSequence::toString).let { types ->
                            types.size == 3 && types[0] == FOCUS_LISTENER && types[2] == "Lkotlin/jvm/functions/Function1;"
                        }
                    } == true
            }
            if (playerCall < 8) throw PatchException("Could not find the picture-in-picture player focus request")
            val playerRequest = pipCheck.reference(playerCall) as MethodReference
            fun fieldRead(index: Int, opcode: Opcode) = checkList[index].takeIf { it.opcode == opcode }
                ?.let { pipCheck.reference(index) as FieldReference }?.takeIf { it.definingClass == pipType }
            val handler = fieldRead(playerCall - 5, Opcode.IGET_OBJECT)?.takeIf { it.type == playerRequest.definingClass }
            val playerItem = fieldRead(playerCall - 4, Opcode.IGET_OBJECT)
            val callbackId = (checkList[playerCall - 3] as? NarrowLiteralInstruction)?.narrowLiteral
            val callbackFactory = checkList[playerCall - 2].takeIf { it.opcode == Opcode.INVOKE_STATIC }
                ?.let { pipCheck.reference(playerCall - 2) as MethodReference }
            val playerManaged = checkList[playerCall - 8].takeIf { it.opcode == Opcode.INVOKE_STATIC }
                ?.let { pipCheck.reference(playerCall - 8) as MethodReference }
                ?.takeIf { it.returnType == "Z" && it.parameterTypes.map(CharSequence::toString) == listOf(pipType) }
            val flagReads = (0 until playerCall - 8).filter { checkList[it].opcode == Opcode.IGET_BOOLEAN }.takeLast(2)
                .map { fieldRead(it, Opcode.IGET_BOOLEAN) }
            val (focusRequested, focusHeld) = pipCheck.gatePipDirectFocus(pipType)
            val playerFocusHeld = flagReads.firstOrNull()
            if (handler == null || playerItem == null || callbackId == null || callbackFactory == null ||
                playerManaged == null || playerFocusHeld == null || flagReads.getOrNull(1) != focusRequested
            ) {
                throw PatchException("Unexpected picture-in-picture focus check")
            }
            val pipFields = classDefBy(pipType).fields
            val focusRequest = pipFields.singleOrNull { it.type == AUDIO_FOCUS_REQUEST }
            val audioManager = pipFields.singleOrNull { it.type == AUDIO_MANAGER }
            if (focusRequest == null || audioManager == null) throw PatchException("Unexpected picture-in-picture fields")

            pipCheck.gatePipPlayerFocus(handler, playerRequest)
            pipCreate.gatePipDirectFocus(pipType)
            Fingerprint(
                name = "invoke",
                returnType = "Ljava/lang/Object;",
                parameters = listOf(),
                filters = listOf(
                    fieldAccess(reference = handler, opcode = Opcode.IGET_OBJECT),
                    methodCall(smali = playerRequest.toString()),
                ),
            ).method.gatePipPlayerFocus(handler, playerRequest)

            // The same choice as the later check, without its music check, for a request the switch skipped.
            RequestPipFocusExtensionFingerprint.method.addInstructions(
                0,
                """
                check-cast p0, $pipType
                invoke-virtual {p0}, Landroidx/fragment/app/Fragment;->isAdded()Z
                move-result p1
                if-eqz p1, :done
                iget-boolean p1, p0, $playerFocusHeld
                if-nez p1, :done
                invoke-static {p0}, $playerManaged
                move-result p1
                if-eqz p1, :direct
                iget-boolean p1, p0, $focusRequested
                if-nez p1, :done
                iget-object p1, p0, $handler
                iget-object p2, p0, $playerItem
                const/16 p3, $callbackId
                invoke-static {p0, p3}, $callbackFactory
                move-result-object p3
                invoke-virtual {p1, p0, p2, p3}, $playerRequest
                return-void
                :direct
                iget-boolean p1, p0, $focusHeld
                if-nez p1, :done
                const/4 p1, 0x1
                iput-boolean p1, p0, $focusRequested
                iput-boolean p1, p0, $focusHeld
                iget-object p1, p0, $focusRequest
                if-eqz p1, :done
                iget-object p2, p0, $audioManager
                if-eqz p2, :done
                invoke-virtual {p2, p1}, $AUDIO_MANAGER->requestAudioFocus($AUDIO_FOCUS_REQUEST)I
                :done
                return-void
                """.trimIndent(),
            )

            enableSettings("keepOtherAudio")
        }
    }
