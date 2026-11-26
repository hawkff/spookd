/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.dm.saveMessages

import app.spookd.patches.instagram.entity.directItem.DirectItemDispatchFingerprint
import app.spookd.patches.instagram.entity.directItem.FieldReactionEmojiExtension
import app.spookd.patches.instagram.entity.directItem.FieldReactionEmojisExtension
import app.spookd.patches.instagram.entity.directItem.FieldReactionLikesExtension
import app.spookd.patches.instagram.entity.directItem.FieldReactionsExtension
import app.spookd.patches.instagram.entity.directItem.ReactionSenderMethodExtension
import app.spookd.patches.instagram.entity.directItem.ReactionWriterFingerprint
import app.spookd.patches.instagram.entity.directItem.directItemEntity
import app.spookd.patches.instagram.entity.directItem.jsonKeyFieldFingerprint
import app.spookd.patches.instagram.entity.directItem.reactionListsFingerprint
import app.spookd.patches.instagram.entity.userdata.userDataEntity
import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.Constants.PATCHES_DESCRIPTOR
import app.spookd.patches.instagram.utils.Constants.USER_SESSION_CLASS
import app.spookd.patches.instagram.utils.enableSettings
import app.crimera.utils.changeFirstString
import app.crimera.utils.changeString
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstructions
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.getFreeRegisterProvider
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val HOOK_CLASS = "$PATCHES_DESCRIPTOR/dm/SavedMessagesHook;"

@Suppress("unused")
val saveDeletedMessagesPatch =
    patch(
        name = "Save deleted messages",
        description = "Captures incoming DMs locally as they arrive from the server and marks them when the sender deletes them.",
        default = true,
    ) {
        dependsOn(settingsPatch, directItemEntity, userDataEntity)
        compatibleWith(COMPATIBILITY_INSTAGRAM)

        execute {
            val cacheInstance = UserCacheInstanceFingerprint.originalMethod
            val idMap = UserCacheIdMapFingerprint.instructionMatches.last().fieldReference
            val cacheLookup = userCacheLookupFingerprint(idMap).originalMethod
            CachedUserLookupExtensionFingerprint.method.apply {
                removeInstructions(0, instructions.size)
                addInstructions(
                    0,
                    """
                    invoke-static/range {p0 .. p0}, $cacheInstance
                    move-result-object p0
                    invoke-virtual/range {p0 .. p1}, $cacheLookup
                    move-result-object p0
                    return-object p0
                    """.trimIndent(),
                )
            }

            // Hook 1: REST/JSON path, inject at the return of the parser.
            DirectItemJsonParserFingerprint.let {
                val returnObject = it.instructionMatches.last()
                val itemRegister = returnObject.getInstruction<OneRegisterInstruction>().registerA

                it.method.addInstructionsAtControlFlowLabel(
                    returnObject.index,
                    """
                    invoke-static {v$itemRegister}, $HOOK_CLASS->onMessageReceived(Ljava/lang/Object;)V
                    """.trimIndent(),
                )
            }

            // Hook 2: MQTT/MSys real-time path, which REST never touches. Hook at entry because
            // normal branches jump directly to return instructions and skip code inserted before them.
            val postprocess = DirectItemPostprocessFingerprint.method
            val deltaType = postprocess.parameterTypes[1].toString()
            val deltaStringFields =
                classDefBy(deltaType)
                    .fields.filter { field ->
                        field.type == "Ljava/lang/String;" &&
                            !AccessFlags.STATIC.isSet(field.accessFlags)
                    }
            if (deltaStringFields.size != 1) {
                throw PatchException(
                    "Expected one delta thread-id field in $deltaType, " +
                        "found ${deltaStringFields.size}",
                )
            }
            DeltaThreadIdFieldExtensionFingerprint.changeString(
                "deltaThreadIdField",
                deltaStringFields.single().name,
            )
            postprocess.addInstructions(
                0,
                """
                invoke-static/range {p0 .. p2}, $HOOK_CLASS->onMessageReceived(Ljava/lang/Object;${USER_SESSION_CLASS}Ljava/lang/Object;)V
                """.trimIndent(),
            )

            // Hook 4: SQLite DAO delete, inject at entry so our DB record is still present when the hook fires.
            DirectItemDbHideFingerprint.method.apply {
                val regs = getFreeRegisterProvider(index = 0, numberOfFreeRegistersNeeded = 2)
                val serverIdRegister = regs.getFreeRegister()
                val clientContextRegister = regs.getFreeRegister()

                addInstructions(
                    0,
                    """
                    move-object/from16 v$serverIdRegister, p2
                    move-object/from16 v$clientContextRegister, p3
                    invoke-static {v$serverIdRegister, v$clientContextRegister}, $HOOK_CLASS->onMessageHiddenFromDb(Ljava/lang/String;Ljava/lang/String;)V
                    """.trimIndent(),
                )
            }

            // REST participants supplement the cache used by real-time message delivery.
            ThreadUsersDispatchFingerprint.matchOrNull()?.let { match ->
                match.method.apply {
                    // The list write is out of line, after other JSON fields. Follow the
                    // array guard to its completion block instead of selecting the next list write.
                    val arrayGuard = getInstruction<BuilderOffsetInstruction>(match.instructionMatches[1].index)
                    val putIndex = indexOfFirstInstructionOrThrow(
                        arrayGuard.target.location.index,
                        fieldAccess(
                            definingClass = parameterTypes.first().toString(),
                            type = "Ljava/util/List;",
                            opcode = Opcode.IPUT_OBJECT,
                        ),
                    )
                    val parsedUsersRegister = getInstruction<TwoRegisterInstruction>(putIndex).registerA
                    val usersRegister = getFreeRegisterProvider(putIndex + 1, 1).getFreeRegister()
                    addInstructions(
                        putIndex + 1,
                        """
                        move-object/from16 v$usersRegister, v$parsedUsersRegister
                        invoke-static {v$usersRegister}, $HOOK_CLASS->noteThreadUsers(Ljava/util/List;)V
                        """.trimIndent(),
                    )
                }
            }

            // Reactions, compared to log removed ones; a miss leaves them unread and changes nothing else.
            val parserClass = DirectItemDispatchFingerprint.originalClassDef.type
            val reactionsField = jsonKeyFieldFingerprint("reactions", parserClass).matchOrNull()
                ?.instructionMatches?.last()?.fieldReference
            val itemIdField = jsonKeyFieldFingerprint("item_id", parserClass).instructionMatches.last().fieldReference
            val reactionLists = reactionsField?.let { reactionListsFingerprint(parserClass, it.type).matchOrNull() }
            val reactionWriter = ReactionWriterFingerprint.matchOrNull()
            if (reactionsField != null && reactionsField.definingClass == itemIdField.definingClass &&
                reactionLists != null && reactionWriter != null
            ) {
                FieldReactionsExtension.changeFirstString(reactionsField.name)
                FieldReactionLikesExtension.changeFirstString(reactionLists.instructionMatches[2].fieldReference.name)
                FieldReactionEmojisExtension.changeFirstString(reactionLists.instructionMatches[3].fieldReference.name)
                ReactionSenderMethodExtension.changeFirstString(reactionWriter.instructionMatches[1].methodReference.name)
                FieldReactionEmojiExtension.changeFirstString(reactionWriter.instructionMatches[4].fieldReference.name)
            }

            enableSettings("saveDeletedMessages")
        }
    }
