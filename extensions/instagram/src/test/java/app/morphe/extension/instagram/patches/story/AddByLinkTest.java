package app.morphe.extension.instagram.patches.story;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

// Loading the class loads the settings, as in StoryUserListTest.
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE)
public class AddByLinkTest {

    @Test
    public void profileLinksAndUsernamesGiveTheUsername() {
        assertEquals("some.user", StoryUserList.profileUsername("https://www.instagram.com/some.user/"));
        assertEquals("some.user", StoryUserList.profileUsername("instagram.com/Some.User?igsh=abc"));
        assertEquals("some_user", StoryUserList.profileUsername("  @Some_User  "));
        assertEquals("someuser", StoryUserList.profileUsername("https://instagr.am/someuser"));
    }

    @Test
    public void anythingElseIsRejected() {
        assertNull(StoryUserList.profileUsername(null));
        assertNull(StoryUserList.profileUsername(""));
        assertNull(StoryUserList.profileUsername("https://www.instagram.com/"));
        assertNull(StoryUserList.profileUsername("https://www.instagram.com/p/ABC123/"));
        assertNull(StoryUserList.profileUsername("https://www.instagram.com/stories/someuser/123/"));
        assertNull(StoryUserList.profileUsername("https://instagram.com.evil.com/someuser"));
        assertNull(StoryUserList.profileUsername("https://www.instagram.com/bad..name/"));
        assertNull(StoryUserList.profileUsername("someuser"));
    }
}
