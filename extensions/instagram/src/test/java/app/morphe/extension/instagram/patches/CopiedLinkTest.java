package app.morphe.extension.instagram.patches;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.content.ClipData;
import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE)
public class CopiedLinkTest {

    @Test
    public void readsTextElseUriOfFirstClipItem() {
        assertEquals("https://www.instagram.com/p/AbC/",
                CopiedLink.fromClip(ClipData.newRawUri("", Uri.parse("https://www.instagram.com/p/AbC/"))));
        assertNull(CopiedLink.fromClip(ClipData.newRawUri("", Uri.parse("https://example.com/p/AbC/"))));
        assertNull(CopiedLink.fromClip(ClipData.newRawUri("", Uri.parse("content://media/external/images/1"))));
        assertEquals("https://www.instagram.com/someuser/",
                CopiedLink.fromClip(ClipData.newPlainText("", "@someuser")));
        assertEquals("https://www.instagram.com/someuser/", CopiedLink.fromClip(new ClipData("",
                new String[]{"text/plain"}, new ClipData.Item("@someuser", null, Uri.parse("https://example.com/")))));
        assertNull(CopiedLink.fromClip(null));
    }

    @Test
    public void keepsPathAndQueryOfInstagramLinks() {
        assertEquals("https://www.instagram.com/p/AbC_1-x/",
                CopiedLink.toUrl("https://www.instagram.com/p/AbC_1-x/"));
        assertEquals("https://www.instagram.com/reel/AbC/?igsh=xyz",
                CopiedLink.toUrl("  HTTPS://Instagram.com/reel/AbC/?igsh=xyz\n"));
        assertEquals("https://www.instagram.com/stories/some.user/123/",
                CopiedLink.toUrl("instagram.com/stories/some.user/123/#top"));
        assertEquals("https://www.instagram.com/some_user",
                CopiedLink.toUrl("http://www.instagram.com/some_user?"));
        assertEquals("https://www.instagram.com/p/AbC/", CopiedLink.toUrl("instagr.am/p/AbC/"));
        assertEquals("https://www.instagram.com/", CopiedLink.toUrl("www.instagram.com"));
    }

    @Test
    public void buildsProfileLinksFromUsernames() {
        assertEquals("https://www.instagram.com/some.user_1/", CopiedLink.toUrl("@Some.User_1"));
        assertEquals("https://www.instagram.com/someuser/", CopiedLink.toUrl(" @someuser "));
        assertEquals("https://www.instagram.com/" + repeat('a', 30) + "/", CopiedLink.toUrl("@" + repeat('a', 30)));
    }

    @Test
    public void rejectsEverythingElse() {
        assertNull(CopiedLink.toUrl(null));
        assertNull(CopiedLink.toUrl(""));
        assertNull(CopiedLink.toUrl("   "));
        assertNull(CopiedLink.toUrl("@"));
        assertNull(CopiedLink.toUrl("https://example.com/p/AbC/"));
        assertNull(CopiedLink.toUrl("https://instagram.com.evil.com/p/AbC/"));
        assertNull(CopiedLink.toUrl("https://instagram.com@evil.com/"));
        assertNull(CopiedLink.toUrl("https://evil.com/instagram.com/"));
        assertNull(CopiedLink.toUrl("https://l.instagram.com/?u=https://evil.com"));
        assertNull(CopiedLink.toUrl("javascript:alert(1)"));
        assertNull(CopiedLink.toUrl("look at instagram.com/p/AbC/"));
        assertNull(CopiedLink.toUrl("someuser"));
        assertNull(CopiedLink.toUrl("@@someuser"));
        assertNull(CopiedLink.toUrl("@.user"));
        assertNull(CopiedLink.toUrl("@user."));
        assertNull(CopiedLink.toUrl("@us..er"));
        assertNull(CopiedLink.toUrl("@user-name"));
        assertNull(CopiedLink.toUrl("@" + repeat('a', 31)));
    }

    private static String repeat(char c, int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int i = 0; i < count; i++) builder.append(c);
        return builder.toString();
    }
}
