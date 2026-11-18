package app.morphe.extension.instagram.patches.dm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.Collections;

import org.junit.Test;

public class RevokeCacheTest {

    @Test
    public void evictsLeastRecentlyUsedAliasAtCapacity() {
        RevokeCache cache = new RevokeCache(2);
        cache.put("s1", null, "m1", "one");
        cache.put("s2", null, "m2", "two");
        assertNotNull(cache.get("s1", null));
        cache.put("s3", null, "m3", "three");

        assertEquals("one", cache.get("s1", null).content);
        assertNull(cache.get("s2", null));
        assertEquals("three", cache.get("s3", null).content);
    }

    @Test
    public void missesAfterEviction() {
        RevokeCache cache = new RevokeCache(2);
        cache.put("s1", "c1", "m1", "one");
        cache.put("s2", null, "m2", "two");
        cache.put("s3", null, "m3", "three");

        assertNull(cache.get("s1", "c1"));
        assertEquals("m2", cache.get("s2", null).messageId);
    }

    @Test
    public void aliasOverwrittenAfterMergePointsToSurvivingId() {
        RevokeCache cache = new RevokeCache(16);
        cache.put("s1", null, "s1", "server text");
        cache.put(null, "c1", "c1", "client text");
        cache.put("s1", "c1", "c1", "merged text");

        assertEquals("c1", cache.get("s1", null).messageId);
        assertEquals("merged text", cache.get("s1", null).content);
        assertEquals("c1", cache.get(null, "c1").messageId);
    }

    @Test
    public void invalidationByMessageIdRemovesEveryAlias() {
        RevokeCache cache = new RevokeCache(16);
        cache.put("s1", "c1", "m1", "one");
        cache.put("s2", "c2", "m2", "two");
        cache.removeMessages(Collections.singletonList("m1"));

        assertNull(cache.get("s1", null));
        assertNull(cache.get(null, "c1"));
        assertEquals("m2", cache.get("s2", "c2").messageId);
    }

    @Test
    public void repointedAliasesMissAfterSurvivorIsInvalidated() {
        RevokeCache cache = new RevokeCache(16);
        cache.put("s1", null, "m1", "server text");
        cache.put(null, "c1", "m2", "client text");
        cache.put("s1", "c1", "m1", "merged text");
        cache.removeMessages(Collections.singletonList("m1"));

        assertNull(cache.get("s1", null));
        assertNull(cache.get(null, "c1"));
    }

    @Test
    public void serverIdAndClientContextDoNotCollide() {
        RevokeCache cache = new RevokeCache(16);
        cache.put("123", null, "m1", "one");

        assertNull(cache.get(null, "123"));
        assertEquals("m1", cache.get(" 123 ", null).messageId);
    }
}
