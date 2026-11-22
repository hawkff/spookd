package app.crimera.patches.instagram.misc.analyzer

import app.crimera.patches.instagram.utils.Constants.PATCHES_DESCRIPTOR
import app.crimera.patches.instagram.utils.Constants.USER_SESSION_CLASS
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode

internal const val FOLLOW_SCAN_REQUEST_CLASS = "$PATCHES_DESCRIPTOR/analyzer/FollowScanRequest;"

private const val STRING_CLASS = "Ljava/lang/String;"

/**
 * Builds the request for one page of a follow list: session, path, query, search surface, max id,
 * rank token, order, then one int and four flags.
 */
internal object FollowListRequestFingerprint : Fingerprint(
    parameters = listOf(
        USER_SESSION_CLASS, STRING_CLASS, STRING_CLASS, STRING_CLASS, STRING_CLASS, STRING_CLASS, STRING_CLASS,
        "I", "Z", "Z", "Z", "Z",
    ),
    strings = listOf("filter_dismissed_users", "enable_groups", "search_surface", "rank_token", "max_id"),
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
 * Parses a follow-list response into its model.
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

internal object FetchPageNativeFingerprint : Fingerprint(
    definingClass = FOLLOW_SCAN_REQUEST_CLASS,
    name = "fetchPageNative",
)

internal object PageUsersNativeFingerprint : Fingerprint(
    definingClass = FOLLOW_SCAN_REQUEST_CLASS,
    name = "pageUsersNative",
)

internal object PageNextMaxIdNativeFingerprint : Fingerprint(
    definingClass = FOLLOW_SCAN_REQUEST_CLASS,
    name = "pageNextMaxIdNative",
)
