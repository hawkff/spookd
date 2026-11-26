/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.dm

import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.Constants.PATCHES_DESCRIPTOR
import app.spookd.patches.instagram.utils.Constants.USER_SESSION_CLASS
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstructions
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.string
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val EXTENSION_CLASS = "$PATCHES_DESCRIPTOR/dm/HiddenChatsShare;"

// The share sheet's recipient adapter; the only class that tags the new group button this way.
internal object ShareSheetAdapterFingerprint : Fingerprint(
    filters = listOf(string("direct_share_sheet_new_group_pog_view")),
)

// Fills the share sheet's rows from its finished lists, after Instagram's late additions like the media author.
internal object ShareSheetListsFingerprint : Fingerprint(
    classFingerprint = ShareSheetAdapterFingerprint,
    returnType = "V",
    parameters = List(6) { "Ljava/util/List;" } + "Z",
)

// Stores the query the share sheet shows; it is set before the rows of a search are filled.
internal object ShareSheetQueryFingerprint : Fingerprint(
    classFingerprint = ShareSheetAdapterFingerprint,
    returnType = "V",
    parameters = listOf("Ljava/lang/String;", "Z", "Z"),
    filters = listOf(fieldAccess(opcode = Opcode.IPUT_OBJECT, type = "Ljava/lang/String;")),
)

// Builds the rows of the recipient pickers, like the new message screen; its class is the only one with this row key.
private object RecipientRowsFingerprint : Fingerprint(
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(string("_community_row_")),
)

// Puts one recipient into the sections of a picker; the flag is true for a suggestion and false for a search result.
private object RecipientAddFingerprint : Fingerprint(
    classFingerprint = RecipientRowsFingerprint,
    returnType = "V",
    parameters = listOf("Lcom/instagram/model/direct/DirectShareTarget;", "Z"),
)

// Instagram's chat store returns its cached chat for a chat id here.
private object ThreadStoreGetThreadFingerprint : Fingerprint(
    returnType = "L",
    parameters = listOf("Ljava/lang/String;"),
    filters = listOf(string("DirectThreadStoreImpl.getThread")),
)

private object CachedThreadExtensionFingerprint : Fingerprint(
    definingClass = EXTENSION_CLASS,
    name = "cachedThread",
    parameters = listOf(USER_SESSION_CLASS, "Ljava/lang/String;"),
    returnType = "Ljava/lang/Object;",
)

@Suppress("unused")
val hiddenChatsSharePatch =
    patch(
        name = "Hide hidden chats from share sheet",
        description = "Leaves hidden chats out of the share sheet's suggested recipients and the new message suggestions.",
        default = true,
    ) {
        dependsOn(hiddenChatsPatch)
        compatibleWith(COMPATIBILITY_INSTAGRAM)

        execute {
            val query = ShareSheetQueryFingerprint.instructionMatches.first().instruction.getReference<FieldReference>()!!
            // Each of the six lists is replaced by its filtered copy; the selected recipients are kept apart and stay.
            val lists = (1..6).joinToString("\n") {
                """
                invoke-static {p$it, v0}, $EXTENSION_CLASS->filterTargets(Ljava/util/List;Ljava/lang/String;)Ljava/util/List;
                move-result-object p$it
                """.trimIndent()
            }
            ShareSheetListsFingerprint.method.addInstructions(
                0,
                """
                iget-object v0, p0, $query
                invoke-static {p0, v0}, $EXTENSION_CLASS->defaultListAccount(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/String;
                move-result-object v0
                """.trimIndent() + "\n" + lists,
            )

            // Chats hidden before their people were kept are looked up once per account in the store's cache.
            val getThread = ThreadStoreGetThreadFingerprint.originalMethod
            val store = ThreadStoreGetThreadFingerprint.classDef.interfaces.first { type ->
                classDefBy(type).methods.any {
                    it.name == getThread.name && it.returnType == getThread.returnType &&
                        it.parameters.map { parameter -> parameter.type } == listOf("Ljava/lang/String;")
                }
            }
            val storeOf = Fingerprint(
                isStatic = true,
                returnType = store,
                parameters = listOf(USER_SESSION_CLASS),
                filters = listOf(methodCall(name = "getScopedLazy")),
            ).originalMethod
            CachedThreadExtensionFingerprint.method.apply {
                removeInstructions(0, instructions.size)
                addInstructions(
                    0,
                    """
                    invoke-static/range {p0 .. p0}, $storeOf
                    move-result-object p0
                    invoke-interface/range {p0 .. p1}, $store->${getThread.name}(Ljava/lang/String;)${getThread.returnType}
                    move-result-object p0
                    return-object p0
                    """.trimIndent(),
                )
            }

            val session = RecipientRowsFingerprint.classDef.fields.first { it.type == USER_SESSION_CLASS }
            RecipientAddFingerprint.method.apply {
                addInstructionsWithLabels(
                    0,
                    """
                    iget-object v0, p0, $session
                    invoke-static {v0, p1, p2}, $EXTENSION_CLASS->skipRecipient(Ljava/lang/Object;Ljava/lang/Object;Z)Z
                    move-result v0
                    if-eqz v0, :spookd_recipient
                    return-void
                    """.trimIndent(),
                    ExternalLabel("spookd_recipient", getInstruction(0)),
                )
            }
        }
    }
