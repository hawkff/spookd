package app.crimera.patches.instagram.misc.analyzer

import app.crimera.patches.instagram.entity.userdata.FollowerCountUserTreeDictFingerprint
import app.crimera.patches.instagram.entity.userdata.FollowingCountUserTreeDictFingerprint
import app.crimera.patches.instagram.entity.userdata.ProfilePicIdUserTreeDictFingerprint
import app.crimera.patches.instagram.entity.userdata.userDataEntity
import app.crimera.patches.instagram.misc.settings.settingsPatch
import app.crimera.patches.instagram.misc.userProfile.friendshipStatusIndicatorPatch
import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.crimera.patches.instagram.utils.Constants.USER_SESSION_CLASS
import app.crimera.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.patch.classDefByOrNull
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.util.MethodUtil

private val MOVE_OBJECT_OPCODES = setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16)

@Suppress("unused")
val profileAnalyzerPatch =
    patch(
        name = "Profile analyzer",
        description = "Adds a Profile analyzer row to the Misc settings that scans your own followers and following lists.",
    ) {
        dependsOn(settingsPatch, userDataEntity, friendshipStatusIndicatorPatch)

        compatibleWith(COMPATIBILITY_INSTAGRAM)

        execute {
            // The own counts and each row's picture id; the shared user entity wires them only when found.
            FollowerCountUserTreeDictFingerprint.match()
            FollowingCountUserTreeDictFingerprint.match()
            ProfilePicIdUserTreeDictFingerprint.match()

            val builder = FollowListRequestFingerprint.originalMethod
            val runner = InlineSchedulerFingerprint.originalMethod
            if (!AccessFlags.STATIC.isSet(builder.accessFlags) || !AccessFlags.STATIC.isSet(runner.accessFlags)) {
                throw PatchException("Expected a static follow list request builder and inline runner")
            }

            // The task the builder returns: its operation, the operation's outcome calls and the success value.
            val run = followListTaskRunFingerprint(builder.returnType).match()
            val operation = run.instructionMatches[0].fieldReference
            val failed = run.instructionMatches[2].methodReference
            val result = run.instructionMatches[3].methodReference
            if (failed.definingClass != operation.type || result.definingClass != operation.type) {
                throw PatchException("Unexpected follow list task outcome calls")
            }
            val successType = run.instructionMatches.last().instruction.getReference<TypeReference>()!!.type
            val successValue = classDefBy(successType).fields.singleOrNull {
                it.type == "Ljava/lang/Object;" && !AccessFlags.STATIC.isSet(it.accessFlags)
            } ?: throw PatchException("Could not resolve the follow list task success value")

            // The builder's parser wraps the parsed response in the page the task succeeds with.
            val responseParser = FollowListResponseParseFingerprint.originalClassDef.type
            val pageParse = builder.implementation!!.instructions
                .filter { it.opcode == Opcode.SGET_OBJECT }
                .mapNotNull { it.getReference<FieldReference>()?.type }
                .distinct()
                .mapNotNull { classDefByOrNull(it) }
                .flatMap { it.methods }
                .singleOrNull { method ->
                    method.name == "unsafeParseFromJson" && method.implementation?.instructions?.any {
                        it.getReference<FieldReference>()?.definingClass == responseParser
                    } == true
                } ?: throw PatchException("Could not resolve the follow list page parser")
            val pageType = pageParse.implementation!!.instructions
                .filter { it.opcode == Opcode.NEW_INSTANCE }
                .mapNotNull { it.getReference<TypeReference>()?.type }
                .distinct()
                .singleOrNull() ?: throw PatchException("Could not resolve the follow list page type")
            val pageResponse = classDefBy(pageType).fields.singleOrNull {
                !AccessFlags.STATIC.isSet(it.accessFlags)
            } ?: throw PatchException("Could not resolve the follow list page response field")

            // The parsed users and next_max_id go into the response constructor, which stores each in a field.
            val parse = FollowListResponseParseFingerprint.originalMethod.implementation!!.instructions.toList()
            val constructorCallIndex = parse.indices.singleOrNull {
                parse[it].opcode == Opcode.INVOKE_DIRECT_RANGE &&
                    parse[it].getReference<MethodReference>()?.name == "<init>"
            } ?: throw PatchException("Could not resolve the follow list response constructor call")
            val constructor = parse[constructorCallIndex].getReference<MethodReference>()!!
            val usersRegister = parsedValueRegister(parse, "users") {
                parse[it].opcode == Opcode.NEW_INSTANCE &&
                    parse[it].getReference<TypeReference>()?.type == "Ljava/util/ArrayList;"
            }
            val nextMaxIdRegister = parsedValueRegister(parse, "next_max_id") {
                parse[it].opcode == Opcode.MOVE_RESULT_OBJECT &&
                    parse[it - 1].getReference<MethodReference>()?.returnType == "Ljava/lang/String;"
            }
            val usersField = constructorField(
                constructor, argumentOffset(parse, constructorCallIndex, usersRegister), "Ljava/util/List;",
            )
            val nextMaxIdField = constructorField(
                constructor, argumentOffset(parse, constructorCallIndex, nextMaxIdRegister), "Ljava/lang/String;",
            )

            // 12 builder arguments in v0 .. v11, then the 4 parameters.
            replaceBody(
                FetchPageNativeFingerprint,
                16,
                """
                check-cast p0, $USER_SESSION_CLASS
                move-object v0, p0
                move-object v1, p1
                const-string v2, ""
                const-string v3, "follow_list_page"
                move-object v4, p2
                move-object v5, p3
                const/4 v6, 0x0
                const/4 v7, 0x0
                const/4 v8, 0x0
                const/4 v9, 0x0
                const/4 v10, 0x0
                const/4 v11, 0x1
                invoke-static/range {v0 .. v11}, $builder
                move-result-object v0
                invoke-static {v0}, $runner
                iget-object v1, v0, $operation
                invoke-virtual {v1}, $failed
                move-result v2
                if-nez v2, :piko_no_page
                invoke-virtual {v1}, $result
                move-result-object v1
                instance-of v2, v1, $successType
                if-eqz v2, :piko_no_page
                check-cast v1, $successType
                iget-object v1, v1, $successValue
                return-object v1
                :piko_no_page
                const/4 v1, 0x0
                return-object v1
                """,
            )
            listOf(PageUsersNativeFingerprint to usersField, PageNextMaxIdNativeFingerprint to nextMaxIdField)
                .forEach { (stub, field) ->
                    stub.method.addInstructions(
                        0,
                        """
                        check-cast p0, $pageType
                        iget-object p0, p0, $pageResponse
                        check-cast p0, ${constructor.definingClass}
                        iget-object p0, p0, $field
                        return-object p0
                        """.trimIndent(),
                    )
                }

            enableSettings("profileAnalyzer")
        }
    }

