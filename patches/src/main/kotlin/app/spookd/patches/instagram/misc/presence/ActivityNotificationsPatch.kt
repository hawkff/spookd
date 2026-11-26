/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.presence

import app.spookd.patches.instagram.misc.dm.MenuButtonActionFingerprint
import app.spookd.patches.instagram.misc.dm.MenuButtonsEnumFingerprint
import app.spookd.patches.instagram.misc.dm.MenuButtonsLabelsFingerprint
import app.spookd.patches.instagram.misc.dm.MenuOpenFingerprint
import app.spookd.patches.instagram.misc.dm.passReturnedValues
import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.Constants.PATCHES_DESCRIPTOR
import app.spookd.patches.instagram.utils.Constants.USER_SESSION_CLASS
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionFilter
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstructions
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.string
import app.morphe.patcher.util.addInstructionsAtControlFlowLabel
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.p0Register
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val EXTENSION_CLASS = "$PATCHES_DESCRIPTOR/dm/presence/ActivityAlerts;"
private const val STRING = "Ljava/lang/String;"
private const val THREAD_KEY_CLASS = "Lcom/instagram/model/direct/DirectThreadKey;"

// MobileConfig long 0x821480000322c9: Instagram's wait in ms between active status refreshes while the app is open.
private const val PRESENCE_REFRESH_INTERVAL = 36614286960960201L

// Instagram's presence store tells its listeners about its map of presence states by user id after every change.
private object PresenceStoreNotifyFingerprint : Fingerprint(
    isStatic = true,
    returnType = "V",
    parameters = listOf("L", "Ljava/util/Map;"),
    filters = listOf(
        string("PresenceStore"),
        string("notify new presence state"),
    ),
)

// Writes a presence state as JSON; the field read right after "is_active" is whether the person is active.
private object PresenceStateJsonFingerprint : Fingerprint(
    returnType = STRING,
    parameters = emptyList(),
    strings = listOf("PresenceState"),
    filters = listOf(
        string("is_active"),
        fieldAccess(opcode = Opcode.IGET_BOOLEAN, type = "Z", location = MatchAfterImmediately()),
        string("lastActivityAtMs"),
    ),
)

// Handles a realtime typing indicator; the static call with the chat, the sender and their activity stores it.
private object TypingIndicatorFingerprint : Fingerprint(
    name = "handleRealtimeOperation",
    returnType = "V",
    filters = listOf(
        string("indicate_activity: in addOrReplaceDirectActivityIndicator"),
        methodCall(opcode = Opcode.INVOKE_STATIC, returnType = "V", parameters = listOf("L", "L", "L", "I")),
    ),
)

// Names Instagram's read receipt delta, whose constructor takes the receipt first.
private object ReadReceiptApplicatorFingerprint : Fingerprint(
    name = "getTAG",
    returnType = STRING,
    parameters = emptyList(),
    filters = listOf(string("IGThreadReadReceiptDeltaApplicator")),
)

// Requests who is active now; its class runs Instagram's active status refreshes.
private object PresenceActiveNowRequestFingerprint : Fingerprint(
    filters = listOf(string("direct_v2/get_presence_active_now/")),
)

private fun refreshDelayFilters(): List<InstructionFilter> = listOf(
    literal(PRESENCE_REFRESH_INTERVAL),
    methodCall(smali = "Lcom/facebook/mobileconfig/factory/MobileConfigUnsafeContext;->Che(J)J"),
    opcode(Opcode.MOVE_RESULT_WIDE, location = MatchAfterImmediately()),
)

// Schedules the first refresh when the app comes to the foreground.
private object PresenceRefreshStartFingerprint : Fingerprint(
    classFingerprint = PresenceActiveNowRequestFingerprint,
    isStatic = true,
    returnType = "V",
    filters = refreshDelayFilters(),
)

// Each refresh asks for who is active now, then schedules the next one.
private fun presenceRefreshFingerprint(manager: String) = Fingerprint(
    name = "run",
    returnType = "V",
    parameters = emptyList(),
    filters = listOf<InstructionFilter>(methodCall(definingClass = manager, opcode = Opcode.INVOKE_STATIC, returnType = "V")) +
        refreshDelayFilters(),
)

