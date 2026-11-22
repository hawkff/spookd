package app.crimera.patches.instagram.misc.analyzer

import app.crimera.patches.instagram.entity.userdata.FollowerCountUserTreeDictFingerprint
import app.crimera.patches.instagram.entity.userdata.FollowingCountUserTreeDictFingerprint
import app.crimera.patches.instagram.entity.userdata.ProfilePicIdUserTreeDictFingerprint
import app.crimera.patches.instagram.entity.userdata.userDataEntity
import app.crimera.patches.instagram.misc.settings.SetCurrentUserSessionFingerprint
import app.crimera.patches.instagram.misc.settings.SettingsStatusLoadFingerprint
import app.crimera.patches.instagram.misc.settings.settingsPatch
import app.crimera.patches.instagram.misc.userProfile.friendshipStatusIndicatorPatch
import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.crimera.patches.instagram.utils.Constants.PATCHES_DESCRIPTOR
import app.crimera.patches.instagram.utils.Constants.USER_SESSION_CLASS
import app.crimera.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.util.findInstructionIndicesReversedOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.util.MethodUtil

private const val PROFILE_ANALYZER_CLASS = "$PATCHES_DESCRIPTOR/analyzer/ProfileAnalyzer;"

private val MOVE_OBJECT_OPCODES = setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16)

