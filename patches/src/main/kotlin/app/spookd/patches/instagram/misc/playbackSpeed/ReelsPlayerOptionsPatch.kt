/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.playbackSpeed

import app.spookd.patches.instagram.misc.reels.addReelMethod
import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.Constants.PATCHES_DESCRIPTOR
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.string
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.registersUsed
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val OPTIONS = "$PATCHES_DESCRIPTOR/ReelsPlayerOptions;"
private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"
private const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"
private const val CLIPS_VIEWER_CONFIG = "Lcom/instagram/clips/intf/ClipsViewerConfig;"
private const val CLIPS_VIEWER_SOURCE = "Lcom/instagram/clips/intf/ClipsViewerSource;"
private const val TAP_DECISION = "android_purge_26_q3_ClipsGestureNavigator_enableAudioToggleOnSingleTap"

private val BRANCHES = setOf(
    Opcode.IF_EQ, Opcode.IF_NE, Opcode.IF_LT, Opcode.IF_GE, Opcode.IF_GT, Opcode.IF_LE,
    Opcode.IF_EQZ, Opcode.IF_NEZ, Opcode.IF_LTZ, Opcode.IF_GEZ, Opcode.IF_GTZ, Opcode.IF_LEZ,
)

private fun MethodReference.parameters() = parameterTypes.map(CharSequence::toString)

private fun controllerMethod(name: String, returnType: String, parameters: List<String>) =
    Fingerprint(
        returnType = returnType, parameters = parameters,
        filters = listOf(string("android_purge_26_q3_ClipsVideoPlayerController_$name")),
    ).originalMethod

// The Reels player plays without its loop flag and records when it reaches the end.
private fun hookReelEnd(owner: String, groot: String, reels: String) {
    val completed = Fingerprint(
        definingClass = owner, returnType = "V", parameters = listOf(groot),
        filters = listOf(string("Playback completed ")),
    ).method
    val completedCode = completed.implementation!!.instructions
    val notify = completedCode.mapNotNull { it.getReference<MethodReference>() }.single {
        it.definingClass != owner && it.parameterTypes.isEmpty() && it.returnType == "V"
    }
    val listenerField = completedCode.mapNotNull { it.getReference<FieldReference>() }.distinct()
        .single { it.definingClass == owner && it.type == notify.definingClass }

    val play = Fingerprint(
        definingClass = owner, returnType = "V",
        filters = listOf(
            string("IgVideoPlayerImpl.playInternal playAfterSeek: "),
            methodCall(definingClass = groot, parameters = listOf("Z"), returnType = "V"),
        ),
    ).match()
    val setLooping = play.instructionMatches.last()
    val loop = setLooping.instruction.registersUsed.last()
    require(
        AccessFlags.STATIC.isSet(play.method.accessFlags) && play.method.parameterTypes.first().toString() == owner &&
            play.method.implementation!!.instructions[setLooping.index].location.labels.isEmpty(),
    ) { "Unexpected video player play path" }

    val looping = addReelMethod(
        owner, "spookdReelLooping", listOf("Z"), "Z", registers = 3,
        instructions = """
            if-eqz p1, :keep
            iget-object v0, p0, $listenerField
            instance-of v0, v0, $reels
            if-eqz v0, :keep
            invoke-static {}, $OPTIONS->stopLooping()Z
            move-result v0
            if-eqz v0, :keep
            const/4 p1, 0x0
            :keep
            return p1
        """,
    )
    val ended = addReelMethod(
        owner, "spookdReelEnded", emptyList(), "V", registers = 2,
        instructions = """
            iget-object v0, p0, $listenerField
            instance-of v0, v0, $reels
            if-eqz v0, :done
            invoke-static {p0}, $OPTIONS->onReelEnded($OBJECT)V
            :done
            return-void
        """,
    )
    // The static play method receives the player as its first parameter.
    play.method.addInstructions(setLooping.index, """
        invoke-direct {p0, v$loop}, $looping
        move-result v$loop
    """)
    completed.addInstruction(0, "invoke-direct/range {p0 .. p0}, $ended")
}

