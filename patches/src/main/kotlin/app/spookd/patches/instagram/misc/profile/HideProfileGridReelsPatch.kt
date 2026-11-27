package app.spookd.patches.instagram.misc.profile

import app.spookd.patches.instagram.entity.decoder.MEDIA_CLASS_NAME
import app.spookd.patches.instagram.entity.mediadata.mediaDataEntity
import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.Constants.PATCHES_DESCRIPTOR
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.string
import app.morphe.patcher.util.addInstructionsAtControlFlowLabel
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.p0Register
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import app.morphe.util.insertLiteralOverride
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val PROFILE_EXTRAS = "$PATCHES_DESCRIPTOR/userprofile/ProfileExtras;"

private const val MAP_GET = "Ljava/util/Map;->get(Ljava/lang/Object;)Ljava/lang/Object;"
private const val PROFILE_GRID_COUNT_REPAIR_FLAG = 36327675203179765L
private const val PROFILE_GRID_PIN_SYNC_FLAG = 2324164360445916084L

// Profile grid filter over posts (older grid store): Instagram's own checks, then public visibility only.
internal object ProfileGridMediaFilterFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf(MEDIA_CLASS_NAME),
    filters = listOf(
        literal(-1091052443),
        string("ig4a-instagram-schema"),
        methodCall(opcode = Opcode.INVOKE_SUPER, returnType = "Z"),
        // Visibility field of the post.
        literal(1941332754),
        methodCall(smali = MAP_GET),
    ),
)

// Profile grid filter over grid items (newer grid store): the same checks on the item's post.
internal object ProfileGridItemFilterFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf("L"),
    filters = listOf(
        methodCall(opcode = Opcode.INVOKE_SUPER, returnType = "Z"),
        // The item's post.
        fieldAccess(opcode = Opcode.IGET_OBJECT, type = MEDIA_CLASS_NAME),
        // Its visibility.
        methodCall(definingClass = MEDIA_CLASS_NAME, returnType = "Ljava/lang/String;", parameters = listOf()),
        fieldAccess(opcode = Opcode.SGET_OBJECT, type = "Ljava/util/Map;"),
        methodCall(smali = MAP_GET),
    ),
)

private const val PROFILE_MEDIA_TAB = "Lcom/instagram/profile/fragment/ProfileMediaTabFragment;"

// Runs after each grid data change. Once the whole main grid loaded, a server switch read here
// rewrites the profile's post count to the grid size.
internal object ProfileGridChangedFingerprint : Fingerprint(
    definingClass = PROFILE_MEDIA_TAB,
    returnType = "V",
    filters = listOf(literal(PROFILE_GRID_COUNT_REPAIR_FLAG)),
)

// Own grid: moves posts you just pinned to the top, and fails on a pinned post the grid filter left out.
internal object ProfileGridPinSyncFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf("L"),
    filters = listOf(literal(PROFILE_GRID_PIN_SYNC_FLAG)),
)

// Profile grid request succeeded; Instagram cleared its loading state just before.
internal object ProfileGridRequestFinishedFingerprint : Fingerprint(
    definingClass = "Lcom/instagram/profile/fragment/UserDetailFragment;",
    returnType = "V",
    strings = listOf("FeedRequestCallback.onMediaFeedRequestFinish"),
)

internal object RerunProfileGridChangeFingerprint : Fingerprint(
    definingClass = PROFILE_EXTRAS,
    name = "rerunProfileGridChange",
)

