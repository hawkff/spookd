/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.confirm

import app.spookd.patches.instagram.misc.navigation.HomeScrollToTopFingerprint
import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.classDefByOrNull
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val STRING = "Ljava/lang/String;"
private const val FUNCTION0 = "Lkotlin/jvm/functions/Function0;"

// Reels' refresh; true means a pull to refresh. When refreshes come too fast it only stops its spinner.
private object ReelsRefreshFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Z"),
    filters = listOf(
        string("rate_limited"),
        string("validate_request_result"),
    ),
)

// The inbox's pull-to-refresh runnable. When refreshes come too fast it posts its own spinner stop.
private object InboxRefreshFingerprint : Fingerprint(
    name = "run",
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(
        methodCall(definingClass = "Landroid/os/Handler;", name = "postDelayed"),
        string("ptr_path"),
        string("snapshot_qs_disabled"),
    ),
)

@Suppress("unused")
val confirmRefreshPatch =
    patch(
        name = "Confirm refresh",
        description = "Adds an option to ask before a pull to refresh reloads Home, Reels or the inbox.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch)

        execute {
            // Home: the refresh-with-reason method that the scroll to top calls once its cooldown check passes.
            val scrollToTop = HomeScrollToTopFingerprint.originalMethod
            val feedClass = scrollToTop.definingClass
            val refreshCall = scrollToTop.implementation!!.instructions
                .drop(HomeScrollToTopFingerprint.instructionMatches[2].index)
                .firstNotNullOfOrNull { instruction ->
                    ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.takeIf {
                        instruction.opcode == Opcode.INVOKE_VIRTUAL && it.definingClass == feedClass &&
                            it.parameterTypes.size == 1 && it.returnType == "V"
                    }
                } ?: throw PatchException("Home refresh call not found")
            val feedRefresh = mutableClassDefBy(feedClass).methods.single {
                it.name == refreshCall.name && it.returnType == "V" &&
                    it.parameterTypes.map(CharSequence::toString) == refreshCall.parameterTypes.map(CharSequence::toString)
            }
            // The fragment's spinner: its only field whose interface can set the loading state.
            val loading = mutableClassDefBy(feedClass).fields.singleOrNull { field ->
                classDefByOrNull(field.type)?.let { type ->
                    AccessFlags.INTERFACE.isSet(type.accessFlags) &&
                        type.methods.any { it.name == "setIsLoading" && it.parameterTypes.map(CharSequence::toString) == listOf("Z") }
                } == true
            } ?: throw PatchException("Home spinner field not found")
            val feedAdded = feedRefresh.addConfirmGate(
                4,
                """
                move-object/from16 v0, p0
                iget-object v1, v0, ${loading.definingClass}->${loading.name}:${loading.type}
                move-object/from16 v2, p1
                const-string v3, "${feedRefresh.name}"
                invoke-static {v0, v2, v1, v3}, $ACTION_CONFIRM->allowFeedRefresh(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;$STRING)Z
                move-result v0
                """,
            )
            if (!feedAdded) throw PatchException("Home refresh has too few registers")

            // Reels: the spinner stop it calls itself, the Function0 field read right before its first return.
            val reelsRefresh = ReelsRefreshFingerprint.method
            val reelsInstructions = reelsRefresh.implementation!!.instructions
            val firstReturn = reelsInstructions.indexOfFirst { it.opcode == Opcode.RETURN_VOID }
            val stopRead = reelsInstructions.getOrNull(firstReturn - 3)
            val stop = ((stopRead as? ReferenceInstruction)?.reference as? FieldReference)?.takeIf {
                stopRead?.opcode == Opcode.IGET_OBJECT && it.type == FUNCTION0 && it.definingClass == reelsRefresh.definingClass
            } ?: throw PatchException("Reels spinner stop not found")
            val reelsAdded = reelsRefresh.addConfirmGate(
                4,
                """
                move-object/from16 v0, p0
                iget-object v1, v0, ${stop.definingClass}->${stop.name}:${stop.type}
                move/from16 v2, p1
                const-string v3, "${reelsRefresh.name}"
                invoke-static {v0, v2, v1, v3}, $ACTION_CONFIRM->allowReelsRefresh(Ljava/lang/Object;ZLjava/lang/Object;$STRING)Z
                move-result v0
                """,
            )
            if (!reelsAdded) throw PatchException("Reels refresh has too few registers")

            // Inbox: the runnable's own spinner stop is built from the inbox controller it holds.
            val inboxRefresh = InboxRefreshFingerprint.method
            val inboxInstructions = inboxRefresh.implementation!!.instructions
            val controllerRead = inboxInstructions.first()
            val controller = ((controllerRead as? ReferenceInstruction)?.reference as? FieldReference)?.takeIf {
                controllerRead.opcode == Opcode.IGET_OBJECT && it.definingClass == inboxRefresh.definingClass
            } ?: throw PatchException("Inbox refresh controller not found")
            val post = InboxRefreshFingerprint.instructionMatches[0].index
            val postedRegister = (inboxInstructions[post] as FiveRegisterInstruction).registerD
            val created = (post - 1 downTo 0).firstOrNull {
                inboxInstructions[it].opcode == Opcode.NEW_INSTANCE &&
                    (inboxInstructions[it] as OneRegisterInstruction).registerA == postedRegister
            } ?: throw PatchException("Inbox spinner stop not found")
            val stopType = ((inboxInstructions[created] as ReferenceInstruction).reference as TypeReference).type
            val stopInit = (inboxInstructions.getOrNull(created + 1) as? ReferenceInstruction)?.reference as? MethodReference
            if (stopInit?.definingClass != stopType || stopInit?.name != "<init>" ||
                stopInit?.parameterTypes?.map(CharSequence::toString) != listOf(controller.type)
            ) {
                throw PatchException("Inbox spinner stop has an unexpected constructor")
            }
            val inboxAdded = inboxRefresh.addConfirmGate(
                4,
                """
                move-object/from16 v3, p0
                iget-object v1, v3, ${controller.definingClass}->${controller.name}:${controller.type}
                new-instance v2, $stopType
                invoke-direct {v2, v1}, $stopType-><init>(${controller.type})V
                invoke-static {v3, v2}, $ACTION_CONFIRM->allowInboxRefresh(Ljava/lang/Runnable;Ljava/lang/Runnable;)Z
                move-result v0
                """,
            )
            if (!inboxAdded) throw PatchException("Inbox refresh has too few registers")

            enableSettings("confirmRefresh")
        }
    }
