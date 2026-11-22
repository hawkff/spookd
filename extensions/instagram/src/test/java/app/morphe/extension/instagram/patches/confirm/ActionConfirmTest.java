package app.morphe.extension.instagram.patches.confirm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
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
    public void feedButtonsMapToTheirActionUntilDone() {
        assertEquals(ActionConfirm.LIKE, ActionConfirm.ufiAction("row_feed_button_like", false));
        assertEquals(ActionConfirm.REPOST, ActionConfirm.ufiAction("reposts_ufi_icon", false));
        assertNull(ActionConfirm.ufiAction("row_feed_button_like", true));
        assertNull(ActionConfirm.ufiAction("reposts_ufi_icon", true));
        assertNull(ActionConfirm.ufiAction("row_feed_button_save", false));
        assertNull(ActionConfirm.ufiAction(null, false));
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
    public void bypassLastsForTheOneRerunOfItsOwnTarget() {
        Object target = new Object();
        Object other = new Object();
        List<Boolean> seen = new ArrayList<>();

        assertFalse(ActionConfirm.isBypassed(target));
        ActionConfirm.runConfirmed(target, () -> {
            seen.add(ActionConfirm.isBypassed(target));
            seen.add(ActionConfirm.isBypassed(other));
            seen.add(ActionConfirm.isBypassed(null));
        });

        assertEquals(List.of(true, false, false), seen);
        assertFalse(ActionConfirm.isBypassed(target));
    }

    @Test
    public void bypassEndsWhenTheRerunFails() {
        Object target = new Object();
        ActionConfirm.runConfirmed(target, () -> {
            throw new IllegalStateException("rerun failed");
        });
        assertFalse(ActionConfirm.isBypassed(target));
    }
}
