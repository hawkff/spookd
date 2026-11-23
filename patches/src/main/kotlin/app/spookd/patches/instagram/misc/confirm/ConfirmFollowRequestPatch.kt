/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.confirm

import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.Constants.USER_SESSION_CLASS
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.patch
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val STRING = "Ljava/lang/String;"
private const val CONFIRM_FOLLOWER_UTIL = "Lcom/instagram/user/follow/ConfirmFollowerUtil;"

// Static accept of a follow request, reached from Activity and the follow requests list.
private object FollowRequestAcceptFingerprint : Fingerprint(
    definingClass = CONFIRM_FOLLOWER_UTIL,
    returnType = "V",
    parameters = listOf("Landroidx/fragment/app/FragmentActivity;", "L", USER_SESSION_CLASS, "L", "L", "Z"),
)

// Click of a row in the follow requests list, which updates the row right after the accept call.
private object FollowRequestRowClickFingerprint : Fingerprint(
    name = "onClick",
    returnType = "V",
    parameters = listOf("Landroid/view/View;"),
    filters = listOf(
        string("follow_request_tapped"),
        string("follow_request_dismiss_tapped"),
    ),
)

@Suppress("unused")
val confirmFollowRequestPatch =
    patch(
        name = "Confirm accepting follow requests",
        description = "Adds an option to ask before a follow request is accepted in Activity or the follow requests list.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch)

        execute {
            val method = FollowRequestAcceptFingerprint.matchOrNull()?.method ?: return@execute
            if (!AccessFlags.STATIC.isSet(method.accessFlags)) return@execute
            val added = method.addConfirmGate(
                4,
                """
                filled-new-array/range {p0 .. p4}, [Ljava/lang/Object;
                move-result-object v0
                move/from16 v1, p5
                const-class v2, $CONFIRM_FOLLOWER_UTIL
                const-string v3, "${method.name}"
                invoke-static {v0, v1, v2, v3}, $ACTION_CONFIRM->allowFollowRequest([Ljava/lang/Object;ZLjava/lang/Class;$STRING)Z
                move-result v0
                """,
            )
            if (!added) return@execute

            // Asking before the list's click keeps a declined prompt from leaving the row looking accepted.
            FollowRequestRowClickFingerprint.matchOrNull()?.method?.takeIf { click ->
                click.implementation!!.instructions.any {
                    ((it as? ReferenceInstruction)?.reference as? MethodReference)?.definingClass == CONFIRM_FOLLOWER_UTIL
                }
            }?.addConfirmGate(
                2,
                """
                move-object/from16 v0, p1
                move-object/from16 v1, p0
                invoke-static {v0, v1}, $ACTION_CONFIRM->allowFollowRequestClick(Landroid/view/View;Ljava/lang/Object;)Z
                move-result v0
                """,
            )
            enableSettings("confirmFollowRequest")
        }
    }
