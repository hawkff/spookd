/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.ghostMode

import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.Constants.USER_SESSION_CLASS
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.getFreeRegisterProvider
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

@Suppress("unused")
val markAsReadAfterReplyPatch = patch(
    name = "Mark as read after reply",
    description = "Adds an option to mark a chat as read when you reply, react, send a message or start typing in it " +
        "while viewing DMs anonymously.",
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)
    dependsOn(viewDmAnonymouslyPatch)

    execute {
        val seen = DMSeenFingerprint.method
        if (seen.parameterTypes.size != 5 || seen.parameterTypes[0] != USER_SESSION_CLASS ||
            !seen.parameterTypes[1].startsWith("L") ||
            seen.parameterTypes.drop(2).any { it != "Ljava/lang/String;" }
        ) {
            throw PatchException("Unexpected DM seen request parameters")
        }
        seen.addInstructions(
            0,
            "invoke-static/range {p0 .. p4}, $REPLY_READ_EXTENSION->onSeenRequest(${USER_SESSION_CLASS}Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V",
        )
        ReplyReadSendReceiptFingerprint.method.addInstructions(
            0,
            """
            check-cast p1, ${seen.parameterTypes[1]}
            invoke-static/range {p0 .. p4}, $seen
            """.trimIndent(),
        )

        // Messages share one send mutation class; every DM mutation, reactions included, extends its superclass.
        val sendClass = classDefBy(DmTextSendNameFingerprint.originalClassDef.superclass
            ?: throw PatchException("Could not resolve the DM send mutation class"))
        val mutationClass = classDefBy(sendClass.superclass
            ?: throw PatchException("Could not resolve the DM mutation class"))
        // Null unless the message is sent as a reply to another one.
        val repliedTo = sendClass.methods.singleOrNull { method ->
            !AccessFlags.STATIC.isSet(method.accessFlags) && method.parameterTypes.isEmpty() &&
                method.returnType.startsWith("L") && method.returnType != "Ljava/lang/Object;" &&
                method.implementation?.instructions?.map { it.opcode } == listOf(Opcode.CONST_4, Opcode.RETURN_OBJECT)
        } ?: throw PatchException("Could not uniquely resolve the DM send replied-to getter")
        // The chat key of a message or a reaction.
        val subject = mutationClass.methods.singleOrNull { method ->
            !AccessFlags.STATIC.isSet(method.accessFlags) && method.parameterTypes.isEmpty() &&
                method.returnType == "Ljava/lang/Object;"
        } ?: throw PatchException("Could not uniquely resolve the DM mutation subject getter")
        val typeName = mutationClass.methods.singleOrNull { method ->
            method.name == "getTypeName" && method.parameterTypes.isEmpty() && method.returnType == "Ljava/lang/String;"
        } ?: throw PatchException("Could not resolve the DM mutation type name")

        fun replaceStub(name: String, body: String) =
            Fingerprint(definingClass = REPLY_READ_EXTENSION, name = name).method.addInstructions(0, body)
        replaceStub("isMessage", "instance-of p0, p0, ${sendClass.type}\nreturn p0")
        replaceStub("repliedTo", "check-cast p0, ${sendClass.type}\ninvoke-virtual {p0}, $repliedTo\nmove-result-object p0\nreturn-object p0")
        replaceStub("subjectOf", "check-cast p0, ${mutationClass.type}\ninvoke-virtual {p0}, $subject\nmove-result-object p0\nreturn-object p0")
        replaceStub("typeOf", "check-cast p0, ${mutationClass.type}\ninvoke-virtual {p0}, $typeName\nmove-result-object p0\nreturn-object p0")

        DmMutationSuccessFingerprint.let { success ->
            if (success.method.parameterTypes[1].toString() != mutationClass.type) {
                throw PatchException("Unexpected DM success mutation parameter")
            }
            val sessionField = success.originalClassDef.fields.singleOrNull {
                it.type == USER_SESSION_CLASS && !AccessFlags.STATIC.isSet(it.accessFlags)
            } ?: throw PatchException("Could not uniquely resolve the DM success session field")
            success.method.apply {
                val registers = getFreeRegisterProvider(index = 0, numberOfFreeRegistersNeeded = 2)
                val sessionRegister = registers.getFreeRegister()
                val mutationRegister = registers.getFreeRegister()
                addInstructions(
                    0,
                    """
                    move-object/from16 v$mutationRegister, p2
                    move-object/from16 v$sessionRegister, p0
                    iget-object v$sessionRegister, v$sessionRegister, $sessionField
                    invoke-static {v$sessionRegister, v$mutationRegister}, $REPLY_READ_EXTENSION->onMutationSent(${USER_SESSION_CLASS}Ljava/lang/Object;)V
                    """.trimIndent(),
                )
            }
        }

        DmTypingStartFingerprint.method.addInstructions(
            0,
            "invoke-static/range {p1 .. p1}, $REPLY_READ_EXTENSION->onTyping(Ljava/lang/String;)V",
        )
        enableSettings("markAsReadAfterReply")
    }
}
