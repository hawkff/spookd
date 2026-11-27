/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.instants

import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.Constants.INSTANTS_DESCRIPTOR
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.registersUsed
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val INSTANTS_UPLOAD_DESCRIPTOR = "$INSTANTS_DESCRIPTOR/upload/InstantsUpload;"
private const val CAPTURED_FRAME_CLASS =
    "Lcom/instagram/quicksnap/camera/ui/QuickSnapCameraShutterButtonComponent\$processCapturedFrame\$1\$job\$1\$1;"
private const val FUNCTION1 = "Lkotlin/jvm/functions/Function1;"

/** The Instants camera's setup once its camera is ready: it builds the shutter, then tags the preview view. */
internal object InstantsCameraReadyFingerprint : Fingerprint(
    name = "onInitialized",
    returnType = "V",
    parameters = emptyList(),
    strings = listOf("camera_kit_init_callback", "camera_start_camera"),
)

/** Hands a taken, cropped camera frame to the shutter's photo handler, an 8-argument function. */
internal object InstantsCapturedFrameFingerprint : Fingerprint(
    definingClass = CAPTURED_FRAME_CLASS,
    name = "invokeSuspend",
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;"),
)

/** The shutter's recording listener: failures go to one handler, a good recording's file to the video handler. */
internal object InstantsVideoRecordedFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("Video capture failed, file path is null", "Empty (0-byte) video capture rejected: "),
)

/** The preview's left margin of its visible square; the same class has the square's top offset. */
internal object InstantsPreviewMarginFingerprint : Fingerprint(
    name = "getCameraHorizontalMarginPx",
    returnType = "F",
    parameters = emptyList(),
    custom = { _, classDef -> classDef.methods.any { it.name == "getVerticalCropOffset" } },
)

private fun uploadStub(name: String) = Fingerprint(definingClass = INSTANTS_UPLOAD_DESCRIPTOR, name = name)

