/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.confirm

import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.literal
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import com.android.tools.smali.dexlib2.AccessFlags

private const val STRING = "Ljava/lang/String;"

// Likes or unlikes a comment: (current reaction, post, comment id, String, Z). The state change runs later in a coroutine.
private object CommentLikeActionFingerprint : Fingerprint(
    definingClass = "Lcom/instagram/comments/mvvm/data/MediaCommentListRepository;",
    returnType = "V",
    parameters = listOf("L", "L", STRING, STRING, "Z"),
    filters = listOf(literal(0x81102a000756f2L)),
)

@Suppress("unused")
val confirmCommentLikePatch =
    patch(
        name = "Confirm comment likes",
        description = "Adds an option to ask before liking a comment.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch)

        execute {
            val method = CommentLikeActionFingerprint.method
            if (AccessFlags.STATIC.isSet(method.accessFlags)) throw PatchException("Comment like action is static")
            val added = method.addConfirmGate(
                3,
                """
                filled-new-array/range {p0 .. p4}, [Ljava/lang/Object;
                move-result-object v0
                move/from16 v1, p5
                const-string v2, "${method.name}"
                invoke-static {v0, v1, v2}, $ACTION_CONFIRM->allowCommentLike([Ljava/lang/Object;Z$STRING)Z
                move-result v0
                """,
            )
            if (!added) throw PatchException("Comment like action has too few registers")
            enableSettings("confirmCommentLike")
        }
    }