/** How the app sends one list's GraphQL request, read from its own call site. */
private class ListRequest(
    val builder: Method,
    val builderInstance: FieldReference,
    val operationFactory: MethodReference,
    val converterType: String,
    val converterInit: MethodReference,
    val converterKind: Int,
    val map: MethodReference,
    val mapArguments: List<Int>,
    val taskFactory: MethodReference,
    /** The class the call site is defined in. */
    val callerType: String,
)

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

            // The session manager method that makes a session current; the per-account patch may hook it too.
            val currentSession = SetCurrentUserSessionFingerprint.method
            val currentSessionReturn = currentSession.findInstructionIndicesReversedOrThrow(Opcode.RETURN_OBJECT)
                .singleOrNull() ?: throw PatchException("Expected a single return in the current session method")
            val currentSessionRegister =
                currentSession.getInstruction<OneRegisterInstruction>(currentSessionReturn).registerA

            // The app's own Followers and Following requests, as its list screen sends them.
            val followers = listRequest(FollowersListRequestFingerprint.originalMethod)
            val following = listRequest(FollowingListRequestFingerprint.originalMethod)
            val converter = FollowListConverterFingerprint.originalMethod
            if (followers.converterType != converter.definingClass || following.converterType != converter.definingClass ||
                followers.converterKind == following.converterKind ||
                followers.taskFactory != following.taskFactory || followers.operationFactory != following.operationFactory
            ) {
                throw PatchException("The follow list requests do not share one converter, operation and task")
            }
            // The converter reads the session from the request holder, which the following request is defined in.
            val holderType = following.callerType
            val holder = classDefBy(holderType)
            val holderSession = holder.fields.singleOrNull {
                it.type == USER_SESSION_CLASS && !AccessFlags.STATIC.isSet(it.accessFlags)
            } ?: throw PatchException("Could not resolve the follow list request holder session")
            val converterReadsSession = converter.implementation?.instructions?.any {
                it.opcode == Opcode.IGET_OBJECT && it.getReference<FieldReference>()?.let { field ->
                    field.definingClass == holderType && field.name == holderSession.name
                } == true
            } == true
            if (!converterReadsSession) {
                throw PatchException("The follow list converter does not read the request holder session")
            }
            val holderConstructors = holder.methods.filter { it.name == "<init>" }
            val holderInit = when {
                holderConstructors.any { it.parameterTypes.isEmpty() } -> "$holderType-><init>()V"
                // The app allocates a holder without constructors this way (0C6Z.smali:7935-7937).
                holderConstructors.isEmpty() && holder.superclass == "Ljava/lang/Object;" ->
                    "Ljava/lang/Object;-><init>()V"
                else -> throw PatchException("The follow list request holder has no constructor without arguments")
            }

            val executorType = followers.operationFactory.parameterTypes.first().toString()
            val executor = graphQLExecutorFingerprint(executorType).originalMethod
            val runner = InlineSchedulerFingerprint.originalMethod
            if (!AccessFlags.STATIC.isSet(executor.accessFlags) || !AccessFlags.STATIC.isSet(runner.accessFlags)) {
                throw PatchException("Expected a static GraphQL executor lookup and inline runner")
            }

            // The task: its operation, the operation's outcome calls and the success value.
            val run = followListTaskRunFingerprint(followers.taskFactory.returnType).match()
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

            // The list model: the items, and the next max id getter of the shared response interface,
            // named by the REST response model, which answers it with its parsed next_max_id.
            val model = FollowListModelFingerprint.originalClassDef
            val parse = FollowListResponseParseFingerprint.originalMethod.implementation!!.instructions.toList()
            val constructorCallIndex = parse.indices.singleOrNull {
                parse[it].opcode == Opcode.INVOKE_DIRECT_RANGE &&
                    parse[it].getReference<MethodReference>()?.name == "<init>"
            } ?: throw PatchException("Could not resolve the follow list response constructor call")
            val constructor = parse[constructorCallIndex].getReference<MethodReference>()!!
            val nextMaxIdRegister = parsedValueRegister(parse, "next_max_id") {
                parse[it].opcode == Opcode.MOVE_RESULT_OBJECT &&
                    parse[it - 1].getReference<MethodReference>()?.returnType == "Ljava/lang/String;"
            }
            val nextMaxIdField = constructorField(
                constructor, argumentOffset(parse, constructorCallIndex, nextMaxIdRegister), "Ljava/lang/String;",
            )
            val nextMaxIdGetter = classDefBy(constructor.definingClass).methods.singleOrNull { method ->
                method.parameterTypes.isEmpty() && method.returnType == "Ljava/lang/String;" &&
                    method.implementation?.instructions?.toList()?.let {
                        it.size == 2 && it[0].opcode == Opcode.IGET_OBJECT &&
                            it[0].getReference<FieldReference>() == nextMaxIdField &&
                            it[1].opcode == Opcode.RETURN_OBJECT
                    } == true
            }?.name ?: throw PatchException("Could not resolve the follow list next max id getter")
            listOf(nextMaxIdGetter, "getItems").forEach { name ->
                if (model.methods.none { it.name == name && it.parameterTypes.isEmpty() }) {
                    throw PatchException("The follow list model has no $name()")
                }
            }

            replaceBody(FetchFollowersNativeFingerprint, 16, fetchBody(
                followers, holderType, holderInit, holderSession.toString(), executor.toString(),
                runner.toString(), operation, failed, result, successType, successValue.toString(),
            ))
            replaceBody(FetchFollowingNativeFingerprint, 16, fetchBody(
                following, holderType, holderInit, holderSession.toString(), executor.toString(),
                runner.toString(), operation, failed, result, successType, successValue.toString(),
            ))
            PageUsersNativeFingerprint.method.addInstructions(
                0,
                """
                check-cast p0, ${model.type}
                invoke-virtual {p0}, ${model.type}->getItems()Ljava/util/List;
                move-result-object p0
                return-object p0
                """.trimIndent(),
            )
            PageNextMaxIdNativeFingerprint.method.addInstructions(
                0,
                """
                check-cast p0, ${model.type}
                invoke-virtual {p0}, ${model.type}->$nextMaxIdGetter()Ljava/lang/String;
                move-result-object p0
                return-object p0
                """.trimIndent(),
            )

            currentSession.addInstruction(
                currentSessionReturn,
                "invoke-static/range { v$currentSessionRegister .. v$currentSessionRegister }, " +
                    "$PROFILE_ANALYZER_CLASS->onCurrentSession($USER_SESSION_CLASS)V",
            )
            SettingsStatusLoadFingerprint.method.addInstruction(
                0,
                "invoke-static {}, $PROFILE_ANALYZER_CLASS->onAppStart()V",
            )

            enableSettings("profileAnalyzer")
        }
    }

