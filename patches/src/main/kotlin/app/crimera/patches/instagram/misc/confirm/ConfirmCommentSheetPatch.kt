/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.confirm

import app.crimera.patches.instagram.misc.settings.settingsPatch
import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.crimera.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.patch.patch
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

private const val STRING = "Ljava/lang/String;"

// Send action of the comment sheet: it logs the tap, adds the comment to the list and clears the box.
private object CommentSheetSendFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/app/Activity;", "L", "Ljava/lang/Boolean;"),
    filters = listOf(
        // The comment's text, read from the comment the second argument describes.
        fieldAccess(opcode = Opcode.IGET_OBJECT, type = STRING),
        string("instagram_comment_send_button_tap"),
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
            val match = CommentSheetSendFingerprint.matchOrNull() ?: return@execute
            val method = match.method
            val text = match.instructionMatches.first().fieldReference
            if (AccessFlags.STATIC.isSet(method.accessFlags) || text.definingClass != method.parameterTypes[1].toString()) {
                return@execute
            }
            val added = method.addConfirmGate(
                4,
                """
                filled-new-array/range {p0 .. p3}, [Ljava/lang/Object;
                move-result-object v0
                const-string v1, "${text.name}"
                const-class v2, ${method.definingClass}
                const-string v3, "${method.name}"
                invoke-static {v0, v1, v2, v3}, $ACTION_CONFIRM->allowCommentSend([Ljava/lang/Object;${STRING}Ljava/lang/Class;$STRING)Z
                move-result v0
                """,
            )
            if (added) enableSettings("confirmCommentSheet")
        }
    }