@Suppress("unused")
val hideProfileGridReelsPatch =
    patch(
        name = "Hide reels in profile grids",
        description = "Adds an option to show only photo and carousel posts in profile grids.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch, mediaDataEntity)

        execute {
            listOf(ProfileGridMediaFilterFingerprint, ProfileGridItemFilterFingerprint).forEach { filter ->
                val method = filter.method
                val returns = method.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN }
                if (returns.size != 1 || method.p0Register < 1) throw PatchException("Unexpected profile grid filter")
                val returnIndex = returns.single().index
                val keep = method.getInstruction<OneRegisterInstruction>(returnIndex).registerA
                // p1 is read by the hook, so it is never scratch.
                val media = if (filter == ProfileGridItemFilterFingerprint) {
                    "v" + method.findFreeRegister(returnIndex, listOf(keep, method.p0Register, method.p0Register + 1))
                } else {
                    "p1"
                }
                val readMedia = if (media == "p1") "" else "iget-object $media, p1, ${filter.instructionMatches[1].fieldReference}"
                method.addInstructionsAtControlFlowLabel(
                    returnIndex,
                    """
                    $readMedia
                    invoke-static {v$keep, $media}, $PROFILE_EXTRAS->keepInProfileGrid(ZLjava/lang/Object;)Z
                    move-result v$keep
                    """.trimIndent(),
                )
            }

            ProfileGridPinSyncFingerprint.method.insertLiteralOverride(
                PROFILE_GRID_PIN_SYNC_FLAG,
                "$PROFILE_EXTRAS->offWhileGridReelsHidden(Z)Z",
            )

            ProfileGridChangedFingerprint.method.apply {
                insertLiteralOverride(PROFILE_GRID_COUNT_REPAIR_FLAG, "$PROFILE_EXTRAS->offWhileGridReelsHidden(Z)Z")

                // Instagram's next-page loader, as this method reads it: has more pages, then is loading.
                val loadingIndex = indexOfFirstInstructionOrThrow {
                    opcode == Opcode.INVOKE_INTERFACE && getReference<MethodReference>()?.name == "isLoading"
                }
                val isLoading = getInstruction(loadingIndex).getReference<MethodReference>()!!
                val loader = isLoading.definingClass
                val hasMore = getInstruction(
                    indexOfFirstInstructionReversedOrThrow(loadingIndex - 1) {
                        opcode == Opcode.INVOKE_INTERFACE && getReference<MethodReference>()?.definingClass == loader
                    },
                ).getReference<MethodReference>()!!
                // Failed: what Instagram's load-more row checks right after loading, to show Retry.
                val failed = Fingerprint(
                    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
                    returnType = "Ljava/lang/Integer;",
                    parameters = listOf(loader),
                    filters = listOf(
                        methodCall(smali = isLoading.toString()),
                        methodCall(definingClass = loader, returnType = "Z"),
                    ),
                ).match().instructionMatches.last().methodReference
                val loadMore = classDefBy(loader).methods.single { it.returnType == "V" && it.parameterTypes.isEmpty() }
                val loaderField = classDefBy(PROFILE_MEDIA_TAB).fields.single { it.type == loader }
                if (hasMore.returnType != "Z" || p0Register < 5 || parameterTypes.map { it.toString() } != listOf(PROFILE_MEDIA_TAB)) {
                    throw PatchException("Unexpected profile grid loader")
                }

                // An emptied page leaves nothing to scroll, so Instagram's scroll-driven loader never asks for the next.
                addInstructionsWithLabels(
                    0,
                    """
                    iget-object v0, p0, $PROFILE_MEDIA_TAB->${loaderField.name}:$loader
                    if-eqz v0, :spookd_grid_changed
                    invoke-interface {v0}, $isLoading
                    move-result v1
                    invoke-interface {v0}, $failed
                    move-result v4
                    invoke-interface {v0}, $hasMore
                    move-result v2
                    iget-object v3, p0, $PROFILE_MEDIA_TAB->recyclerView:Landroidx/recyclerview/widget/RecyclerView;
                    invoke-static {p0, v3, v1, v4, v2}, $PROFILE_EXTRAS->profileGridNeedsNextPage(Ljava/lang/Object;Landroid/view/View;ZZZ)Z
                    move-result v1
                    if-eqz v1, :spookd_grid_changed
                    invoke-interface {v0}, $loader->${loadMore.name}()V
                    """.trimIndent(),
                    ExternalLabel("spookd_grid_changed", getInstruction(0)),
                )

                // A page arrives while its request still counts as loading, so the grid is checked again once it succeeds.
                RerunProfileGridChangeFingerprint.method.addInstructions(
                    0,
                    """
                    check-cast p0, $PROFILE_MEDIA_TAB
                    invoke-static {p0}, $PROFILE_MEDIA_TAB->$name($PROFILE_MEDIA_TAB)V
                    return-void
                    """.trimIndent(),
                )
            }

            ProfileGridRequestFinishedFingerprint.method.addInstruction(
                0,
                "invoke-static {}, $PROFILE_EXTRAS->onProfileGridRequestFinished()V",
            )

            enableSettings("hideProfileGridReels")
        }
    }
