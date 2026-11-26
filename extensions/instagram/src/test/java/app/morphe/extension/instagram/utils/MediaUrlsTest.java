package app.morphe.extension.instagram.utils;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class MediaUrlsTest {
    private static final long EXPIRY = 0x65A1B2C3L * 1000L;

    @Test
    public void readsHexExpiry() {
        assertEquals(EXPIRY, MediaUrls.expiresAt("https://x.fbcdn.net/a.jpg?stp=1&oe=65A1B2C3&_nc_sid=2"));
        assertEquals(EXPIRY, MediaUrls.expiresAt("https://x.fbcdn.net/a.jpg?oe=65A1B2C3"));
    }

    @Test
    public void skipsOeInsideAnotherNameOrValue() {
        assertEquals(EXPIRY, MediaUrls.expiresAt("https://x.fbcdn.net/a.jpg?shoe=1&oe=65A1B2C3"));
        assertEquals(EXPIRY, MediaUrls.expiresAt("https://x.fbcdn.net/a.jpg?key=Zoe==&oe=65A1B2C3"));
        assertEquals(EXPIRY, MediaUrls.expiresAt("https://x.fbcdn.net/a.jpg?key=Zoe==?oe=7FFFFFFF&oe=65A1B2C3"));
    }

    @Test
    public void reportsZeroWithoutExpiry() {
        assertEquals(0L, MediaUrls.expiresAt(null));
        assertEquals(0L, MediaUrls.expiresAt("https://www.instagram.com/p/abc/"));
        assertEquals(0L, MediaUrls.expiresAt("https://x.fbcdn.net/a.jpg?shoe=65A1B2C3"));
        assertEquals(0L, MediaUrls.expiresAt("https://x.fbcdn.net/a.jpg?oe=&x=1"));
        assertEquals(0L, MediaUrls.expiresAt("https://x.fbcdn.net/a.jpg?shoe=1#&oe=65A1B2C3"));
        assertEquals(0L, MediaUrls.expiresAt("https://x.fbcdn.net/a.jpg#?oe=65A1B2C3"));
    }

    @Test
    public void avatarPrefersTheOrdinaryPictureAndFallsBackToHd() {
        // A cached user without HD info still has its ordinary picture.
        assertEquals("https://x.fbcdn.net/s.jpg", MediaUrls.avatar("https://x.fbcdn.net/s.jpg", ""));
        assertEquals("https://x.fbcdn.net/s.jpg",
                MediaUrls.avatar("https://x.fbcdn.net/s.jpg", "https://x.fbcdn.net/hd.jpg"));
        assertEquals("https://x.fbcdn.net/hd.jpg", MediaUrls.avatar(null, "https://x.fbcdn.net/hd.jpg"));
        // Neither usable: the refresh falls back to one profile request.
        assertEquals(null, MediaUrls.avatar("", ""));
        assertEquals(null, MediaUrls.avatar(null, null));
    }
}
