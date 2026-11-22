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