/** Passes the refresh wait Instagram just read through the chosen interval. */
private fun Fingerprint.hookRefreshDelay() {
    val index = instructionMatches.last().index
    val wait = method.getInstruction<OneRegisterInstruction>(index).registerA
    if (wait > 14) throw PatchException("Presence refresh wait is in a high register")
    method.addInstructions(
        index + 1,
        """
        invoke-static {v$wait, v${wait + 1}}, $EXTENSION_CLASS->presenceRefreshMs(J)J
        move-result-wide v$wait
        """.trimIndent(),
    )
}

/** Rewrites an extension stub to cast its only argument to [type] and return what [read] puts in v0. */
private fun replaceStub(name: String, type: String, read: String, returnOpcode: String) {
    Fingerprint(definingClass = EXTENSION_CLASS, name = name).method.apply {
        removeInstructions(0, instructions.size)
        addInstructions(0, "check-cast p0, $type\n$read\n$returnOpcode v0")
    }
}

@Suppress("unused")
val activityNotificationsPatch =
    patch(
        name = "Activity notifications",
        description = "Adds an option to the chat menu in the inbox to get notified, while Instagram is running, " +
            "when the person comes online, goes offline, types to you or reads your message, " +
            "and a setting for how often Instagram refreshes active status.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch)

        execute {
            // Presence: whether a state says active, and every change of the store, with the store's account.
            val isActive = PresenceStateJsonFingerprint.instructionMatches[1].fieldReference
            if (isActive.definingClass != PresenceStateJsonFingerprint.originalMethod.definingClass) {
                throw PatchException("Unexpected presence active field $isActive")
            }
            replaceStub("presenceActive", isActive.definingClass, "iget-boolean v0, p0, $isActive", "return")

            PresenceStoreNotifyFingerprint.method.apply {
                val storeFields = classDefBy(parameterTypes.first().toString()).fields
                val session = storeFields.firstOrNull { it.type == USER_SESSION_CLASS }
                    ?: throw PatchException("Presence store has no session")
                // Updates pass the store's own map; the session ending passes another one.
                val storeMap = storeFields.singleOrNull { it.type == "Ljava/util/Map;" }
                    ?: throw PatchException("Presence store has no single map")
                // v0 and v1 are free at the start and the method has two parameters, so p1 sits in a low register.
                if (implementation!!.registerCount !in 4..16) throw PatchException("Unexpected presence store registers")
                addInstructions(
                    0,
                    """
                    iget-object v0, p0, $session
                    iget-object v1, p0, $storeMap
                    invoke-static {v0, p1, v1}, $EXTENSION_CLASS->onPresence(Ljava/lang/Object;Ljava/util/Map;Ljava/util/Map;)V
                    """.trimIndent(),
                )
            }

            // Typing: the store call takes the chat and the sender as id pairs, whose id is the first text the pair is made with.
            val typingStore = TypingIndicatorFingerprint.instructionMatches[1].methodReference
            val typingTypes = typingStore.parameterTypes.map(CharSequence::toString)
            val pair = typingTypes[1]
            if (typingTypes[2] != pair) throw PatchException("Unexpected typing indicator store $typingStore")
            val pairInit = classDefBy(pair).methods.firstOrNull {
                it.name == "<init>" && it.parameterTypes.map(CharSequence::toString) == listOf(STRING, STRING)
            }?.implementation ?: throw PatchException("Id pair has no constructor")
            val firstText = pairInit.registerCount - 2
            val pairId = pairInit.instructions.firstOrNull {
                it.opcode == Opcode.IPUT_OBJECT && (it as TwoRegisterInstruction).registerA == firstText
            }?.getReference<FieldReference>() ?: throw PatchException("Id pair has no id field")
            replaceStub("pairId", pair, "iget-object v0, p0, $pairId", "return-object")

            mutableClassDefBy(typingStore.definingClass).methods.first {
                it.name == typingStore.name && it.parameterTypes.map(CharSequence::toString) == typingTypes
            }.apply {
                // Static, so p1 .. p3 are the chat, the sender and the activity.
                if (implementation!!.registerCount > 16) throw PatchException("Typing indicator store has too many registers")
                addInstructions(0, "invoke-static {p1, p2, p3}, $EXTENSION_CLASS->onTyping(Ljava/lang/Object;Ljava/lang/Object;I)V")
            }

            // Read receipts: who read, when (ms, as text) and the chat are the first, second and last texts of the receipt.
            val receipt = ReadReceiptApplicatorFingerprint.classDef.methods.firstOrNull { it.name == "<init>" }
                ?.parameterTypes?.firstOrNull()?.toString() ?: throw PatchException("Read receipt delta has no receipt")
            val receiptTypes = listOf("L") + List(7) { STRING } + "J"
            mutableClassDefBy(receipt).methods.firstOrNull { method ->
                method.name == "<init>" && method.parameterTypes.map(CharSequence::toString).let { types ->
                    types.size == receiptTypes.size && types.zip(receiptTypes).all { (type, prefix) -> type.startsWith(prefix) }
                }
            }?.apply {
                // p8 is the register count minus the 11 parameter registers (the long takes two) plus 8.
                if (implementation!!.registerCount > 18) throw PatchException("Read receipt has too many registers")
                val returns = instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }.map { it.index }
                if (returns.isEmpty()) throw PatchException("Read receipt has no return to hook")
                for (index in returns.reversed()) {
                    addInstructionsAtControlFlowLabel(
                        index,
                        "invoke-static {p2, p3, p8}, $EXTENSION_CLASS->onReadReceipt($STRING$STRING$STRING)V",
                    )
                }
            } ?: throw PatchException("Unexpected read receipt constructor")

            // Refresh interval: the wait before the first refresh and before each next one.
            PresenceRefreshStartFingerprint.hookRefreshDelay()
            presenceRefreshFingerprint(PresenceActiveNowRequestFingerprint.classDef.type).hookRefreshDelay()

            // Chat menu entry, as Choose chats for DM ghost adds its own.
            MenuOpenFingerprint.method.apply {
                val list = parameters.indexOfFirst { it.type == "Ljava/util/List;" }
                val key = parameters.indexOfFirst { it.type == THREAD_KEY_CLASS }
                val title = parameters.indexOfFirst { it.type == STRING }
                if (list < 0 || key < 0 || title < 0) throw PatchException("Chat menu opener has no list, chat key or title")
                // The method is static and its parameters sit far above v0, so they are copied down to be passed on.
                addInstructions(
                    0,
                    """
                    move-object/from16 v0, p$list
                    move-object/from16 v1, p$key
                    move-object/from16 v2, p$title
                    invoke-static {v0, v1, v2}, $EXTENSION_CLASS->menuOpening(Ljava/util/List;Ljava/lang/Object;Ljava/lang/String;)Ljava/util/List;
                    move-result-object v0
                    move-object/from16 p$list, v0
                    """.trimIndent(),
                )
            }

            MenuButtonsLabelsFingerprint.method.apply {
                val list = p0Register + parameters.indexOfFirst { it.type == "Ljava/util/List;" }
                if (passReturnedValues(list, "$EXTENSION_CLASS->menuLabels(Ljava/util/List;[Ljava/lang/String;)[Ljava/lang/String;") == 0) {
                    throw PatchException("Chat menu labels have no return to hook")
                }
            }

            val buttonEnum = MenuButtonsEnumFingerprint.classDef.type
            MenuButtonActionFingerprint.apply {
                val session = classDef.fields.first { it.type == USER_SESSION_CLASS }
                method.apply {
                    val button = parameters.indexOfFirst { it.type == buttonEnum } + 1
                    if (button < 1) throw PatchException("Chat menu press has no button")
                    // Registers are fixed because the code goes first and nothing is live yet.
                    addInstructionsWithLabels(
                        0,
                        """
                        move-object/from16 v0, p0
                        iget-object v1, v0, $session
                        move-object/from16 v2, p$button
                        invoke-static {v1, v2}, $EXTENSION_CLASS->onMenuButton(Ljava/lang/Object;Ljava/lang/Object;)Z
                        move-result v0
                        if-eqz v0, :spookd_activity_menu
                        return-void
                        """.trimIndent(),
                        ExternalLabel("spookd_activity_menu", getInstruction(0)),
                    )
                }
            }

            enableSettings("activityAlerts")
        }
    }
