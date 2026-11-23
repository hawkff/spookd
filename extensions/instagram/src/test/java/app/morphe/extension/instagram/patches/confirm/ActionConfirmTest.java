package app.morphe.extension.instagram.patches.confirm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class ActionConfirmTest {
    @Test
    public void onlyFollowLabelsAreFollows() {
        assertTrue(ActionConfirm.isFollow("FOLLOW"));
        assertTrue(ActionConfirm.isFollow("FOLLOW_BACK"));
        assertFalse(ActionConfirm.isFollow("FOLLOWING"));
        assertFalse(ActionConfirm.isFollow("UNFOLLOW"));
        assertFalse(ActionConfirm.isFollow("REQUESTED"));
        assertFalse(ActionConfirm.isFollow(null));
    }

    @Test
    public void onlyDirectUnfollowsAndWithdrawnRequestsAreAsked() {
        assertEquals(ActionConfirm.UNFOLLOW, ActionConfirm.unfollowAction("FOLLOWSTATUSFOLLOWING", "PrivacyStatusPublic"));
        assertEquals(ActionConfirm.WITHDRAW, ActionConfirm.unfollowAction("FOLLOWSTATUSREQUESTED", "PrivacyStatusPrivate"));
        assertNull(ActionConfirm.unfollowAction("FOLLOWSTATUSFOLLOWING", "PrivacyStatusPrivate"));
        assertNull(ActionConfirm.unfollowAction("FOLLOWSTATUSFOLLOWING", "PrivacyStatusUnknown"));
        assertNull(ActionConfirm.unfollowAction("FOLLOWSTATUSNOTFOLLOWING", "PrivacyStatusPublic"));
        assertNull(ActionConfirm.unfollowAction(null, null));
    }

    @Test
    public void onlyStandaloneCallButtonsAreAsked() {
        assertEquals("spookd_confirm_audio_call_title", ActionConfirm.callTitle("AUDIO_CALL"));
        assertEquals("spookd_confirm_video_call_title", ActionConfirm.callTitle("VIDEO_CALL"));
        assertNull(ActionConfirm.callTitle("COMBINED_CALL_V1"));
        assertNull(ActionConfirm.callTitle("ONGOING_AUDIO_CALL"));
        assertNull(ActionConfirm.callTitle("INFO"));
        assertNull(ActionConfirm.callTitle(null));
    }

    @Test
    public void storyLikeReplayIsDroppedWhenThePresenterMovesToAnotherStory() throws Exception {
        Object presenter = new Object();
        Object storyA = new Object();
        Object storyB = new Object();
        Object button = new Object();
        ActionConfirm.rememberStoryLikePresenter(null, presenter);
        ActionConfirm.onStoryItemBound(presenter, storyA);
        List<Object> asked = Arrays.asList(0, false, ActionConfirm.storyItemOf(presenter));
        List<Boolean> seen = new ArrayList<>();

        ActionConfirm.runConfirmed(button, asked, () -> {
            seen.add(ActionConfirm.replayVerdict(button, Arrays.asList(0, false, ActionConfirm.storyItemOf(presenter))));
            ActionConfirm.onStoryItemBound(presenter, storyB);
            seen.add(ActionConfirm.replayVerdict(button, Arrays.asList(0, false, ActionConfirm.storyItemOf(presenter))));
        });

        assertEquals(Arrays.asList(true, false), seen);
    }

    @Test
    public void otherPresentersAreNotTracked() throws Exception {
        Object presenter = new Object();
        ActionConfirm.onStoryItemBound(presenter, new Object());
        assertNull(ActionConfirm.storyItemOf(presenter));
    }

    @Test
    public void reelLikesWithAStoredReactionAreNotAsked() {
        Object[] plainTap = new Object[10];
        Object[] reaction = new Object[10];
        reaction[8] = "reaction_asset";
        assertFalse(ActionConfirm.isReaction(plainTap));
        assertTrue(ActionConfirm.isReaction(reaction));
    }

    @Test
    public void blankCommentsAreNotAsked() {
        assertTrue(ActionConfirm.isBlank(null));
        assertTrue(ActionConfirm.isBlank(" \n "));
        assertFalse(ActionConfirm.isBlank(" nice "));
    }

    @Test
    public void unchangedStateMatchesByValueAndIdentity() {
        Object media = new Object();
        assertTrue(ActionConfirm.sameState(Arrays.asList(2, "alice", "FOLLOW"), Arrays.asList(2, "alice", "FOLLOW")));
        assertTrue(ActionConfirm.sameState(Arrays.asList("nice", media), Arrays.asList(new String("nice"), media)));
        assertTrue(ActionConfirm.sameState(Arrays.asList(false, null), Arrays.asList(false, null)));
    }

    @Test
    public void changedStateDoesNotMatch() {
        assertFalse(ActionConfirm.sameState(Arrays.asList(2, false), Arrays.asList(3, false)));
        assertFalse(ActionConfirm.sameState(Arrays.asList(2, false), Arrays.asList(2, true)));
        assertFalse(ActionConfirm.sameState(Arrays.asList(1, "alice", "FOLLOW"), Arrays.asList(1, "bob", "FOLLOW")));
        assertFalse(ActionConfirm.sameState(Arrays.asList("nice", new Object()), Arrays.asList("nice", new Object())));
        assertFalse(ActionConfirm.sameState(Arrays.asList("nice"), Arrays.asList("nice!")));
        assertFalse(ActionConfirm.sameState(Arrays.asList("a"), Arrays.asList("a", "b")));
        assertFalse(ActionConfirm.sameState(null, Arrays.asList("a")));
    }

    @Test
    public void replayGoesThroughOnlyForItsTargetWhileUnchanged() {
        Object target = new Object();
        Object other = new Object();
        List<Object> asked = Arrays.asList(1, false);
        List<Boolean> seen = new ArrayList<>();

        assertNull(ActionConfirm.replayVerdict(target, asked));
        ActionConfirm.runConfirmed(target, asked, () -> {
            seen.add(ActionConfirm.replayVerdict(target, Arrays.asList(1, false)));
            seen.add(ActionConfirm.replayVerdict(target, Arrays.asList(2, false)));
            seen.add(ActionConfirm.replayVerdict(other, asked));
            seen.add(ActionConfirm.replayVerdict(null, asked));
        });

        assertEquals(Arrays.asList(true, false, null, null), seen);
        assertNull(ActionConfirm.replayVerdict(target, asked));
    }

    @Test
    public void replayEndsWhenItFails() {
        Object target = new Object();
        List<Object> asked = Arrays.asList("nice");
        ActionConfirm.runConfirmed(target, asked, () -> {
            throw new IllegalStateException("rerun failed");
        });
        assertNull(ActionConfirm.replayVerdict(target, asked));
    }
}