/**
 * The register that receives the value parsed for [key]: the first instruction matching [isValue]
 * after the key and before the next key.
 */
private fun parsedValueRegister(instructions: List<Instruction>, key: String, isValue: (Int) -> Boolean): Int {
    val keyIndex = instructions.indexOfFirst { it.getReference<StringReference>()?.string == key }
    if (keyIndex < 0) throw PatchException("Missing follow list response key \"$key\"")
    for (index in keyIndex + 1 until instructions.size) {
        val opcode = instructions[index].opcode
        if (opcode == Opcode.CONST_STRING || opcode == Opcode.CONST_STRING_JUMBO) break
        if (isValue(index)) return (instructions[index] as OneRegisterInstruction).registerA
    }
    throw PatchException("Could not trace the follow list response value \"$key\"")
}

/**
 * The register offset (`this` is 0) of the argument that carries [register] into the range call at
 * [callIndex], following the moves right before the call.
 */
private fun argumentOffset(instructions: List<Instruction>, callIndex: Int, register: Int): Int {
    val call = instructions[callIndex] as RegisterRangeInstruction
    val moves = instructions.subList(0, callIndex)
        .takeLastWhile { it.opcode in MOVE_OBJECT_OPCODES }
        .map { it as TwoRegisterInstruction }
    val copies = moves.filter { it.registerB == register }.map { it.registerA }
    val argument = when {
        copies.size == 1 -> copies.single()
        copies.isEmpty() && moves.none { it.registerA == register } -> register
        else -> throw PatchException("Ambiguous follow list response argument v$register")
    }
    val offset = argument - call.startRegister
    if (offset !in 1 until call.registerCount) {
        throw PatchException("Follow list response value v$register is not a constructor argument")
    }
    return offset
}

/**
 * The field of [constructor]'s class that stores the parameter at register [offset] (`this` is 0),
 * which must have [type].
 */
private fun constructorField(constructor: MethodReference, offset: Int, type: String): FieldReference {
    var position = 1
    var parameterType: String? = null
    for (parameter in constructor.parameterTypes) {
        if (position == offset) parameterType = parameter.toString()
        position += if (parameter.toString() == "J" || parameter.toString() == "D") 2 else 1
    }
    if (parameterType != type) {
        throw PatchException("Follow list response argument at $offset is $parameterType, expected $type")
    }
    val method = classDefBy(constructor.definingClass).methods.singleOrNull {
        MethodUtil.methodSignaturesMatch(it, constructor)
    } ?: throw PatchException("Could not resolve $constructor")
    val implementation = method.implementation ?: throw PatchException("$constructor has no code")
    val thisRegister = implementation.registerCount - position
    val parameterRegister = thisRegister + offset
    val instructions = implementation.instructions.toList()
    val stores = instructions.indices.mapNotNull { index ->
        val store = instructions[index] as? TwoRegisterInstruction
        if (instructions[index].opcode != Opcode.IPUT_OBJECT || store == null || store.registerB != thisRegister) {
            return@mapNotNull null
        }
        val previous = instructions.getOrNull(index - 1)
        val copy = if (previous != null && previous.opcode in MOVE_OBJECT_OPCODES) previous as TwoRegisterInstruction else null
        val stored = store.registerA == parameterRegister ||
            copy != null && copy.registerA == store.registerA && copy.registerB == parameterRegister
        if (stored) instructions[index].getReference<FieldReference>() else null
    }
    return stores.singleOrNull()?.takeIf { it.type == type }
        ?: throw PatchException("Could not resolve the field $constructor stores its argument at $offset in")
}

/** Gives an extension stub a new body with [registers] registers, parameters included. */
private fun replaceBody(fingerprint: Fingerprint, registers: Int, body: String) {
    val original = fingerprint.method
    val replacement = ImmutableMethod(
        original.definingClass, original.name, original.parameters, original.returnType,
        original.accessFlags, original.annotations, original.hiddenApiRestrictions,
        MutableMethodImplementation(registers),
    ).toMutable().apply { addInstructions(0, body.trimIndent()) }
    mutableClassDefBy(original.definingClass).methods.apply {
        remove(original)
        add(replacement)
    }
}
