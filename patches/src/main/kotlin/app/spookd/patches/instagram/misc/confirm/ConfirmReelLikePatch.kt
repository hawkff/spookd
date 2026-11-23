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
import app.morphe.patcher.patch.patch
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

private const val STRING = "Ljava/lang/String;"

// Reels like button tap. The last two arguments are "the button is enabled" and "already liked".
private object ReelLikeTapFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(
        "Landroid/view/View;", "L", "L", "L", STRING, STRING, STRING, STRING,
        "Lkotlin/jvm/functions/Function1;", "Z", "Z",
    ),
    filters = listOf(
        string("android_purge_26_q3_LikeActionHandler_handleLikeButtonTap"),
        string("clips_unlike"),
    ),
)

@Suppress("unused")
val confirmReelLikePatch =
    patch(
        name = "Confirm Reels likes",
        description = "Adds an option to ask before liking a Reel.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch)

        execute {
            val method = ReelLikeTapFingerprint.matchOrNull()?.method ?: return@execute
            if (AccessFlags.STATIC.isSet(method.accessFlags)) return@execute
            // The handler and its reference arguments travel in one array so the tap can be replayed.
            val added = method.addConfirmGate(
                5,
                """
                filled-new-array/range {p0 .. p9}, [Ljava/lang/Object;
                move-result-object v0
                move/from16 v1, p10
                move/from16 v2, p11
                const-class v3, ${method.definingClass}
                const-string v4, "${method.name}"
                invoke-static {v0, v1, v2, v3, v4}, $ACTION_CONFIRM->allowReelLike([Ljava/lang/Object;ZZLjava/lang/Class;$STRING)Z
                move-result v0
                """,
            )
            if (added) enableSettings("confirmReelLike")
        }
    }