// Without a stored sound choice, the Reels controller asks for silence instead of Instagram's sound-on default.
private fun hookStartMuted(controller: String) {
    val audio = Fingerprint(
        definingClass = controller, returnType = "Z", parameters = emptyList(),
        filters = listOf(methodCall(parameters = listOf("Ljava/lang/Integer;", "Z", "Z"), returnType = "Z")),
    ).match()
    val call = audio.instructionMatches.single()
    val code = audio.method.implementation!!.instructions
    val enabled = code[call.index + 1].registersUsed.single()
    require(
        code[call.index + 1].opcode == Opcode.MOVE_RESULT && code[call.index + 2].opcode == Opcode.RETURN &&
            code[call.index + 2].registersUsed.single() == enabled && code[call.index + 2].location.labels.isEmpty(),
    ) { "Unexpected Reels audio check" }

    val sessionAudioCode = call.instruction.getReference<MethodReference>()!!.let { audioState ->
        classDefBy(audioState.definingClass).methods.single { it == audioState }.implementation!!.instructions
    }
    // Instagram's session sound choice: null until the user or Instagram turns sound on or off.
    val sessionAudio = sessionAudioCode.mapNotNull { it.getReference<FieldReference>() }.distinct()
        .single { it.type == "Ljava/lang/Boolean;" }
    val sessionAudioOf = sessionAudioCode.mapNotNull { it.getReference<MethodReference>() }.distinct()
        .single { it.returnType == sessionAudio.definingClass && it.parameters() == listOf(USER_SESSION) }
    val session = code.mapNotNull { it.getReference<FieldReference>() }.distinct()
        .single { it.definingClass == controller && it.type == USER_SESSION }

    val muted = addReelMethod(
        controller, "spookdStartReelMuted", listOf("Z"), "Z", registers = 3,
        instructions = """
            if-eqz p1, :keep
            invoke-static {}, $OPTIONS->startMuted()Z
            move-result v0
            if-eqz v0, :keep
            iget-object v0, p0, $session
            invoke-static {v0}, $sessionAudioOf
            move-result-object v0
            iget-object v0, v0, $sessionAudio
            if-nez v0, :keep
            const/4 p1, 0x0
            :keep
            return p1
        """,
    )
    audio.method.addInstructions(call.index + 2, """
        invoke-direct {p0, v$enabled}, $muted
        move-result v$enabled
    """)
    val setSound = guardAutomaticSoundOn(sessionAudio.definingClass)
    hookExpandedFeedMuted(controller, audio.method, session, sessionAudioOf, setSound)
}

// A Reels viewer opened from the home feed turns the session sound off once, before its first sound check.
private fun hookExpandedFeedMuted(
    controller: String, audio: MutableMethod, session: FieldReference, sessionAudioOf: MethodReference, setSound: Method,
) {
    fun instanceField(owner: String, type: String) = classDefBy(owner).fields.single {
        it.type == type && !AccessFlags.STATIC.isSet(it.accessFlags)
    }
    val config = instanceField(controller, CLIPS_VIEWER_CONFIG)
    val source = instanceField(CLIPS_VIEWER_CONFIG, CLIPS_VIEWER_SOURCE)
    val mute = addReelMethod(
        controller, "spookdMuteExpandedFeedVideo", emptyList(), "V", registers = 3,
        instructions = """
            iget-object v0, p0, $config
            if-eqz v0, :done
            iget-object v0, v0, $source
            invoke-static {p0, v0}, $OPTIONS->muteExpandedFeedVideo($OBJECT$OBJECT)Z
            move-result v0
            if-eqz v0, :done
            iget-object v0, p0, $session
            invoke-static {v0}, $sessionAudioOf
            move-result-object v0
            const/4 v1, 0x0
            invoke-virtual {v0, v1}, $setSound
            :done
            return-void
        """,
    )
    audio.addInstruction(0, "invoke-direct/range {p0 .. p0}, $mute")
}

// The session store turns sound on by itself (other media playing on return to the app, a timer); user choices come from outside it.
private fun guardAutomaticSoundOn(store: String): Method {
    val setter = classDefBy(store).methods.single {
        it.parameters() == listOf("Z") && it.returnType == "V" && !AccessFlags.STATIC.isSet(it.accessFlags)
    }
    val guard = addReelMethod(
        store, "spookdAutomaticSoundOn", listOf("Z"), "V", registers = 3,
        instructions = """
            if-eqz p1, :set
            invoke-static {}, $OPTIONS->startMuted()Z
            move-result v0
            if-nez v0, :skip
            :set
            invoke-virtual {p0, p1}, $setter
            :skip
            return-void
        """,
    )
    val writes = mutableClassDefBy(store).methods.filter { it.name != guard.name && it != setter }.flatMap { method ->
        val code = method.implementation?.instructions.orEmpty()
        code.indices.filter { code[it].getReference<MethodReference>() == setter }.map { method to it }
    }
    require(writes.isNotEmpty()) { "Missing automatic session sound writes" }
    for ((method, index) in writes) {
        val (receiver, value) = method.implementation!!.instructions[index].registersUsed
        method.replaceInstruction(index, "invoke-direct {v$receiver, v$value}, $guard")
    }
    return setter
}

