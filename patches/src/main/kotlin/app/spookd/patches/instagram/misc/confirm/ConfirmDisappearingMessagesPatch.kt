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
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

private const val STRING = "Ljava/lang/String;"

// Turns disappearing messages on or off when a swipe up in a chat is released past the threshold.
private object DisappearingMessagesSwitchFingerprint : Fingerprint(
    parameters = emptyList(),
    returnType = "V",
    filters = listOf(
        string("disappearing_messages"),
        methodCall(parameters = listOf(USER_SESSION_CLASS, "L"), returnType = "Z"),
        opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately()),
    ),
)

@Suppress("unused")
val confirmDisappearingMessagesPatch =
    patch(
        name = "Confirm disappearing messages",
        description = "Adds an option to ask before swiping up in a chat turns disappearing messages on or off.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch)

        execute {
            val method = DisappearingMessagesSwitchFingerprint.method
            // The swipe controller drives the chat's pull gesture; this tells it apart from other users of the string.
            val swipeController = classDefBy(method.definingClass).fields.any {
                it.type == "Lcom/instagram/ui/widget/refresh/RefreshableNestedScrollingParent;"
            }
            if (AccessFlags.STATIC.isSet(method.accessFlags) || !swipeController) {
                throw PatchException("Disappearing messages switch not found")
            }
            val added = method.addConfirmGate(
                2,
                """
                move-object/from16 v0, p0
                const-string v1, "${method.name}"
                invoke-static {v0, v1}, $ACTION_CONFIRM->allowDisappearingMessagesSwitch(Ljava/lang/Object;$STRING)Z
                move-result v0
                """,
            )
            if (!added) throw PatchException("Disappearing messages switch has too few registers")
            enableSettings("confirmDisappearingMessages")
        }
    }
