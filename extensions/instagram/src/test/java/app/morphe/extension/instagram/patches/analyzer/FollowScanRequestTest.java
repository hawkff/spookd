package app.morphe.extension.instagram.patches.analyzer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class FollowScanRequestTest {

    @Test
    public void aFullNameEqualToTheUsernameIsKept() {
        assertEquals("alice", FollowScanRequest.followUser("1", "alice", "alice", false, null).fullName);
    }

    @Test
    public void onlyAMissingOrEmptyFullNameIsNull() {
        assertNull(FollowScanRequest.followUser("1", "alice", null, false, null).fullName);
        assertNull(FollowScanRequest.followUser("1", "alice", "", false, null).fullName);
        assertEquals("Alice Smith", FollowScanRequest.followUser("1", "alice", "Alice Smith", true, "p").fullName);
    }

    @Test
    public void aUserWithoutAnIdIsSkipped() {
        assertNull(FollowScanRequest.followUser(null, "alice", "Alice", false, null));
        assertNull(FollowScanRequest.followUser("", "alice", "Alice", false, null));
    }
}
