package app.spookd.patches.instagram.misc.profile

import app.spookd.patches.instagram.entity.decoder.MEDIA_CLASS_NAME
import app.spookd.patches.instagram.entity.mediadata.mediaDataEntity
import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.Constants.PATCHES_DESCRIPTOR
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.string
import app.morphe.patcher.util.addInstructionsAtControlFlowLabel
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patcher.util.p0Register
import app.morphe.util.insertLiteralOverride
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

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

// Once the whole main grid loaded, a server switch read here rewrites the profile's post count to the grid size.
internal object ProfileGridCountCheckFingerprint : Fingerprint(
    definingClass = "Lcom/instagram/profile/fragment/ProfileMediaTabFragment;",
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

            ProfileGridCountCheckFingerprint.method.insertLiteralOverride(
                PROFILE_GRID_COUNT_REPAIR_FLAG,
                "$PROFILE_EXTRAS->offWhileGridReelsHidden(Z)Z",
            )

            enableSettings("hideProfileGridReels")
        }
    }
