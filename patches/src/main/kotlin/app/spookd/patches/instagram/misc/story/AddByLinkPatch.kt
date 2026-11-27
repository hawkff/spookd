/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.story

import app.spookd.patches.instagram.entity.decoder.USER_MODEL_CLASS_NAME
import app.spookd.patches.instagram.entity.userdata.userDataEntity
import app.spookd.patches.instagram.misc.dm.saveMessages.USER_CACHE_CLASS
import app.spookd.patches.instagram.misc.dm.saveMessages.UserCacheIdMapFingerprint
import app.spookd.patches.instagram.misc.dm.saveMessages.UserCacheInstanceFingerprint
import app.spookd.patches.instagram.misc.settings.SetCurrentUserSessionFingerprint
import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.Constants.PATCHES_DESCRIPTOR
import app.spookd.patches.instagram.utils.Constants.USER_SESSION_CLASS
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.getReference
import app.morphe.util.findInstructionIndicesReversedOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val EXTENSION_CLASS = "$PATCHES_DESCRIPTOR/story/StoryUserList;"

private object CachedUserNamedExtensionFingerprint : Fingerprint(
    definingClass = EXTENSION_CLASS,
    name = "cachedUserNamed",
    returnType = "Ljava/lang/Object;",
    parameters = listOf(USER_SESSION_CLASS, "Ljava/lang/String;"),
)

@Suppress("unused")
val addByLinkPatch =
    patch(
        name = "Add people to lists by link",
        description = "Adds an option to add someone to spookd's people lists by pasting their profile link or " +
            "@username. Uses the profiles Instagram has already loaded, so nothing is requested.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch, userDataEntity)

        execute {
            val cacheInstance = UserCacheInstanceFingerprint.originalMethod
            val idMap = UserCacheIdMapFingerprint.instructionMatches.last().fieldReference
            // The cache keeps users by id and by username; the lookup reads the username map.
            val usernameLookup = classDefBy(USER_CACHE_CLASS).methods.singleOrNull { method ->
                !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == USER_MODEL_CLASS_NAME &&
                    method.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/String;") &&
                    method.implementation?.instructions?.any { instruction ->
                        instruction.opcode == Opcode.IGET_OBJECT &&
                            instruction.getReference<FieldReference>()?.let {
                                it.definingClass == USER_CACHE_CLASS &&
                                    it.type == "Ljava/util/concurrent/ConcurrentMap;" && it != idMap
                            } == true
                    } == true
            } ?: throw PatchException("Could not find the user cache lookup by username")

            CachedUserNamedExtensionFingerprint.method.addInstructions(
                0,
                """
                invoke-static/range {p0 .. p0}, $cacheInstance
                move-result-object p0
                invoke-virtual/range {p0 .. p1}, $usernameLookup
                move-result-object p0
                return-object p0
                """.trimIndent(),
            )

            SetCurrentUserSessionFingerprint.method.apply {
                val returnIndex = findInstructionIndicesReversedOrThrow(Opcode.RETURN_OBJECT).singleOrNull()
                    ?: throw PatchException("Expected a single return in the current session method")
                val register = getInstruction<OneRegisterInstruction>(returnIndex).registerA
                addInstruction(
                    returnIndex,
                    "invoke-static/range { v$register .. v$register }, " +
                        "$EXTENSION_CLASS->onCurrentSession($USER_SESSION_CLASS)V",
                )
            }

            enableSettings("addByLink")
        }
    }
