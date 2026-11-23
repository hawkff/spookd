/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.playbackSpeed

import app.spookd.patches.instagram.misc.overflowMenuButton.reels.hookReelOverflowMenuButton
import app.spookd.patches.instagram.misc.reels.addReelMethod
import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.Constants.PATCHES_DESCRIPTOR
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstructions
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.string
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PLAYBACK_SPEED_CLASS = "$PATCHES_DESCRIPTOR/PlaybackSpeed;"
private const val OBJECT = "Ljava/lang/Object;"

// IgVideoPlayerImpl's prepared callback; its parameter is the player that owns the speed.
private object VideoPreparedFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(string("IgVideoPlayerImpl.onPreparedInternal")),
)

private object ReelSetSpeedFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("F"),
    filters = listOf(
        string("android_purge_26_q3_ClipsVideoPlayer_setPlaybackSpeed"),
        methodCall(parameters = listOf("F"), returnType = "V"),
    ),
)

// The Reels player's listener, which IgVideoPlayerImpl notifies.
private object ReelPreparedListenerFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(string("android_purge_26_q3_ClipsVideoPlayer_onVideoPrepared")),
)

// The feed video controller, the listener of feed players.
private object FeedVideoControllerFingerprint : Fingerprint(
    filters = listOf(string("feed_video_crash_when_adjusting_volume")),
)

private fun MethodReference.parameters() = parameterTypes.map(CharSequence::toString)

private fun replaceStub(name: String, smali: String) =
    Fingerprint(definingClass = PLAYBACK_SPEED_CLASS, name = name).method.apply {
        removeInstructions(0, instructions.size)
        addInstructionsWithLabels(0, smali)
    }

@Suppress("unused")
val defaultPlaybackSpeedPatch =
    patch(
        name = "Default playback speed",
        description = "Starts Reels and feed videos at a speed you choose, and adds a speed and seek panel to the Reels menu.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch, hookReelOverflowMenuButton)

        execute {
            val prepared = VideoPreparedFingerprint.method
            val owner = prepared.definingClass
            val groot = prepared.parameterTypes.single().toString()
            val setSpeed = ReelSetSpeedFingerprint.instructionMatches.last().methodReference
            val grootField = ReelSetSpeedFingerprint.originalMethod.implementation!!.instructions
                .mapNotNull { it.getReference<FieldReference>() }.distinct()
                .single { it.definingClass == owner && it.type == groot }
            require(setSpeed.definingClass == groot) { "Unexpected Reels speed setter" }

            val reelsListener = ReelPreparedListenerFingerprint.originalMethod
            val feedListener = FeedVideoControllerFingerprint.originalClassDef.type
            val code = VideoPreparedFingerprint.originalMethod.implementation!!.instructions
            val fields = code.mapNotNull { it.getReference<FieldReference>() }.distinct()
            val notify = code.mapNotNull { it.getReference<MethodReference>() }.distinct().single {
                it.name == reelsListener.name && it.parameters() == reelsListener.parameters() &&
                    it.returnType == reelsListener.returnType
            }
            val listenerField = fields.single { it.definingClass == owner && it.type == notify.definingClass }
            // The prepared callback reads the play request from the player's state to notify its listener.
            val source = notify.parameters().first()
            val sourceField = fields.single { it.type == source }
            val stateField = fields.single { it.definingClass == owner && it.type == sourceField.definingClass }
            val contentField = classDefBy(source).fields.single {
                it.type == OBJECT && !AccessFlags.STATIC.isSet(it.accessFlags)
            }

            val ownerMethods = classDefBy(owner).methods
            val intGetters = ownerMethods.filter { it.parameterTypes.isEmpty() && it.returnType == "I" }
            fun Method.has(predicate: (Instruction) -> Boolean) = implementation?.instructions?.any(predicate) == true
            // The position getter ignores values above one day; the duration getter narrows a long.
            val position = intGetters.single { m -> m.has { (it as? WideLiteralInstruction)?.wideLiteral == 86_400_000L } }
            val duration = intGetters.single { m -> m.has { it.opcode == Opcode.LONG_TO_INT } }
            require(position != duration) { "Unexpected video player getters" }
            val seek = ownerMethods.single { it.parameters() == listOf("I", "Z", "Z") && it.returnType == "V" }
            val reels = reelsListener.definingClass

            val onPrepared = addReelMethod(
                owner, "spookdApplyDefaultSpeed", listOf(groot), "V", registers = 4,
                instructions = """
                    invoke-static {}, $PLAYBACK_SPEED_CLASS->defaultSpeed()F
                    move-result v0
                    const/high16 v1, 0x3f800000
                    cmpl-float v1, v0, v1
                    if-eqz v1, :done
                    if-eqz p1, :done
                    iget-object v1, p0, $listenerField
                    instance-of v1, v1, $reels
                    if-nez v1, :apply
                    iget-object v1, p0, $listenerField
                    instance-of v1, v1, $feedListener
                    if-eqz v1, :done
                    :apply
                    invoke-virtual {p1, v0}, $setSpeed
                    :done
                    return-void
                """,
            )
            val onStarted = addReelMethod(
                owner, "spookdTrackReelStart", emptyList(), "V", registers = 2,
                instructions = """
                    iget-object v0, p0, $listenerField
                    instance-of v0, v0, $reels
                    if-eqz v0, :done
                    invoke-static {p0}, $PLAYBACK_SPEED_CLASS->onReelStarted($OBJECT)V
                    :done
                    return-void
                """,
            )
            prepared.addInstruction(0, "invoke-direct/range {p0 .. p1}, $onPrepared")
            Fingerprint(
                definingClass = owner, parameters = listOf("J", "J"), returnType = "V",
                strings = listOf("video_cached", "warmed"),
            ).method.addInstruction(0, "invoke-direct/range {p0 .. p0}, $onStarted")

            replaceStub("playingContent", """
                check-cast p0, $owner
                iget-object p0, p0, $stateField
                if-eqz p0, :none
                iget-object p0, p0, $sourceField
                if-eqz p0, :none
                iget-object p0, p0, $contentField
                :none
                return-object p0
            """)
            replaceStub("positionMs", """
                check-cast p0, $owner
                invoke-virtual {p0}, $position
                move-result p0
                return p0
            """)
            replaceStub("durationMs", """
                check-cast p0, $owner
                invoke-virtual {p0}, $duration
                move-result p0
                return p0
            """)
            replaceStub("seekTo", """
                check-cast p0, $owner
                invoke-virtual {p0, p1, p2, p2}, $seek
                return-void
            """)
            replaceStub("setSpeed", """
                check-cast p0, $owner
                iget-object p0, p0, $grootField
                if-eqz p0, :none
                invoke-virtual {p0, p1}, $setSpeed
                :none
                return-void
            """)

            enableSettings("defaultPlaybackSpeed")
        }
    }
