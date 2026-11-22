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
import app.morphe.patcher.patch.patch
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

private const val STRING = "Ljava/lang/String;"

// Static Reels repost action; the sixth argument is set when it undoes a repost.
private object ReelRepostActionFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/view/View;", "L", "L", "L", STRING, STRING, STRING, "I"),
    filters = listOf(
        string("android_purge_26_q3_RepostActionHandler_performRepostAction"),
        string("clips_repost"),
    ),
)

@Suppress("unused")
val confirmReelRepostPatch =
    patch(
        name = "Confirm Reels reposts",
        description = "Adds an option to ask before reposting a Reel.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch)

        execute {
            val method = ReelRepostActionFingerprint.matchOrNull()?.method ?: return@execute
            if (!AccessFlags.STATIC.isSet(method.accessFlags)) return@execute
            val added = method.addConfirmGate(
                4,
                """
                filled-new-array/range {p0 .. p6}, [Ljava/lang/Object;
                move-result-object v0
                move/from16 v1, p7
                const-class v2, ${method.definingClass}
                const-string v3, "${method.name}"
                invoke-static {v0, v1, v2, v3}, $ACTION_CONFIRM->allowReelRepost([Ljava/lang/Object;ILjava/lang/Class;$STRING)Z
                move-result v0
                """,
            )
            if (added) enableSettings("confirmReelRepost")
        }
    }
