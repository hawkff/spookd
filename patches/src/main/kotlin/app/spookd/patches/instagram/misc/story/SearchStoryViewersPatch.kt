package app.spookd.patches.instagram.misc.story

import app.spookd.patches.instagram.entity.userdata.userDataEntity
import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.Constants.PATCHES_DESCRIPTOR
import app.spookd.patches.instagram.utils.Constants.USER_SESSION_CLASS
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.patch.classDefByOrNull
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val EXTENSION_CLASS = "$PATCHES_DESCRIPTOR/story/viewers/StoryViewerSearch;"
private const val DASHBOARD_FRAGMENT = "Linstagram/features/stories/dashboard/fragment/ReelDashboardFragment;"
private const val REEL_ITEM = "Lcom/instagram/model/reels/ReelItem;"
private const val USER = "Lcom/instagram/user/model/User;"
private const val LIST = "Ljava/util/List;"

// The viewer list screen of your own story.
private object DashboardViewCreatedFingerprint : Fingerprint(
    definingClass = DASHBOARD_FRAGMENT,
    name = "onViewCreated",
    returnType = "V",
    parameters = listOf("Landroid/view/View;", "Landroid/os/Bundle;"),
)

private object DashboardViewDestroyedFingerprint : Fingerprint(
    definingClass = DASHBOARD_FRAGMENT,
    name = "onDestroyView",
    returnType = "V",
    parameters = listOf(),
)

// The story whose viewer list the screen shows.
private object DashboardCurrentStoryFingerprint : Fingerprint(
    definingClass = DASHBOARD_FRAGMENT,
    returnType = REEL_ITEM,
    parameters = listOf(),
)

// Rebuilds the rows of one story's viewer list; it runs again after each page of viewers arrives.
private object ViewerListRebuildFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    strings = listOf("reel_viewers_list_megaphone_item_id", "jcs_view_requests"),
)

