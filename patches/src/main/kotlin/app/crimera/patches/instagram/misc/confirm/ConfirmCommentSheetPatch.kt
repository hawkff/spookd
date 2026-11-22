/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.confirm

import app.crimera.patches.instagram.misc.settings.settingsPatch
import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.crimera.patches.instagram.utils.Constants.USER_SESSION_CLASS
import app.crimera.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.patch
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

private const val STRING = "Ljava/lang/String;"

// Static send of the comment sheet: it trims the text, resets the composer's counters, posts and
// then clears the composer, so gating it at the start leaves a cancelled draft untouched.
private object CommentSheetSendFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(
        "Landroidx/fragment/app/FragmentActivity;", "L", "L", "L", "Lcom/instagram/common/gallery/Medium;",
        USER_SESSION_CLASS, "L", "L", "L", "L", STRING, STRING, STRING, "Z",
    ),
    filters = listOf(
        string("\\n{2,}"),
        string("comment"),
    ),
)

@Suppress("unused")
val confirmCommentSheetPatch =
    patch(
        name = "Confirm comments in the comment sheet",
        description = "Adds an option to ask before a comment written in the comment sheet is posted.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch)

        execute {
            val method = CommentSheetSendFingerprint.matchOrNull()?.method ?: return@execute
            if (!AccessFlags.STATIC.isSet(method.accessFlags)) return@execute
            val added = method.addConfirmGate(
                4,
                """
                filled-new-array/range {p0 .. p12}, [Ljava/lang/Object;
                move-result-object v0
                move/from16 v1, p13
                const-class v2, ${method.definingClass}
                const-string v3, "${method.name}"
                invoke-static {v0, v1, v2, v3}, $ACTION_CONFIRM->allowCommentSend([Ljava/lang/Object;ZLjava/lang/Class;$STRING)Z
                move-result v0
                """,
            )
            if (added) enableSettings("confirmCommentSheet")
        }
    }