/** Reads how the app's list screen sends the request [builder] builds. */
private fun listRequest(builder: Method): ListRequest {
    if (AccessFlags.STATIC.isSet(builder.accessFlags)) throw PatchException("Unexpected static $builder")
    val descriptor = "${builder.definingClass}->${builder.name}(${builder.parameterTypes.joinToString("")})" +
        builder.returnType
    val call = followListRequestCallFingerprint(descriptor).match()
    val instructions = call.originalMethod.implementation!!.instructions.toList()
    val matches = call.instructionMatches
    val builderIndex = matches[0].index
    // The builder instance the call site reads right before.
    val builderInstance = instructions.subList(0, builderIndex).lastOrNull {
        it.opcode == Opcode.SGET_OBJECT && it.getReference<FieldReference>()?.type == builder.definingClass
    }?.getReference<FieldReference>() ?: throw PatchException("Could not resolve the instance of $builder")
    val converterInit = matches[4].methodReference
    val converterType = matches[3].instruction.getReference<TypeReference>()!!.type
    if (converterInit.definingClass != converterType) throw PatchException("Unexpected converter for $builder")
    val converterKind = constBefore(
        instructions, matches[4].index, (matches[4].instruction as FiveRegisterInstruction).registerE,
    )
    val map = matches[5].methodReference
    val mapCall = matches[5].instruction as FiveRegisterInstruction
    if (mapCall.registerD != (matches[4].instruction as FiveRegisterInstruction).registerC) {
        throw PatchException("The mapped operation of $builder does not use its converter")
    }
    val mapArguments = listOf(mapCall.registerE, mapCall.registerF, mapCall.registerG).map {
        constBefore(instructions, matches[5].index, it)
    }
    return ListRequest(
        builder, builderInstance, matches[1].methodReference, converterType, converterInit, converterKind,
        map, mapArguments, matches[7].methodReference, call.originalMethod.definingClass,
    )
}

/** The value of the nearest constant written to [register] before [index]; anything else throws. */
private fun constBefore(instructions: List<Instruction>, index: Int, register: Int): Int {
    for (i in index - 1 downTo 0) {
        val instruction = instructions[i]
        if (!instruction.opcode.setsRegister() || (instruction as? OneRegisterInstruction)?.registerA != register) continue
        return (instruction as? NarrowLiteralInstruction)?.narrowLiteral
            ?: throw PatchException("v$register is not a constant before $index")
    }
    throw PatchException("v$register is never set before $index")
}

/**
 * Builds and runs one page request like the app does, then returns the task's success value or null.
 * The registers v0 .. v11 are locals, p0 .. p3 the session, user id, max id and rank token.
 */
private fun fetchBody(
    request: ListRequest,
    holderType: String,
    holderInit: String,
    holderSession: String,
    executor: String,
    runner: String,
    operation: FieldReference,
    failed: MethodReference,
    result: MethodReference,
    successType: String,
    successValue: String,
): String {
    // Following lists take enable groups (true here, as the app's own list) before the cache flag.
    val following = request.builder.parameterTypes.size == 10
    val lastArgument = if (following) 10 else 9
    return """
        check-cast p0, $USER_SESSION_CLASS
        sget-object v0, ${request.builderInstance}
        move-object v1, p0
        const/4 v2, 0x0
        const/4 v3, 0x0
        const-string v4, ""
        const/4 v5, 0x0
        move-object v6, p2
        move-object v7, p1
        move-object v8, p3
        const/4 v9, 0x1
        const/4 v10, 0x1
        invoke-virtual/range {v0 .. v$lastArgument}, ${request.builder}
        move-result-object v1
        invoke-static {p0}, $executor
        move-result-object v0
        invoke-static {v0, v1}, ${request.operationFactory}
        move-result-object v0
        new-instance v1, $holderType
        invoke-direct {v1}, $holderInit
        iput-object p0, v1, $holderSession
        new-instance v2, ${request.converterType}
        const v3, ${request.converterKind}
        invoke-direct {v2, v1, v3}, ${request.converterInit}
        const v3, ${request.mapArguments[0]}
        const v4, ${request.mapArguments[1]}
        const v5, ${request.mapArguments[2]}
        invoke-virtual {v0, v2, v3, v4, v5}, ${request.map}
        move-result-object v0
        invoke-static {v0}, ${request.taskFactory}
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
    """
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
