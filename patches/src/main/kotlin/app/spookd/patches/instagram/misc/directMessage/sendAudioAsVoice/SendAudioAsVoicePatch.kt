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
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation

private const val EXTENSION_CLASS = "$PATCHES_DESCRIPTOR/dm/voice/VoiceUpload;"
private const val STRING_CLASS = "Ljava/lang/String;"
private const val INTEGER_CLASS = "Ljava/lang/Integer;"
private const val FRAGMENT_CLASS = "Landroidx/fragment/app/Fragment;"

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

/** Static initializer of the mic's state enum; the second filter writes the state [name]. */
private fun voiceRecordingStateFingerprint(name: String) = Fingerprint(
    name = "<clinit>",
    strings = listOf("LONG_PRESS_STARTED_WAITING_FOR_RECORDER", "RECORDING_LOCKED"),
    filters = listOf(
        string(name),
        opcode(Opcode.SPUT_OBJECT),
    ),
)

/** The mic listener's binding of the mic view, which it stores in the composer views and listens to. */
private fun micViewBindFingerprint(listener: String) = Fingerprint(
    definingClass = listener,
    returnType = "V",
    parameters = listOf("Landroid/view/View;"),
    filters = listOf(
        fieldAccess(opcode = Opcode.IGET_OBJECT),
        fieldAccess(opcode = Opcode.IPUT_OBJECT, type = "Landroid/view/View;", location = MatchAfterImmediately()),
        methodCall(definingClass = "Landroid/view/View;", name = "setOnTouchListener"),
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

private const val MEDIA_RECORDER_CLASS = "Landroid/media/MediaRecorder;"

private fun Instruction.methodRef() = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.fieldRef() = (this as? ReferenceInstruction)?.reference as? FieldReference

/**
 * The MediaRecorder recorder's fields that its start passes to [setter], in code order: the last int
 * field read into the argument register before each call.
 */
private fun recorderSetterFields(code: List<Instruction>, recorder: String, setter: String) =
    code.withIndex().filter { (_, ins) ->
        ins.methodRef()?.let { call -> call.definingClass == MEDIA_RECORDER_CLASS && call.name == setter } == true
    }.map { (index, call) ->
        val register = (call as FiveRegisterInstruction).registerD
        val read = code.subList(0, index).lastOrNull {
            it.opcode == Opcode.IGET && (it as TwoRegisterInstruction).registerA == register
        }?.fieldRef()
        if (read == null || read.definingClass != recorder || read.type != "I") {
            throw PatchException("The recorder's $setter does not take one of its int fields")
        }
        index to read
    }

/** Throws unless the recorder's start passes the constant [value] to [setter] once. */
private fun requireRecorderConstant(code: List<Instruction>, setter: String, value: Int) {
    val calls = code.withIndex().filter { (_, ins) ->
        ins.methodRef()?.let { call -> call.definingClass == MEDIA_RECORDER_CLASS && call.name == setter } == true
    }
    val (index, call) = calls.singleOrNull() ?: throw PatchException("Expected one $setter in the recorder's start")
    val register = (call as FiveRegisterInstruction).registerD
    val previous = code[index - 1]
    if (previous !is NarrowLiteralInstruction || previous.narrowLiteral != value ||
        (previous as OneRegisterInstruction).registerA != register
    ) {
        throw PatchException("The recorder's $setter is not $value")
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
 * no recording starts; a tap still records. Once the sheet closes the mic goes back to idle, so the
 * keyboard can start a recording again.
 *
 * Only a chat whose recorder is the MediaRecorder one is served: its encoder, bit rate and sample rates
 * are read from that recorder, and the file is written as it would write it.
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

        // The MediaRecorder recorder's settings: MPEG-4, mono, and the encoder, bit rate and sample rates it reads.
        val recorderStart = VoiceRecorderStartFingerprint.originalMethod
        val mediaRecorder = classDefBy(recorderStart.definingClass)
        requirePublic(mediaRecorder.type, mediaRecorder.accessFlags)
        val recorderCode = recorderStart.implementation!!.instructions.toList()
        requireRecorderConstant(recorderCode, "setOutputFormat", 2)
        requireRecorderConstant(recorderCode, "setAudioChannels", 1)
        val encoderField = recorderSetterFields(recorderCode, mediaRecorder.type, "setAudioEncoder").single().second
        val bitRateField = recorderSetterFields(recorderCode, mediaRecorder.type, "setAudioEncodingBitRate").single().second
        val rates = recorderSetterFields(recorderCode, mediaRecorder.type, "setAudioSamplingRate")
        // The first rate is set when the model equals "Pixel 4", right after that test.
        if (rates.size != 2 || recorderCode[rates[0].first - 1].opcode != Opcode.IF_EQZ ||
            recorderCode.none { (it as? ReferenceInstruction)?.reference?.let { ref -> ref is StringReference && ref.string == "Pixel 4" } == true }
        ) {
            throw PatchException("Unexpected sample rate choice in the recorder's start")
        }
        val pixelRateField = rates[0].second
        val rateField = rates[1].second
        for (field in listOf(encoderField, bitRateField, rateField, pixelRateField)) {
            val declared = mediaRecorder.fields.single { it.name == field.name && it.type == "I" }
            requirePublic("$field", declared.accessFlags)
        }

        val start = VoiceRecordingStartFingerprint.originalMethod
        val controller = start.definingClass
        // The controller's lazy recorder, read in its start as lazy.getValue() cast to the recorder base class.
        val startCode = start.implementation!!.instructions.toList()
        val recorderRead = (0 until startCode.size - 3).firstOrNull { i ->
            startCode[i].opcode == Opcode.IGET_OBJECT && startCode[i].fieldRef()?.definingClass == controller &&
                startCode[i + 1].opcode == Opcode.INVOKE_INTERFACE && startCode[i + 1].methodRef()?.name == "getValue" &&
                startCode[i + 2].opcode == Opcode.MOVE_RESULT_OBJECT &&
                startCode[i + 3].opcode == Opcode.CHECK_CAST &&
                ((startCode[i + 3] as ReferenceInstruction).reference as TypeReference).type == mediaRecorder.superclass
        } ?: throw PatchException("No recorder read in $start")
        val recorderLazy = startCode[recorderRead].fieldRef()!!
        val lazyValue = startCode[recorderRead + 1].methodRef()!!
        val send = voiceSendFingerprint(controller, recording.type).originalMethod
        requirePublic(controller, classDefBy(controller).accessFlags)
        requirePublic("$send", send.accessFlags)

        // The send is bound to the chat screen that owns the controller, and to the controller's thread.
        val controllerFields = classDefBy(controller).instanceFields.toList()
        val screenField = controllerFields.singleOrNull { it.type == FRAGMENT_CLASS }
            ?: throw PatchException("Expected one chat screen field in $controller")
        val sendCalls = send.implementation!!.instructions.filter { it.opcode == Opcode.INVOKE_STATIC }
            .map { (it as ReferenceInstruction).reference as MethodReference }
        // The send reads the thread's id from the controller's thread field.
        val threadField = controllerFields.singleOrNull { field ->
            !field.type.startsWith("Ljava/") && sendCalls.any { call ->
                call.returnType == STRING_CLASS && call.parameterTypes.map { it.toString() } == listOf(field.type)
            }
        } ?: throw PatchException("Expected one thread field in $controller")
        for (field in listOf(screenField, threadField)) requirePublic("$controller->${field.name}", field.accessFlags)
        requirePublic("$recorderLazy", classDefBy(controller).fields.single { it.name == recorderLazy.name }.accessFlags)
        replaceStub(
            "recorderSettings",
            4,
            """
            check-cast p0, $controller
            iget-object p0, p0, $recorderLazy
            invoke-interface {p0}, $lazyValue
            move-result-object p0
            instance-of v0, p0, ${mediaRecorder.type}
            if-eqz v0, :spookd_other_recorder
            check-cast p0, ${mediaRecorder.type}
            const/4 v0, 0x4
            new-array v0, v0, [I
            iget v1, p0, $encoderField
            const/4 v2, 0x0
            aput v1, v0, v2
            iget v1, p0, $bitRateField
            const/4 v2, 0x1
            aput v1, v0, v2
            iget v1, p0, $rateField
            const/4 v2, 0x2
            aput v1, v0, v2
            iget v1, p0, $pixelRateField
            const/4 v2, 0x3
            aput v1, v0, v2
            return-object v0
            :spookd_other_recorder
            const/4 v0, 0x0
            return-object v0
            """,
        )
        replaceStub(
            "chatScreenOf",
            1,
            """
            check-cast p0, $controller
            iget-object p0, p0, $controller->${screenField.name}:${screenField.type}
            return-object p0
            """,
        )
        replaceStub(
            "chatThreadOf",
            1,
            """
            check-cast p0, $controller
            iget-object p0, p0, $controller->${threadField.name}:${threadField.type}
            return-object p0
            """,
        )

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
            val ignoredState = voiceRecordingStateFingerprint("RECOVERING_FROM_FAILURE").instructionMatches[1]
                .instruction.getReference<FieldReference>()!!
            val idleState = voiceRecordingStateFingerprint("IDLE").instructionMatches[1]
                .instruction.getReference<FieldReference>()!!
            if (stateField.definingClass != method.definingClass || relayField.definingClass != method.definingClass ||
                controllerField.definingClass != relayField.type || controllerField.type != controller
            ) {
                throw PatchException("Unexpected fields in the mic's long press: $stateField, $relayField, $controllerField")
            }
            if (ignoredState.type != stateField.type || idleState.type != stateField.type) {
                throw PatchException("$ignoredState or $idleState is not a state of $stateField")
            }

            // Once the sheet closes, the mic leaves the ignored state unless it is still held down.
            val listenerField = classDefBy(controller).instanceFields.singleOrNull { field -> field.type == method.definingClass }
                ?: throw PatchException("Expected one mic listener field in $controller")
            val micView = micViewBindFingerprint(method.definingClass).instructionMatches
            val viewsField = micView[0].instruction.getReference<FieldReference>()!!
            val viewField = micView[1].instruction.getReference<FieldReference>()!!
            if (viewsField.definingClass != method.definingClass || viewField.definingClass != viewsField.type) {
                throw PatchException("Unexpected mic view fields: $viewsField, $viewField")
            }
            replaceStub(
                "restoreMic",
                3,
                """
                check-cast p0, $controller
                iget-object p0, p0, $controller->${listenerField.name}:${listenerField.type}
                iget-object v0, p0, $stateField
                sget-object v1, $ignoredState
                if-ne v0, v1, :spookd_mic_done
                iget-object v0, p0, $viewsField
                iget-object v0, v0, $viewField
                if-eqz v0, :spookd_mic_idle
                invoke-virtual {v0}, Landroid/view/View;->isPressed()Z
                move-result v0
                if-nez v0, :spookd_mic_done
                :spookd_mic_idle
                sget-object v1, $idleState
                iput-object v1, p0, $stateField
                :spookd_mic_done
                return-void
                """,
            )
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
