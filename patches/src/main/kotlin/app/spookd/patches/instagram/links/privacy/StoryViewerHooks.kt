/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.links.privacy

import app.spookd.patches.instagram.misc.download.storyViewerItemList
import app.spookd.patches.instagram.utils.Constants.USER_SESSION_CLASS
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions

private const val REEL_VIEWER_FRAGMENT = "Linstagram/features/stories/fragment/ReelViewerFragment;"
private const val STORY_ADVANCE_DESCRIPTOR =
    "Lapp/morphe/extension/instagram/patches/story/StoryAdvance;"
private const val STORY_EARLIER_SEEN_DESCRIPTOR =
    "Lapp/morphe/extension/instagram/patches/story/StoryEarlierSeen;"

/**
 * The story viewer's tap-forward handler, which takes the tap coordinates or nulls.
 */
internal object StoryTapForwardFingerprint : Fingerprint(
    definingClass = REEL_VIEWER_FRAGMENT,
    returnType = "V",
    parameters = listOf("Ljava/lang/Float;", "Ljava/lang/Float;"),
    strings = listOf("tap_forward"),
)

private fun storyViewerLifecycleFingerprint(name: String) = Fingerprint(
    definingClass = REEL_VIEWER_FRAGMENT,
    name = name,
    returnType = "V",
    parameters = listOf(),
)

/**
 * Tracks the resumed story viewer so the advance settings can tap forward on it. Optional; the settings show only when it applies.
 */
internal fun addStoryAdvanceHooks() {
    val tapForward = StoryTapForwardFingerprint.matchOrNull()?.method ?: return
    val resume = storyViewerLifecycleFingerprint("onResume").matchOrNull()?.method ?: return
    val pause = storyViewerLifecycleFingerprint("onPause").matchOrNull()?.method ?: return

    Fingerprint(definingClass = STORY_ADVANCE_DESCRIPTOR, name = "advanceNative").method.addInstructions(
        0,
        """
        check-cast p0, $REEL_VIEWER_FRAGMENT
        const/4 p1, 0x0
        invoke-virtual {p0, p1, p1}, $tapForward
        return-void
        """.trimIndent(),
    )
    resume.addInstruction(
        0,
        "invoke-static/range {p0 .. p0}, $STORY_ADVANCE_DESCRIPTOR->onViewerResumed(Ljava/lang/Object;)V",
    )
    pause.addInstruction(
        0,
        "invoke-static/range {p0 .. p0}, $STORY_ADVANCE_DESCRIPTOR->onViewerPaused(Ljava/lang/Object;)V",
    )
    enableSettings("advanceStory")
}

/**
 * Gives the eye button the reel's ordered items, so it can mark the ones before the selected item.
 */
internal fun addStoryEarlierSeenHook(reelParentClass: String) {
    val items = storyViewerItemList()
    if (items.definingClass != reelParentClass) return

    Fingerprint(definingClass = STORY_EARLIER_SEEN_DESCRIPTOR, name = "itemsNative").method.addInstructions(
        0,
        """
        check-cast p0, $reelParentClass
        check-cast p1, $USER_SESSION_CLASS
        invoke-virtual {p0, p1}, $items
        move-result-object p0
        return-object p0
        """.trimIndent(),
    )
    enableSettings("markEarlierStoriesSeen")
}