@Suppress("unused")
val sendMediaAsInstantsPatch =
    patch(
        name = "Send media as Instants",
        description = "Adds an option for a button in the Instants camera that sends a photo or video from the phone or the private gallery as an Instant.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch)

        execute {
            val frame = InstantsCapturedFrameFingerprint.originalMethod.implementation!!.instructions
            val photoHandler = frame.firstNotNullOfOrNull { instruction ->
                if (instruction.opcode != Opcode.INVOKE_INTERFACE_RANGE) return@firstNotNullOfOrNull null
                instruction.getReference<MethodReference>()?.takeIf { it.parameterTypes.size == 8 }
            } ?: throw PatchException("Instants photo handler call not found")
            if (photoHandler.returnType != "Ljava/lang/Object;" ||
                photoHandler.parameterTypes.any { it.toString() != "Ljava/lang/Object;" }
            ) {
                throw PatchException("Instants photo handler is not a plain function")
            }
            val photoField = frame.firstNotNullOfOrNull { instruction ->
                if (instruction.opcode != Opcode.IGET_OBJECT) return@firstNotNullOfOrNull null
                instruction.getReference<FieldReference>()?.takeIf { it.type == photoHandler.definingClass }
            } ?: throw PatchException("Instants photo handler field not found")
            val shutterType = photoField.definingClass

            val videoFields = InstantsVideoRecordedFingerprint.originalMethod.implementation!!.instructions.mapNotNull { instruction ->
                if (instruction.opcode != Opcode.IGET_OBJECT) return@mapNotNull null
                instruction.getReference<FieldReference>()?.takeIf { it.definingClass == shutterType && it.type == FUNCTION1 }
            }
            // The failure handler is read first; the good recording's handler is read last.
            val videoField = videoFields.lastOrNull()?.takeIf { it != videoFields.first() }
                ?: throw PatchException("Instants video handler field not found")

            // The shutter's photo entry first checks, under its lock, whether the camera released it.
            val releasedField = classDefBy(shutterType).methods.firstOrNull {
                AccessFlags.STATIC.isSet(it.accessFlags) && it.parameterTypes.firstOrNull()?.toString() == "Landroid/graphics/Bitmap;"
            }?.implementation?.instructions?.firstNotNullOfOrNull { instruction ->
                if (instruction.opcode != Opcode.IGET_BOOLEAN) return@firstNotNullOfOrNull null
                instruction.getReference<FieldReference>()?.takeIf { it.definingClass == shutterType }
            } ?: throw PatchException("Instants shutter release flag not found")

            val ready = InstantsCameraReadyFingerprint.method
            val code = ready.instructions.toList()
            // The recorder's time limit is read from the shutter once, to pass it on in microseconds.
            val maxVideoField = code.mapNotNull { instruction ->
                if (instruction.opcode != Opcode.IGET_WIDE) return@mapNotNull null
                instruction.getReference<FieldReference>()?.takeIf { it.definingClass == shutterType && it.type == "J" }
            }.singleOrNull() ?: throw PatchException("Instants video time limit not found")
            val created = code.indexOfFirst {
                it.opcode == Opcode.NEW_INSTANCE && it.getReference<TypeReference>()?.type == shutterType
            }
            val tagged = code.indexOfLast { instruction ->
                instruction.opcode == Opcode.INVOKE_VIRTUAL && instruction.getReference<MethodReference>()?.let {
                    it.definingClass == "Landroid/view/View;" && it.name == "setTag"
                } == true
            }
            if (created < 0 || tagged < created) throw PatchException("Instants camera setup has no shutter and tag")
            val shutterRegister = code[created].registersUsed.first()
            val previewRegister = code[tagged].registersUsed.first()
            val overwritten = (created + 1 until tagged).any { index ->
                val instruction = code[index]
                val written = instruction.registersUsed.firstOrNull()
                instruction.opcode.setsRegister() && written != null &&
                    (written == shutterRegister || instruction.opcode.setsWideRegister() && written + 1 == shutterRegister)
            }
            if (overwritten || shutterRegister > 15) throw PatchException("Instants shutter register is not kept")

            val preview = InstantsPreviewMarginFingerprint.originalClassDef.type
            uploadStub("capturePhotoNative").method.addInstructions(
                0,
                """
                check-cast p0, $shutterType
                iget-object p0, p0, $photoField
                invoke-interface/range { p0 .. p8 }, $photoHandler
                return-void
                """.trimIndent(),
            )
            uploadStub("captureVideoNative").method.addInstructions(
                0,
                """
                check-cast p0, $shutterType
                iget-object p0, p0, $videoField
                invoke-interface { p0, p1 }, $FUNCTION1->invoke(Ljava/lang/Object;)Ljava/lang/Object;
                return-void
                """.trimIndent(),
            )
            uploadStub("maxVideoMillisNative").method.addInstructions(
                0,
                """
                check-cast p0, $shutterType
                iget-wide v0, p0, $maxVideoField
                return-wide v0
                """.trimIndent(),
            )
            uploadStub("releasedNative").method.addInstructions(
                0,
                """
                check-cast p0, $shutterType
                iget-boolean p0, p0, $releasedField
                return p0
                """.trimIndent(),
            )
            uploadStub("horizontalMarginNative").method.addInstructions(
                0,
                """
                check-cast p0, $preview
                invoke-virtual { p0 }, $preview->getCameraHorizontalMarginPx()F
                move-result p0
                return p0
                """.trimIndent(),
            )
            uploadStub("verticalOffsetNative").method.addInstructions(
                0,
                """
                check-cast p0, $preview
                invoke-virtual { p0 }, $preview->getVerticalCropOffset()F
                move-result p0
                return p0
                """.trimIndent(),
            )

            ready.addInstruction(
                tagged,
                "invoke-static { v$previewRegister, v$shutterRegister }, $INSTANTS_UPLOAD_DESCRIPTOR->" +
                    "onCameraReady(Landroid/view/View;Ljava/lang/Object;)V",
            )

            enableSettings("instantsUpload")
        }
    }
