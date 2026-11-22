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
import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

private const val MEDIA = "Lcom/instagram/feed/media/Media;"

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
            val composer = method.parameterTypes.first().toString()
            if (!AccessFlags.STATIC.isSet(method.accessFlags) || composerText.definingClass != composer) {
                return@execute
            }
            // The post the comment goes to, so an approval for another post is dropped.
            val media = classDefBy(composer).fields.singleOrNull {
                it.type == MEDIA && !AccessFlags.STATIC.isSet(it.accessFlags)
            }?.let { "${it.definingClass}->${it.name}:${it.type}" } ?: return@execute
            val added = method.addConfirmGate(
                5,
                """
                move-object/from16 v0, p0
                invoke-virtual {v0}, $composerText
                move-result-object v1
                iget-object v2, v0, $media
                const-class v3, ${method.definingClass}
                const-string v4, "${method.name}"
                invoke-static {v0, v1, v2, v3, v4}, $ACTION_CONFIRM->allowComment(Ljava/lang/Object;Ljava/lang/CharSequence;Ljava/lang/Object;Ljava/lang/Class;Ljava/lang/String;)Z
                move-result v0
                """,
            )
            if (added) enableSettings("confirmComment")
        }
    }
