package app.spookd.patches.instagram.misc.profile

import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.checkCast
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.patch
import app.morphe.util.insertLiteralOverride

// Instagram's switch for reordering highlights: the Reorder entry in the highlight menu and dragging in the tray.
private const val HIGHLIGHTS_REORDER_FLAG = 36333623732891201L

// Long-press menu of a profile highlight; both its menu styles read the switch.
internal object HighlightMenuFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/view/View;", "L", "Lcom/instagram/model/reels/ReelItem;", "Ljava/lang/Integer;"),
    strings = listOf("HighlightsTrayDialogHelper"),
    filters = listOf(literal(HIGHLIGHTS_REORDER_FLAG)),
)

// Attaches drag to reorder to your own highlights tray when Reorder is picked.
internal object HighlightReorderAttachFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        literal(HIGHLIGHTS_REORDER_FLAG),
        // The tray's list.
        methodCall(definingClass = "Lkotlin/jvm/functions/Function0;", name = "invoke"),
        checkCast("Landroidx/recyclerview/widget/RecyclerView;"),
    ),
)

@Suppress("unused")
val reorderHighlightsPatch =
    patch(
        name = "Reorder highlights",
        description = "Adds an option to turn on Instagram's own Reorder entry for highlights on your profile.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch)

        execute {
            listOf(HighlightMenuFingerprint, HighlightReorderAttachFingerprint).forEach {
                it.method.insertLiteralOverride(HIGHLIGHTS_REORDER_FLAG, "$PROFILE_EXTRAS->reorderHighlights(Z)Z")
            }

            enableSettings("reorderHighlights")
        }
    }
