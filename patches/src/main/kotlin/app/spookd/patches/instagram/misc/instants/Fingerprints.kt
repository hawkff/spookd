/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.instants

import app.spookd.patches.instagram.entity.decoder.MEDIA_CLASS_NAME
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.string
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.Opcode

internal const val QUICK_SNAP_API_CLASS = "Lcom/instagram/quicksnap/data/api/QuickSnapApi;"
internal const val QUICK_SNAP_CAMERA_VIEW_MODEL_CLASS = "Lcom/instagram/quicksnap/camera/domain/QuickSnapCameraViewModel;"
private const val MEDIUM_CLASS = "Lcom/instagram/common/gallery/Medium;"

/**
 * The app's per-instant item, which has the view modes of an instant.
 */
internal object InstantItemFingerprint : Fingerprint(
    name = "hashCode",
    strings = listOf("NUX_VIDEO", "ONCE", "PERMANENT", "REPLAYABLE"),
)

internal object InstantMediaItemFingerprint : Fingerprint(
    name = "hashCode",
    returnType = "I",
    parameters = emptyList(),
    filters = listOf(
        fieldAccess(definingClass = "this", type = MEDIA_CLASS_NAME, opcode = Opcode.IGET_OBJECT),
        string("NUX_VIDEO"),
    ),
)

/**
 * The per-instant item is built from that instant's Media, its first parameter. Hooking its constructor catches every
 * instant as it is created and hands the Media straight to the extension.
 */
internal fun instantItemConstructorFingerprint(itemType: String) = Fingerprint(
    definingClass = itemType,
    name = "<init>",
)

/** The per-instant item's constructor; p1 is that instant's Media. */
internal fun instantItemConstructor(): MutableMethod {
    val itemType = (InstantItemFingerprint.matchOrNull()
        ?: InstantMediaItemFingerprint.match()).originalClassDef.type
    val constructor = instantItemConstructorFingerprint(itemType).method
    if (constructor.parameterTypes.firstOrNull() != MEDIA_CLASS_NAME) {
        throw PatchException("Expected the instant item's first constructor parameter to be Media")
    }
    return constructor
}

/** Reports Instants as seen: a suspending call with the media ids and its continuation. */
internal object InstantsSeenReportFingerprint : Fingerprint(
    definingClass = QUICK_SNAP_API_CLASS,
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/util/List;", "L"),
    strings = listOf("IGQuickSnapUpdateSeenStateMutation"),
)

/** Sends an emoji reaction to an Instant: a suspending call with the media id, the emoji and its continuation. */
internal object InstantReactionSendFingerprint : Fingerprint(
    definingClass = QUICK_SNAP_API_CLASS,
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/String;", "Ljava/lang/String;", "L"),
    strings = listOf("IGQuickSnapSendEmojiReactionMutation"),
)

/**
 * Sends a captured Instant from the Instants camera: a static method taking the capture and the camera's view model.
 * It first checks whether the capture's Medium is a video.
 */
internal object InstantSendFingerprint : Fingerprint(
    definingClass = QUICK_SNAP_CAMERA_VIEW_MODEL_CLASS,
    returnType = "V",
    parameters = listOf("L", QUICK_SNAP_CAMERA_VIEW_MODEL_CLASS),
    filters = listOf(
        fieldAccess(type = MEDIUM_CLASS, opcode = Opcode.IGET_OBJECT),
        methodCall(definingClass = MEDIUM_CLASS, parameters = emptyList(), returnType = "Z", location = MatchAfterWithin(3)),
        string("ig_quicksnap"),
    ),
)
