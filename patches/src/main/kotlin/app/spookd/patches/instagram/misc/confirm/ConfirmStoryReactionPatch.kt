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

// Sends a quick reaction from the story reply bar; the fifth argument is the emoji.
private object StoryQuickReactionFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(
        "Landroid/content/Context;", "Landroid/view/View;", "L", "Lcom/instagram/model/reels/ReelItem;", STRING, STRING,
    ),
    filters = listOf(
        string("flyingReactionController"),
        string("reply_with_quick_reaction"),
    ),
)

@Suppress("unused")
val confirmStoryReactionPatch =
    patch(
        name = "Confirm story quick reactions",
        description = "Adds an option to ask before a quick reaction is sent from a story's reply bar.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch)

        execute {
            val method = StoryQuickReactionFingerprint.matchOrNull()?.method ?: return@execute
            if (AccessFlags.STATIC.isSet(method.accessFlags)) return@execute
            val added = method.addConfirmGate(
                3,
                """
                filled-new-array/range {p0 .. p6}, [Ljava/lang/Object;
                move-result-object v0
                const-class v1, ${method.definingClass}
                const-string v2, "${method.name}"
                invoke-static {v0, v1, v2}, $ACTION_CONFIRM->allowStoryReaction([Ljava/lang/Object;Ljava/lang/Class;$STRING)Z
                move-result v0
                """,
            )
            if (added) enableSettings("confirmStoryReaction")
        }
    }
