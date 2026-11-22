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
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.patch
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

// Static post action of the comment composer; it reads the composer's text first.
private object CommentPostFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        methodCall(returnType = "Ljava/lang/CharSequence;", parameters = emptyList()),
        string("postCommentWithText mMedia == null"),
    ),
)

@Suppress("unused")
val confirmCommentPatch =
    patch(
        name = "Confirm comments",
        description = "Adds an option to ask before a comment is posted.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch)

        execute {
            val match = CommentPostFingerprint.matchOrNull() ?: return@execute
            val method = match.method
            val composerText = match.instructionMatches.first().methodReference
            if (!AccessFlags.STATIC.isSet(method.accessFlags) || composerText.definingClass != method.parameterTypes.first().toString()) {
                return@execute
            }
            val added = method.addConfirmGate(
                4,
                """
                move-object/from16 v0, p0
                invoke-virtual {v0}, $composerText
                move-result-object v1
                const-class v2, ${method.definingClass}
                const-string v3, "${method.name}"
                invoke-static {v0, v1, v2, v3}, $ACTION_CONFIRM->allowComment(Ljava/lang/Object;Ljava/lang/CharSequence;Ljava/lang/Class;Ljava/lang/String;)Z
                move-result v0
                """,
            )
            if (added) enableSettings("confirmComment")
        }
    }