@Suppress("unused")
val searchStoryViewersPatch =
    patch(
        name = "Search story viewers",
        description = "Adds an option to search the viewer list of your own story, filter it to followers, people who don't follow you back or starred people, and load all of its viewers.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch, userDataEntity)

        execute {
            val rebuild = ViewerListRebuildFingerprint.originalMethod
            val adapter = rebuild.definingClass
            val instructions = rebuild.implementation!!.instructions.toList()
            fun fieldRead(index: Int) = instructions.getOrNull(index)
                ?.takeIf { it.opcode == Opcode.IGET_OBJECT }?.getReference<FieldReference>()
            fun call(index: Int) = instructions.getOrNull(index)?.getReference<MethodReference>()

            val adapterFields = ViewerListRebuildFingerprint.originalClassDef.fields
                .filter { !AccessFlags.STATIC.isSet(it.accessFlags) }
            fun adapterField(type: String) = adapterFields.singleOrNull { it.type == type }
                ?: throw PatchException("Expected one $type field in the viewer list adapter")
            val story = adapterField(REEL_ITEM)
            val session = adapterField(USER_SESSION_CLASS)

            // Each rebuild clears a list of the adapter and refills it from a list of the story:
            // first with the Facebook viewers, then with the Instagram viewers.
            val copies = instructions.indices.filter { i ->
                fieldRead(i)?.let { it.definingClass == adapter && it.type == LIST } == true &&
                    call(i + 1)?.toString() == "Ljava/util/List;->clear()V" &&
                    call(i + 2)?.returnType == REEL_ITEM &&
                    instructions.getOrNull(i + 3)?.opcode == Opcode.MOVE_RESULT_OBJECT &&
                    fieldRead(i + 4)?.let { it.definingClass == REEL_ITEM && it.type == LIST } == true
            }
            if (copies.size != 2) throw PatchException("Expected two viewer list copies, found ${copies.size}")
            val viewers = fieldRead(copies[1])!!
            val facebookViewers = fieldRead(copies[0])!!
            // The viewers Instagram marks as spam: the story's list is read, then an adapter list is refilled with it.
            val spamCopy = instructions.indices.singleOrNull { i ->
                fieldRead(i)?.let { it.definingClass == REEL_ITEM && it.type == LIST } == true &&
                    fieldRead(i + 1)?.let { it.definingClass == adapter && it.type == LIST } == true &&
                    call(i + 2)?.toString() == "Ljava/util/List;->clear()V" &&
                    call(i + 3)?.toString() == "Ljava/util/List;->addAll(Ljava/util/Collection;)Z"
            } ?: throw PatchException("Could not find the spam viewer list copy")
            val spamViewers = fieldRead(spamCopy + 1)!!
            // The copy loop leaves out your own entry by comparing each entry's user.
            val userIndex = (copies[1] + 5 until instructions.size).firstOrNull { fieldRead(it)?.type == USER }
                ?: throw PatchException("Could not find the viewer entry's user")
            val entryUser = fieldRead(userIndex)!!
            val entryCast = instructions[userIndex - 1]
            if (entryCast.opcode != Opcode.CHECK_CAST ||
                entryCast.getReference<TypeReference>()?.type != entryUser.definingClass
            ) {
                throw PatchException("Unexpected viewer entry read")
            }

            // The paging state: the adapter field the rebuild keeps asking whether more viewers exist.
            val pagingCalls = instructions.indices.mapNotNull { i ->
                val field = fieldRead(i)
                val asked = call(i + 1)
                if (field == null || asked == null || field.definingClass != adapter ||
                    instructions[i + 1].opcode != Opcode.INVOKE_INTERFACE || asked.definingClass != field.type ||
                    asked.returnType != "Z" || asked.parameterTypes.isNotEmpty()
                ) {
                    null
                } else {
                    field to asked
                }
            }
            val paging = pagingCalls.map { it.first }.distinct().singleOrNull { field ->
                classDefByOrNull(field.type)?.methods?.any {
                    it.name == "isLoading" && it.parameterTypes.isEmpty() && it.returnType == "Z"
                } == true
            } ?: throw PatchException("Could not find the viewer list paging state")
            val askedCounts = pagingCalls.filter { it.first == paging }.groupingBy { it.second }.eachCount()
            val mostAsked = askedCounts.values.maxOrNull()
            val hasMore = askedCounts.entries.singleOrNull { it.value == mostAsked }?.key
                ?: throw PatchException("Could not tell which paging call asks for more viewers")
            // The interface's only call without a result loads the next page.
            val loadMore = classDefBy(paging.type).methods.singleOrNull {
                it.parameterTypes.isEmpty() && it.returnType == "V"
            } ?: throw PatchException("Could not find the viewer list next page call")

            // The paging class the screen creates for each story's list and stores in the adapter.
            val pagingStore = Fingerprint(
                filters = listOf(fieldAccess(reference = paging, opcode = Opcode.IPUT_OBJECT)),
            ).match().originalMethod
            val pagingClass = pagingStore.implementation!!.instructions
                .filter { it.opcode == Opcode.NEW_INSTANCE }
                .mapNotNull { it.getReference<TypeReference>()?.type }
                .distinct()
                .singleOrNull { classDefByOrNull(it)?.interfaces?.contains(paging.type) == true }
                ?.let { classDefBy(it) }
                ?: throw PatchException("Could not find the viewer list paging class")
            fun pagingMethod(name: String, returnType: String) = pagingClass.methods.singleOrNull {
                it.name == name && it.parameterTypes.isEmpty() && it.returnType == returnType
            } ?: throw PatchException("The viewer list paging class has no $name()")
            // The next page cursor, which the answer to "more viewers?" checks first.
            val cursor = pagingMethod(hasMore.name, "Z").implementation!!.instructions.firstNotNullOfOrNull { read ->
                read.takeIf { it.opcode == Opcode.IGET_OBJECT }?.getReference<FieldReference>()
                    ?.takeIf { it.definingClass == pagingClass.type && it.type == "Ljava/lang/String;" }
            } ?: throw PatchException("Could not find the viewer list cursor")
            // Instagram's guarded next-page call: it asks only while the screen is active, no page is out,
            // the last load did not fail and more viewers exist.
            val guardedLoadMore = pagingClass.methods.singleOrNull { method ->
                method.parameterTypes.isEmpty() && method.returnType == "V" && method.name != loadMore.name &&
                    method.implementation?.instructions?.mapNotNull { it.getReference<MethodReference>() }?.let { calls ->
                        calls.any { it.definingClass == pagingClass.type && it.name == hasMore.name } &&
                            calls.any { it.definingClass == pagingClass.type && it.name == loadMore.name }
                    } == true
            } ?: throw PatchException("Could not find the guarded viewer list next page call")
            // The next-page call goes to the story's page, which sends the request on every path
            // (Instagram viewers, Facebook viewers, the newer list); its start is where a request begins.
            val pageRequest = pagingMethod(loadMore.name, "V").implementation!!.instructions
                .firstOrNull { it.opcode == Opcode.INVOKE_INTERFACE }?.getReference<MethodReference>()
                ?: throw PatchException("Could not find the viewer page request call")
            val pageType = pagingStore.implementation!!.instructions
                .filter { it.opcode == Opcode.NEW_INSTANCE }
                .mapNotNull { it.getReference<TypeReference>()?.type }
                .distinct()
                .singleOrNull { classDefByOrNull(it)?.interfaces?.contains(pageRequest.definingClass) == true }
                ?: throw PatchException("Could not find the viewer page class")
            val pageAdapter = classDefBy(pageType).fields.singleOrNull {
                it.type == adapter && !AccessFlags.STATIC.isSet(it.accessFlags)
            } ?: throw PatchException("Could not find the viewer page adapter")
            val pageRequestMethod = mutableClassDefBy(pageType).methods.singleOrNull {
                it.name == pageRequest.name && it.parameterTypes.isEmpty() && it.returnType == "V"
            } ?: throw PatchException("Could not find the viewer page request")

            val currentStory = DashboardCurrentStoryFingerprint.originalMethod
            val viewCreated = DashboardViewCreatedFingerprint.method
            val viewDestroyed = DashboardViewDestroyedFingerprint.method
            val rebuildHook = ViewerListRebuildFingerprint.method

            fun stub(name: String, body: String) =
                Fingerprint(definingClass = EXTENSION_CLASS, name = name).method.addInstructions(0, body.trimIndent())
            stub(
                "currentStory",
                """
                check-cast p0, $DASHBOARD_FRAGMENT
                invoke-virtual {p0}, $currentStory
                move-result-object p0
                return-object p0
                """,
            )
            stub(
                "adapterStory",
                """
                check-cast p0, $adapter
                iget-object p0, p0, $story
                return-object p0
                """,
            )
            stub(
                "adapterSession",
                """
                check-cast p0, $adapter
                iget-object p0, p0, $session
                return-object p0
                """,
            )
            stub(
                "adapterViewers",
                """
                check-cast p0, $adapter
                iget-object p0, p0, $viewers
                return-object p0
                """,
            )
            stub(
                "facebookViewers",
                """
                check-cast p0, $adapter
                iget-object p0, p0, $facebookViewers
                return-object p0
                """,
            )
            stub(
                "spamViewers",
                """
                check-cast p0, $adapter
                iget-object p0, p0, $spamViewers
                return-object p0
                """,
            )
            stub(
                "viewerUser",
                """
                check-cast p0, ${entryUser.definingClass}
                iget-object p0, p0, $entryUser
                return-object p0
                """,
            )
            stub(
                "hasMoreViewers",
                """
                check-cast p0, $adapter
                iget-object p0, p0, $paging
                invoke-interface {p0}, $hasMore
                move-result p0
                return p0
                """,
            )
            stub(
                "viewersCursor",
                """
                check-cast p0, $adapter
                iget-object p0, p0, $paging
                check-cast p0, ${pagingClass.type}
                iget-object p0, p0, $cursor
                return-object p0
                """,
            )
            stub(
                "pageAdapter",
                """
                check-cast p0, $pageType
                iget-object p0, p0, $pageAdapter
                return-object p0
                """,
            )
            stub(
                "loadMoreViewers",
                """
                check-cast p0, $adapter
                iget-object p0, p0, $paging
                check-cast p0, ${pagingClass.type}
                invoke-virtual {p0}, $guardedLoadMore
                return-void
                """,
            )

            rebuildHook.addInstruction(
                0,
                "invoke-static/range {p0 .. p0}, $EXTENSION_CLASS->onViewersRebuilt(Ljava/lang/Object;)V",
            )
            viewCreated.addInstruction(
                0,
                "invoke-static/range {p0 .. p1}, $EXTENSION_CLASS->onDashboardViewCreated(Ljava/lang/Object;Landroid/view/View;)V",
            )
            pageRequestMethod.addInstruction(
                0,
                "invoke-static/range {p0 .. p0}, $EXTENSION_CLASS->onViewersPageRequested(Ljava/lang/Object;)V",
            )
            viewDestroyed.addInstruction(
                0,
                "invoke-static/range {p0 .. p0}, $EXTENSION_CLASS->onDashboardViewDestroyed(Ljava/lang/Object;)V",
            )

            enableSettings("searchStoryViewers")
        }
    }
