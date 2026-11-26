/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.instants

import app.spookd.patches.instagram.misc.confirm.addConfirmGate
import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.Constants.INSTANTS_DESCRIPTOR
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val INSTANTS_SEND_DESCRIPTOR = "$INSTANTS_DESCRIPTOR/InstantsSend;"
private const val INSTANTS_REACTIONS_DESCRIPTOR = "$INSTANTS_DESCRIPTOR/InstantsReactions;"

private fun extensionStubFingerprint(definingClass: String, name: String) = Fingerprint(
    definingClass = definingClass,
    name = name,
)

private fun MutableMethod.smaliReference() =
    "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

private fun MutableMethod.parameterRegisters() =
    parameterTypes.sumOf { if (it.toString() == "J" || it.toString() == "D") 2 else 1 } +
        if (AccessFlags.STATIC.isSet(accessFlags)) 0 else 1

// Kotlin's COROUTINE_SUSPENDED: the first enum constant a suspending method of Instagram's reads.
private fun MutableMethod.suspendedMarker() = instructions.firstNotNullOfOrNull { instruction ->
    if (instruction.opcode != Opcode.SGET_OBJECT) return@firstNotNullOfOrNull null
    instruction.getReference<FieldReference>()?.takeIf { it.type == it.definingClass }
}

/**
 * Hooks the Instants camera's send so Disable Instants creation and Confirm Instants can hold a capture back.
 */
internal val instantsSendHookPatch =
    patch(
        description = "Holds back captured Instants at the Instants camera's send.",
    ) {
        dependsOn(settingsPatch)

        execute {
            val send = InstantSendFingerprint.method
            if (!AccessFlags.STATIC.isSet(send.accessFlags)) throw PatchException("Instants send is not static")
            val sendType = send.parameterTypes.first().toString()
            val mediumField = InstantSendFingerprint.instructionMatches[0].fieldReference
            val isVideo = InstantSendFingerprint.instructionMatches[1].methodReference
            val payloadField = classDefBy(sendType).fields.singleOrNull { it.type == mediumField.definingClass }
                ?: throw PatchException("Instants send has no single capture field")
            // Instagram's own discard of a capture that is not sent: the capture, the camera and whether to drop its placeholder.
            val discard = classDefBy(QUICK_SNAP_CAMERA_VIEW_MODEL_CLASS).methods.singleOrNull {
                AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" &&
                    it.parameterTypes.map(CharSequence::toString) == listOf(sendType, QUICK_SNAP_CAMERA_VIEW_MODEL_CLASS, "Z")
            } ?: throw PatchException("Instants discard not found")

            extensionStubFingerprint(INSTANTS_SEND_DESCRIPTOR, "isVideoNative").method.addInstructions(
                0,
                """
                check-cast p0, $sendType
                iget-object p0, p0, $sendType->${payloadField.name}:${payloadField.type}
                iget-object p0, p0, $mediumField
                invoke-virtual {p0}, $isVideo
                move-result p0
                return p0
                """.trimIndent(),
            )
            extensionStubFingerprint(INSTANTS_SEND_DESCRIPTOR, "sendNative").method.addInstructions(
                0,
                """
                check-cast p0, $sendType
                check-cast p1, $QUICK_SNAP_CAMERA_VIEW_MODEL_CLASS
                invoke-static {p0, p1}, ${send.smaliReference()}
                return-void
                """.trimIndent(),
            )
            extensionStubFingerprint(INSTANTS_SEND_DESCRIPTOR, "discardNative").method.addInstructions(
                0,
                """
                check-cast p0, $sendType
                check-cast p1, $QUICK_SNAP_CAMERA_VIEW_MODEL_CLASS
                invoke-static {p0, p1, p2}, $QUICK_SNAP_CAMERA_VIEW_MODEL_CLASS->${discard.name}($sendType${QUICK_SNAP_CAMERA_VIEW_MODEL_CLASS}Z)V
                return-void
                """.trimIndent(),
            )

            val added = send.addConfirmGate(
                1,
                """
                invoke-static/range {p0 .. p1}, $INSTANTS_SEND_DESCRIPTOR->allowSend(Ljava/lang/Object;Ljava/lang/Object;)Z
                move-result v0
                """,
            )
            if (!added) throw PatchException("Instants send has no free register")
        }
    }

@Suppress("unused")
val disableInstantsCreationPatch =
    patch(
        name = "Disable Instants creation",
        description = "Adds an option to discard Instants you capture instead of sending them.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch, instantsSendHookPatch)

        execute {
            enableSettings("disableInstantsCreation")
        }
    }

@Suppress("unused")
val confirmInstantsPatch =
    patch(
        name = "Confirm Instants",
        description = "Adds options to ask before an Instant video or an emoji reaction to an Instant is sent.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch, instantsSendHookPatch)

        execute {
            enableSettings("confirmInstantVideo")

            val react = InstantReactionSendFingerprint.matchOrNull()?.method ?: return@execute
            val continuationType = react.parameterTypes.last().toString()
            val marker = react.suspendedMarker()
            val resumable = classDefBy(continuationType).methods.any {
                it.name == "resumeWith" && it.returnType == "V" && it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Object;")
            }
            // The seen report is another suspending method of the same API, so both read the same marker.
            if (AccessFlags.STATIC.isSet(react.accessFlags) || marker == null || !resumable ||
                marker != InstantsSeenReportFingerprint.matchOrNull()?.method?.suspendedMarker() ||
                react.implementation!!.registerCount - react.parameterRegisters() < 1
            ) {
                return@execute
            }

            extensionStubFingerprint(INSTANTS_REACTIONS_DESCRIPTOR, "suspendedNative").method.addInstructions(
                0,
                """
                sget-object v0, $marker
                return-object v0
                """.trimIndent(),
            )
            extensionStubFingerprint(INSTANTS_REACTIONS_DESCRIPTOR, "reactNative").method.addInstructions(
                0,
                """
                check-cast p0, $QUICK_SNAP_API_CLASS
                check-cast p3, $continuationType
                invoke-virtual {p0, p1, p2, p3}, ${react.smaliReference()}
                move-result-object p0
                return-object p0
                """.trimIndent(),
            )
            extensionStubFingerprint(INSTANTS_REACTIONS_DESCRIPTOR, "resumeNative").method.addInstructions(
                0,
                """
                check-cast p0, $continuationType
                invoke-interface {p0, p1}, $continuationType->resumeWith(Ljava/lang/Object;)V
                return-void
                """.trimIndent(),
            )

            // p1 and p2 are null when Instagram resumes its own call, which the extension lets through.
            react.addInstructionsWithLabels(
                0,
                """
                invoke-static/range {p0 .. p3}, $INSTANTS_REACTIONS_DESCRIPTOR->holdReaction(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v0
                if-eqz v0, :instants_reaction_send
                return-object v0
                """.trimIndent(),
                ExternalLabel("instants_reaction_send", react.getInstruction(0)),
            )
            enableSettings("confirmInstantReaction")
        }
    }
