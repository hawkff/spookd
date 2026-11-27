/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.directMessage.sendAudioAsVoice

import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.Constants.PATCHES_DESCRIPTOR
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.string
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation

private const val EXTENSION_CLASS = "$PATCHES_DESCRIPTOR/dm/voice/VoiceUpload;"
private const val STRING_CLASS = "Ljava/lang/String;"
private const val INTEGER_CLASS = "Ljava/lang/Integer;"

/** The MPEG-4 voice message path of Instagram's voice file paths: a new file name in its audio folder. */
private object VoiceFilePathFingerprint : Fingerprint(
    returnType = STRING_CLASS,
    parameters = emptyList(),
    filters = listOf(
        string("_voice_message"),
        string(".mp4", location = MatchAfterImmediately()),
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            parameters = listOf(STRING_CLASS, STRING_CLASS),
            returnType = STRING_CLASS,
            location = MatchAfterImmediately(),
        ),
    ),
)

/**
 * Starts the MediaRecorder voice recorder: it creates a recording in the MPEG-4 format, with the paths
 * object and nulls, then records into the recording's path.
 */
private object VoiceRecorderStartFingerprint : Fingerprint(
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(
        string("vmrecording_start"),
        // The MPEG-4 format.
        fieldAccess(opcode = Opcode.SGET_OBJECT, type = INTEGER_CLASS),
        methodCall(name = "<init>", parameters = listOf("L", INTEGER_CLASS, STRING_CLASS, STRING_CLASS, STRING_CLASS, "Z")),
        // The recording's path.
        fieldAccess(opcode = Opcode.IGET_OBJECT, type = STRING_CLASS),
        methodCall(definingClass = "Landroid/media/MediaRecorder;", name = "setOutputFormat"),
    ),
)

/** The chat voice controller's start of a recording, which the mic's long press and tap call. */
private object VoiceRecordingStartFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L", "Z"),
    strings = listOf("VoiceMessagingController"),
)

/** Static initializer of the mic's state enum; the second filter writes RECOVERING_FROM_FAILURE. */
private object VoiceRecordingStateFingerprint : Fingerprint(
    name = "<clinit>",
    strings = listOf("LONG_PRESS_STARTED_WAITING_FOR_RECORDER", "RECORDING_LOCKED"),
    filters = listOf(
        string("RECOVERING_FROM_FAILURE"),
        opcode(Opcode.SPUT_OBJECT),
    ),
)

/** The controller's send of a finished recording, which logs "audio_clips_send". */
private fun voiceSendFingerprint(controller: String, recording: String) = Fingerprint(
    definingClass = controller,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(controller, recording),
    strings = listOf("audio_clips_send"),
)

/** The mic's long press: it sets the waiting-for-recorder state and starts a recording through the controller. */
private fun micLongPressFingerprint(start: MethodReference) = Fingerprint(
    name = "onLongPress",
    returnType = "V",
    parameters = listOf("Landroid/view/MotionEvent;"),
    filters = listOf(
        fieldAccess(opcode = Opcode.SGET_OBJECT),
        // The state.
        fieldAccess(opcode = Opcode.IPUT_OBJECT, location = MatchAfterImmediately()),
        // The controller, through a relay.
        fieldAccess(opcode = Opcode.IGET_OBJECT, location = MatchAfterImmediately()),
        fieldAccess(opcode = Opcode.IGET_OBJECT, location = MatchAfterImmediately()),
        methodCall(reference = start),
    ),
)

/** Replaces the extension stub [name] with [smali] in a method of [registers] registers. */
private fun replaceStub(name: String, registers: Int, smali: String) {
    val stub = Fingerprint(definingClass = EXTENSION_CLASS, name = name).method
    val bridge = MutableMethod(
        ImmutableMethod(
            stub.definingClass, stub.name, stub.parameters, stub.returnType,
            stub.accessFlags, stub.annotations, stub.hiddenApiRestrictions,
            ImmutableMethodImplementation(registers, emptyList(), emptyList(), emptyList()),
        ),
    ).apply { addInstructions(0, smali.trimIndent()) }
    mutableClassDefBy(EXTENSION_CLASS).methods.apply {
        remove(stub)
        add(bridge)
    }
}

private fun requirePublic(what: String, accessFlags: Int) {
    if (!AccessFlags.PUBLIC.isSet(accessFlags)) throw PatchException("$what is not public")
}

/**
 * How it works:
 *
 * Holding the chat composer's mic starts a hold-to-record recording. With the switch on, the long press
 * opens the extension's audio file sheet instead and leaves the mic in a state its release ignores, so
 * no recording starts; a tap still records.
 *
 * The chosen selection is converted into a new file in Instagram's voice message folder. A recording
 * object is then built the way the MediaRecorder recorder builds one, given that file, its length and
 * waveform, and passed to the controller's own send of a finished recording, which uploads and sends it.
 */
