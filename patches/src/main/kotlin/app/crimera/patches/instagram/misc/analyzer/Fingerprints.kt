package app.crimera.patches.instagram.misc.analyzer

import app.crimera.patches.instagram.utils.Constants.PATCHES_DESCRIPTOR
import app.crimera.patches.instagram.utils.Constants.USER_SESSION_CLASS
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode

internal const val FOLLOW_SCAN_REQUEST_CLASS = "$PATCHES_DESCRIPTOR/analyzer/FollowScanRequest;"

private const val STRING_CLASS = "Ljava/lang/String;"
private const val INTEGER_CLASS = "Ljava/lang/Integer;"
private const val PANDO_REQUEST_CLASS = "Lcom/facebook/pando/PandoGraphQLRequest;"
private const val GRAPHQL_EXECUTOR_EXTENSIONS_CLASS = "Lcom/instagram/graphql/IgGraphQLQueryExecutorExtensionsKt;"

/**
 * Builds the GraphQL request for one page of a followers list: session, two cache key counts,
 * query, order, max id, user id, rank token, and whether to skip the cached copy.
 */
internal object FollowersListRequestFingerprint : Fingerprint(
    returnType = PANDO_REQUEST_CLASS,
    parameters = listOf(
        USER_SESSION_CLASS, INTEGER_CLASS, INTEGER_CLASS,
        STRING_CLASS, STRING_CLASS, STRING_CLASS, STRING_CLASS, STRING_CLASS, "Z",
    ),
    strings = listOf("FollowersList", "xdt_api__v1__friendships__followers"),
)

/**
 * The same for a following list, with an enable groups flag before the cache flag.
 */
internal object FollowingListRequestFingerprint : Fingerprint(
    returnType = PANDO_REQUEST_CLASS,
    parameters = listOf(
        USER_SESSION_CLASS, INTEGER_CLASS, INTEGER_CLASS,
        STRING_CLASS, STRING_CLASS, STRING_CLASS, STRING_CLASS, STRING_CLASS, "Z", "Z",
    ),
    strings = listOf("FollowingList", "xdt_api__v1__friendships__following"),
)

/**
 * Where the app sends a list request: builds it, turns it into an operation with the session's
 * GraphQL executor, maps the result to the list model, and wraps the operation in a task.
 */
internal fun followListRequestCallFingerprint(builderDescriptor: String) = Fingerprint(
    filters = listOf(
        methodCall(smali = builderDescriptor),
        // Operation.
        methodCall(definingClass = GRAPHQL_EXECUTOR_EXTENSIONS_CLASS, opcode = Opcode.INVOKE_STATIC),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        // Converter to the list model.
        opcode(Opcode.NEW_INSTANCE),
        methodCall(
            name = "<init>",
            parameters = listOf("Ljava/lang/Object;", "I"),
            location = MatchAfterImmediately(),
        ),
        // Mapped operation.
        methodCall(parameters = listOf("L", "I", "I", "Z"), opcode = Opcode.INVOKE_VIRTUAL),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        // Task.
        methodCall(opcode = Opcode.INVOKE_STATIC, parameters = listOf("L"), location = MatchAfterImmediately()),
    ),
)

/**
 * Converts a followers or following GraphQL result into the list model.
 */
internal object FollowListConverterFingerprint : Fingerprint(
    strings = listOf("Expected followers list to be available", "Expected following list to be available"),
)

/**
 * The session's GraphQL executor, looked up by its service id.
 */
internal fun graphQLExecutorFingerprint(executorType: String) = Fingerprint(
    returnType = executorType,
    parameters = listOf("L"),
    filters = listOf(literal(0x2c05a)),
)

/**
 * The list model both list responses share.
 */
internal object FollowListModelFingerprint : Fingerprint(
    name = "<init>",
    strings = listOf(
        "null cannot be cast to non-null type com.instagram.api.schemas.ImmutablePandoNewFriendListResponse",
    ),
)

/**
 * Runs a task on the calling thread.
 */
internal object InlineSchedulerFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(string("Schedulers.scheduleInline() called on main thread: task=")),
)

/**
 * Parses the REST follow-list response; its model answers the same response interface, which
 * names the next max id getter.
 */
internal object FollowListResponseParseFingerprint : Fingerprint(
    name = "unsafeParseFromJson",
    strings = listOf("follow_ranking_token", "show_spam_follow_request_tab", "next_max_id"),
)

/**
 * The request task runs its operation, then asks the operation whether it failed and for its result.
 */
internal fun followListTaskRunFingerprint(taskType: String) = Fingerprint(
    definingClass = taskType,
    name = "run",
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        // Operation.
        fieldAccess(definingClass = "this", opcode = Opcode.IGET_OBJECT),
        methodCall(smali = "Ljava/lang/Runnable;->run()V", location = MatchAfterImmediately()),
        // Failed.
        methodCall(parameters = listOf(), returnType = "Z", opcode = Opcode.INVOKE_VIRTUAL),
        // Result.
        methodCall(parameters = listOf(), returnType = "Ljava/lang/Object;", opcode = Opcode.INVOKE_VIRTUAL),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        opcode(Opcode.CHECK_CAST, location = MatchAfterImmediately()),
        // Success.
        opcode(Opcode.INSTANCE_OF, location = MatchAfterImmediately()),
    ),
)

internal object FetchFollowersNativeFingerprint : Fingerprint(
    definingClass = FOLLOW_SCAN_REQUEST_CLASS,
    name = "fetchFollowersNative",
)

internal object FetchFollowingNativeFingerprint : Fingerprint(
    definingClass = FOLLOW_SCAN_REQUEST_CLASS,
    name = "fetchFollowingNative",
)

internal object PageUsersNativeFingerprint : Fingerprint(
    definingClass = FOLLOW_SCAN_REQUEST_CLASS,
    name = "pageUsersNative",
)

internal object PageNextMaxIdNativeFingerprint : Fingerprint(
    definingClass = FOLLOW_SCAN_REQUEST_CLASS,
    name = "pageNextMaxIdNative",
)
