/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.playbackSpeed

import app.spookd.patches.instagram.entity.decoder.MEDIA_CLASS_NAME
import app.spookd.patches.instagram.entity.mediadata.mediaDataEntity
import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.misc.stories.OnCLickStoryButtonFingerprint
import app.spookd.patches.instagram.misc.stories.SelfStoryOnCLickStoryButtonFingerprint
import app.spookd.patches.instagram.misc.stories.handleStoryButtonPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.Constants.PATCHES_DESCRIPTOR
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstructions
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.patch.classDefByOrNull
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.p0Register
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.registersUsed
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val STORY_PLAYBACK_CLASS = "$PATCHES_DESCRIPTOR/StoryPlayback;"
private const val REEL_VIEWER_FRAGMENT = "Linstagram/features/stories/fragment/ReelViewerFragment;"
private const val REEL_ITEM = "Lcom/instagram/model/reels/ReelItem;"
private const val FRAGMENT = "Landroidx/fragment/app/Fragment;"
private const val ACTIVITY = "Landroid/app/Activity;"
private const val CHAR_SEQUENCE = "Ljava/lang/CharSequence;"
private const val DISMISS_LISTENER = "Landroid/content/DialogInterface\$OnDismissListener;"
private const val ATOMIC_BOOLEAN = "Ljava/util/concurrent/atomic/AtomicBoolean;"

private object StoryPlayerCreatedFingerprint : Fingerprint(
    definingClass = REEL_VIEWER_FRAGMENT,
    name = "onViewCreated",
    filters = listOf(fieldAccess(definingClass = "this", name = "mVideoPlayer", opcode = Opcode.IPUT_OBJECT)),
)

// The story player's sound decision: Instagram's session sound check, told whether the viewer asked for sound.
private fun storySoundFingerprint(owner: String) = Fingerprint(
    definingClass = owner,
    returnType = "Z",
    parameters = emptyList(),
    filters = listOf(
        methodCall(smali = "$ATOMIC_BOOLEAN->get()Z"),
        opcode(Opcode.MOVE_RESULT, MatchAfterImmediately()),
        methodCall(parameters = listOf("Ljava/lang/Integer;", "Z", "Z"), returnType = "Z"),
        opcode(Opcode.MOVE_RESULT, MatchAfterImmediately()),
    ),
)

private class StoryPlayerApi(val position: String, val duration: String, val seek: String)

private fun Method.parameterList() = parameterTypes.map(CharSequence::toString)

private fun Method.code() = implementation?.instructions?.toList().orEmpty()

// The relative seek adds an offset to the position getter's source; the absolute seek is clamped by the duration field.
private fun storyPlayerApi(player: String, api: Set<String>): StoryPlayerApi? {
    val methods = classDefBy(player).methods.filter { it.name in api }
    val seeks = methods.filter { it.parameterList() == listOf("I") && it.returnType == "V" }
    val relative = seeks.singleOrNull { m -> m.code().any { it.opcode == Opcode.ADD_INT || it.opcode == Opcode.ADD_INT_2ADDR } }
        ?: return null
    val absolute = seeks.singleOrNull { it.name != relative.name } ?: return null
    val source = relative.code().mapNotNull { it.getReference<MethodReference>() }.distinct()
        .singleOrNull { it.parameterTypes.isEmpty() && it.returnType == "I" } ?: return null
    val getters = methods.filter { it.parameterTypes.isEmpty() && it.returnType == "I" }
    val position = getters.singleOrNull { m -> m.code().any { it.getReference<MethodReference>() == source } } ?: return null
    val durationField = absolute.code().mapNotNull { it.getReference<FieldReference>() }.distinct()
        .singleOrNull { it.definingClass == player && it.type == "I" } ?: return null
    val duration = getters.singleOrNull { m ->
        m.code().let { it.size == 2 && it[0].opcode == Opcode.IGET && it[0].getReference<FieldReference>() == durationField }
    } ?: return null
    return StoryPlayerApi(position.name, duration.name, absolute.name)
}

private fun replaceStoryStub(name: String, smali: String) =
    Fingerprint(definingClass = STORY_PLAYBACK_CLASS, name = name).method.apply {
        removeInstructions(0, instructions.size)
        addInstructions(0, smali)
    }

// Wires the panel's player calls to Instagram's story player interface.
private fun hookStoryPlayer(playerField: FieldReference, players: List<String>) {
    val player = playerField.type
    val api = classDefBy(player).methods
    val apis = players.mapNotNull { storyPlayerApi(it, api.map { method -> method.name }.toSet()) }
    val found = apis.firstOrNull() ?: throw PatchException("Unexpected story player seek and position methods")
    if (apis.any { it.position != found.position || it.duration != found.duration || it.seek != found.seek }) {
        throw PatchException("Story players disagree on seek and position methods")
    }
    val speed = api.single { it.parameterList() == listOf("F") && it.returnType == "V" }

    replaceStoryStub("player", """
        check-cast p0, $REEL_VIEWER_FRAGMENT
        iget-object p0, p0, $playerField
        return-object p0
    """)
    replaceStoryStub("positionMs", """
        check-cast p0, $player
        invoke-interface {p0}, $player->${found.position}()I
        move-result p0
        return p0
    """)
    replaceStoryStub("durationMs", """
        check-cast p0, $player
        invoke-interface {p0}, $player->${found.duration}()I
        move-result p0
        return p0
    """)
    replaceStoryStub("seekTo", """
        check-cast p0, $player
        invoke-interface {p0, p1}, $player->${found.seek}(I)V
        return-void
    """)
    replaceStoryStub("setSpeed", """
        check-cast p0, $player
        invoke-interface {p0, p1}, $speed
        return-void
    """)
}