// A single tap restarts an ended Reel, and can pause or resume a Reel instead of toggling its sound.
private fun hookReelTap(controller: String) {
    val tap = Fingerprint(returnType = OBJECT, filters = listOf(string(TAP_DECISION))).method
    val code = tap.implementation!!.instructions
    val returnIndex = code.indices.single {
        code[it].opcode == Opcode.RETURN_OBJECT && code[it - 1].opcode == Opcode.SGET_OBJECT &&
            code[it - 1].registersUsed == code[it].registersUsed
    }
    // The tap handler's plain return.
    val done = code[returnIndex - 1]

    class Site(val start: Int, val scratch: Int, val item: Int, val navigator: Int, val tapPauses: Int, val tapPausesIndex: Int)
    var navigatorType: String? = null
    val sites = code.indices.filter { code[it].getReference<StringReference>()?.string == TAP_DECISION }.map { site ->
        val first = code[site + 2]
        val gate = (site + 3 until code.size).first { index ->
            code[index].opcode == Opcode.INVOKE_STATIC && code[index].getReference<MethodReference>()?.let {
                it.returnType == "Z" && it.parameters().size == 4 && it.parameters()[3] == "Z"
            } == true
        }
        val pauseCheck = (site + 3 until gate).single { index ->
            code[index].opcode == Opcode.INVOKE_STATIC && code[index].getReference<MethodReference>()?.let {
                it.returnType == "Z" && it.parameters() == listOf(USER_SESSION)
            } == true
        }
        val (gateItem, _, nav) = code[gate].registersUsed
        val type = code[gate].getReference<MethodReference>()!!.parameterTypes[2].toString()
        require(navigatorType == null || navigatorType == type) { "Unexpected Reels tap navigator" }
        navigatorType = type
        require(code[site + 1].opcode == Opcode.INVOKE_STATIC && first.opcode == Opcode.IGET_BOOLEAN) {
            "Unexpected Reels tap handler"
        }
        first as TwoRegisterInstruction
        val tapPauses = code[pauseCheck + 1].registersUsed.single()
        require(
            first.location.labels.isEmpty() && first.registerB == gateItem &&
                code[pauseCheck + 1].opcode == Opcode.MOVE_RESULT && code[pauseCheck + 2].location.labels.isEmpty() &&
                setOf(first.registerA, gateItem, nav).size == 3 &&
                listOf(first.registerA, gateItem, nav, tapPauses).all { it in 0..15 } &&
                (site + 2 until gate).none { code[it].opcode.setsRegister() && code[it].registersUsed.first() in setOf(gateItem, nav) },
        ) { "Unexpected Reels tap handler" }
        Site(site, first.registerA, gateItem, nav, tapPauses, pauseCheck + 2)
    }
    require(sites.isNotEmpty()) { "Missing Reels tap handler" }

    val navigator = navigatorType!!
    val controllerField = classDefBy(navigator).fields.single {
        it.type == controller && !AccessFlags.STATIC.isSet(it.accessFlags)
    }
    val pause = controllerMethod("pauseCurrentPlayer", "V", listOf(STRING, "Z", "Z"))
    val seek = controllerMethod("seekToCurrentPlayer", "Z", listOf("I", "Z", "Z"))
    val resume = controllerMethod("resumeCurrentPlayer", "V", listOf(STRING, "J", "Z", "Z", "Z"))
    require(listOf(pause, seek, resume).all { it.definingClass == controller }) { "Unexpected Reels player controller" }

    // Instagram's own replay sequence: pause, seek to the start, resume.
    val restart = addReelMethod(
        tap.definingClass, "spookdRestartEndedReel", listOf(OBJECT, navigator), "Z", registers = 9,
        flags = AccessFlags.PRIVATE.value or AccessFlags.STATIC.value or AccessFlags.SYNTHETIC.value,
        instructions = """
            invoke-static {p0}, $OPTIONS->takeEndedReel($OBJECT)Z
            move-result v0
            if-eqz v0, :keep
            iget-object v0, p1, $controllerField
            if-eqz v0, :keep
            const-string v1, "seek"
            const/4 v2, 0x0
            invoke-virtual {v0, v1, v2, v2}, $pause
            const/4 v3, 0x1
            invoke-virtual {v0, v2, v2, v3}, $seek
            const-string v1, "resume"
            const-wide/16 v2, 0x0
            const/4 v4, 0x0
            const/4 v5, 0x0
            const/4 v6, 0x0
            invoke-virtual/range {v0 .. v6}, $resume
            const/4 v0, 0x1
            return v0
            :keep
            const/4 v0, 0x0
            return v0
        """,
    )
    // Before those sites, a pending flag can send the tap straight to the sound toggle.
    val shortcut = (0 until sites.minOf { it.start }).single { index ->
        code[index].getReference<MethodReference>()?.let {
            it.definingClass == controller && it.parameters() == listOf("I") && it.returnType == "V"
        } == true
    }
    // The toggle's controller is read from the navigator right after the flag test that skips the toggle.
    val controllerRead = (shortcut - 1 downTo maxOf(1, shortcut - 4)).firstOrNull { index ->
        code[index].opcode == Opcode.IGET_OBJECT &&
            code[index].registersUsed.first() == code[shortcut].registersUsed.first() &&
            code[index].getReference<FieldReference>()?.toString() == controllerField.toString()
    } ?: throw PatchException("Missing controller read in the Reels tap sound shortcut")
    val flagCheck = (controllerRead - 1 downTo maxOf(1, controllerRead - 3)).firstOrNull { index ->
        code[index].opcode in BRANCHES && code[index - 1].opcode == Opcode.IGET_BOOLEAN
    } ?: throw PatchException("Missing flag test in the Reels tap sound shortcut")
    val shortcutScratch = code[flagCheck].registersUsed.first()
    val insertAt = flagCheck + 1
    val decision = code[(code[flagCheck] as BuilderOffsetInstruction).target.location.index]
    // The flag register must be dead on both paths, so it can hold the extension's answer.
    require(
        code[insertAt].location.labels.isEmpty() &&
            code[insertAt].opcode.setsRegister() && code[insertAt].registersUsed.first() == shortcutScratch &&
            decision.opcode.setsRegister() && decision.registersUsed.first() == shortcutScratch &&
            (insertAt..shortcut).none { code[it].opcode in BRANCHES },
    ) { "Unexpected Reels tap sound shortcut" }

    for (site in sites.sortedByDescending { it.start }) {
        tap.addInstructions(site.tapPausesIndex, """
            invoke-static {v${site.tapPauses}}, $OPTIONS->tapPausesReel(Z)Z
            move-result v${site.tapPauses}
        """)
        tap.addInstructionsWithLabels(site.start + 2, """
            invoke-static {v${site.item}, v${site.navigator}}, $restart
            move-result v${site.scratch}
            if-nez v${site.scratch}, :spookd_tap_done
        """, ExternalLabel("spookd_tap_done", done))
    }
    tap.addInstructionsWithLabels(insertAt, """
        invoke-static {}, $OPTIONS->skipsTapSoundShortcut()Z
        move-result v$shortcutScratch
        if-nez v$shortcutScratch, :spookd_tap_decision
    """, ExternalLabel("spookd_tap_decision", decision))
}

@Suppress("unused")
val reelsPlayerOptionsPatch =
    patch(
        name = "Reels player options",
        description = "Adds switches to start Reels and expanded feed videos muted, stop Reels from looping, and tap a Reel to pause it instead of muting it.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch, defaultPlaybackSpeedPatch)

        execute {
            val owner = VideoPreparedFingerprint.method.definingClass
            val groot = VideoPreparedFingerprint.method.parameterTypes.single().toString()
            val reels = ReelPreparedListenerFingerprint.originalMethod.definingClass
            val controller = controllerMethod("pauseCurrentPlayer", "V", listOf(STRING, "Z", "Z")).definingClass

            hookReelEnd(owner, groot, reels)
            hookStartMuted(controller)
            hookReelTap(controller)

            enableSettings("reelsPlayerOptions")
        }
    }