@Suppress("unused")
val sendAudioAsVoicePatch = patch(
    name = "Send audio files as voice messages",
    description = "Adds an option to hold the mic in a chat to pick, trim and send an audio file as a voice message.",
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)
    dependsOn(settingsPatch)

    execute {
        val filePath = VoiceFilePathFingerprint.originalMethod
        val paths = classDefBy(filePath.definingClass)
        requirePublic(paths.type, paths.accessFlags)
        val pathsInit = paths.methods.singleOrNull { it.name == "<init>" && it.parameterTypes.isEmpty() }
            ?: throw PatchException("No constructor without parameters in ${paths.type}")
        requirePublic("$pathsInit", pathsInit.accessFlags)

        val recorder = VoiceRecorderStartFingerprint.instructionMatches
        val format = recorder[1].instruction.getReference<FieldReference>()!!
        val recordingInit = recorder[2].instruction.getReference<MethodReference>()!!
        val recordingPath = recorder[3].instruction.getReference<FieldReference>()!!
        val recording = classDefBy(recordingInit.definingClass)
        requirePublic(recording.type, recording.accessFlags)
        if (recordingInit.parameterTypes.first().toString() !in paths.interfaces) {
            throw PatchException("${paths.type} is not the paths type of $recordingInit")
        }
        val fields = recording.instanceFields.toList()
        val pathField = fields.singleOrNull { it.name == recordingPath.name && it.type == STRING_CLASS }
            ?: throw PatchException("$recordingPath is not a field of ${recording.type}")
        val durationField = fields.singleOrNull { it.type == "I" }
            ?: throw PatchException("Expected one duration field in ${recording.type}")
        val waveformField = fields.singleOrNull { it.type == "Ljava/util/List;" }
            ?: throw PatchException("Expected one waveform field in ${recording.type}")
        for (field in listOf(pathField, durationField)) {
            requirePublic("${recording.type}->${field.name}", field.accessFlags)
            if (AccessFlags.FINAL.isSet(field.accessFlags)) throw PatchException("${field.name} of ${recording.type} is final")
        }
        requirePublic("${recording.type}->${waveformField.name}", waveformField.accessFlags)

        val start = VoiceRecordingStartFingerprint.originalMethod
        val controller = start.definingClass
        val send = voiceSendFingerprint(controller, recording.type).originalMethod
        requirePublic(controller, classDefBy(controller).accessFlags)
        requirePublic("$send", send.accessFlags)

        replaceStub(
            "voiceFilePath",
            1,
            """
            new-instance v0, ${paths.type}
            invoke-direct {v0}, $pathsInit
            invoke-virtual {v0}, $filePath
            move-result-object v0
            return-object v0
            """,
        )

        // A recording as the recorder starts one, then given the converted file, its length and its waveform.
        replaceStub(
            "sendRecording",
            11,
            """
            new-instance v0, ${recording.type}
            new-instance v1, ${paths.type}
            invoke-direct {v1}, $pathsInit
            sget-object v2, $format
            const/4 v3, 0x0
            const/4 v4, 0x0
            const/4 v5, 0x0
            const/4 v6, 0x0
            invoke-direct/range {v0 .. v6}, $recordingInit
            iput-object p1, v0, ${recording.type}->${pathField.name}:$STRING_CLASS
            iput p2, v0, ${recording.type}->${durationField.name}:I
            iget-object v1, v0, ${recording.type}->${waveformField.name}:Ljava/util/List;
            invoke-interface {v1, p3}, Ljava/util/List;->addAll(Ljava/util/Collection;)Z
            check-cast p0, $controller
            invoke-static {p0, v0}, $send
            return-void
            """,
        )

        micLongPressFingerprint(start).let {
            val method = it.method
            val stateField = it.instructionMatches[1].instruction.getReference<FieldReference>()!!
            val relayField = it.instructionMatches[2].instruction.getReference<FieldReference>()!!
            val controllerField = it.instructionMatches[3].instruction.getReference<FieldReference>()!!
            val ignoredState = VoiceRecordingStateFingerprint.instructionMatches[1]
                .instruction.getReference<FieldReference>()!!
            if (stateField.definingClass != method.definingClass || relayField.definingClass != method.definingClass ||
                controllerField.definingClass != relayField.type || controllerField.type != controller
            ) {
                throw PatchException("Unexpected fields in the mic's long press: $stateField, $relayField, $controllerField")
            }
            if (ignoredState.type != stateField.type) {
                throw PatchException("$ignoredState is not a state of $stateField")
            }
            // p0 and the event take two registers; the hook needs one more.
            if (method.implementation!!.registerCount < 3) throw PatchException("No free register in $method")

            // Its release ignores the recovering state, so neither hold-to-record nor tap-to-record starts.
            method.addInstructionsWithLabels(
                0,
                """
                iget-object v0, p0, $relayField
                iget-object v0, v0, $controllerField
                invoke-static {v0}, $EXTENSION_CLASS->onMicLongPress(Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :spookd_voice_record
                sget-object v0, $ignoredState
                iput-object v0, p0, $stateField
                return-void
                """.trimIndent(),
                ExternalLabel("spookd_voice_record", method.getInstruction(0)),
            )
        }

        enableSettings("sendAudioAsVoice")
    }
}