// Turns the session sound check's true result false while Mute stories is on and the viewer was not asked for sound.
private fun hookSoundDecision(method: MutableMethod, callIndex: Int, asked: Int) {
    val code = method.implementation!!.instructions
    val call = code[callIndex].registersUsed
    val result = code[callIndex + 1].registersUsed.single()
    val insertAt = callIndex + 2
    if (code[callIndex + 1].opcode != Opcode.MOVE_RESULT || call.size != 4 || call[2] != asked || asked == result ||
        asked !in 0..15 || result !in 0..15 || insertAt >= code.size || code[insertAt].location.labels.isNotEmpty()
    ) throw PatchException("Unexpected story sound decision in $method")
    method.addInstructions(insertAt, """
        invoke-static {v$result, v$asked}, $STORY_PLAYBACK_CLASS->storySound(ZZ)Z
        move-result v$result
    """)
}

// Every story player's sound decision is hooked; a player that keeps its sound state in a helper plays by the helper's decision.
private fun hookStorySound(players: List<String>) {
    val hooked = mutableSetOf<String>()
    fun hook(owner: String): String? {
        val match = storySoundFingerprint(owner).matchOrNull() ?: return null
        val get = match.instructionMatches[0]
        val stateRead = match.method.implementation!!.instructions[get.index - 1]
        val stateField = stateRead.getReference<FieldReference>()
        if (stateRead.opcode != Opcode.IGET_OBJECT || stateField?.type != ATOMIC_BOOLEAN ||
            stateRead.registersUsed.first() != get.instruction.registersUsed.single()
        ) throw PatchException("Unexpected story sound state in ${match.method}")
        if (hooked.add(owner)) {
            hookSoundDecision(match.method, match.instructionMatches[2].index, match.instructionMatches[1].instruction.registersUsed.single())
        }
        return stateField!!.definingClass
    }
    for (player in players) {
        val state = hook(player) ?: throw PatchException("Missing story sound decision in $player")
        if (state != player && hook(state) == null) throw PatchException("Missing story sound decision in $state")
    }
}

// A Speed and seek row tap opens the panel for the viewer's player and keeps the story paused until it closes.
private fun hookStoryMenu() {
    listOf(SelfStoryOnCLickStoryButtonFingerprint, OnCLickStoryButtonFingerprint).forEach { fingerprint ->
        fingerprint.method.apply {
            val fields = fingerprint.classDef.fields
            val activityField = fields.single { it.type == ACTIVITY }
            val fragmentField = fields.single { it.type == FRAGMENT }
            val itemField = fields.single { it.type == REEL_ITEM }
            val dismissField = fields.single { it.type == DISMISS_LISTENER }
            val mediaField = instructions.mapNotNull { it.getReference<FieldReference>() }
                .filter { it.definingClass == REEL_ITEM && it.type == MEDIA_CLASS_NAME }
                .distinctBy { it.toString() }
                .singleOrNull() ?: throw PatchException("Expected one story menu media field")
            val text = parameters.indexOfLast { it.type == CHAR_SEQUENCE }
            val menu = parameters.indexOfLast { it.type == fingerprint.classDef.type }
            val dismiss = parameters.indices.singleOrNull { parameters[it].type == DISMISS_LISTENER }
            if (p0Register < 6 || text < 0 || menu < 0 || dismiss == null) {
                throw PatchException("Unexpected story menu click parameters or local registers")
            }

            // At method entry v0..v5 are free; v5 keeps the menu instance.
            addInstructionsWithLabels(
                0,
                """
                move-object/from16 v0, p$text
                move-object/from16 v5, p$menu
                iget-object v1, v5, $activityField
                iget-object v2, v5, $fragmentField
                iget-object v3, v5, $itemField
                iget-object v3, v3, $mediaField
                move-object/from16 v4, p$dismiss
                invoke-static {v0, v1, v2, v3, v4}, $STORY_PLAYBACK_CLASS->openPanel(${CHAR_SEQUENCE}Landroid/content/Context;Ljava/lang/Object;Ljava/lang/Object;$DISMISS_LISTENER)Z
                move-result v0
                if-eqz v0, :spookd_story_menu
                const/4 v0, 0x0
                iput-object v0, v5, $dismissField
                return-void
                """.trimIndent(),
                ExternalLabel("spookd_story_menu", getInstruction(0)),
            )
        }
    }
}

@Suppress("unused")
val storyPlaybackPatch =
    patch(
        name = "Story playback options",
        description = "Adds Speed and seek to the story menu for video stories, and a switch to play stories without sound.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch, handleStoryButtonPatch, mediaDataEntity)

        execute {
            val created = StoryPlayerCreatedFingerprint.instructionMatches.single().fieldReference
            val players = StoryPlayerCreatedFingerprint.originalMethod.implementation!!.instructions
                .mapNotNull { it.getReference<MethodReference>() }
                .filter { it.name == "<init>" }
                .map { it.definingClass }
                .distinct()
                .filter { classDefByOrNull(it)?.interfaces?.contains(created.type) == true }
            if (players.isEmpty()) throw PatchException("No story video player implementations")

            hookStoryPlayer(created, players)
            hookStorySound(players)
            hookStoryMenu()

            enableSettings("storyPlayback")
        }
    }
