package app.morphe.extension.instagram.patches.dm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.Collections;

import org.junit.Test;

public class RevokeCacheTest {

    private static void put(RevokeCache cache, String serverId, String clientContext,
                            String messageId, String content) {
        cache.put(serverId, clientContext, messageId, content, cache.generation());
    }

    @Test
    public void evictsLeastRecentlyUsedAliasAtCapacity() {
        RevokeCache cache = new RevokeCache(2);
        put(cache, "s1", null, "m1", "one");
        put(cache, "s2", null, "m2", "two");
        assertNotNull(cache.get("s1", null));
        put(cache, "s3", null, "m3", "three");

        assertEquals("one", cache.get("s1", null).content);
        assertNull(cache.get("s2", null));
        assertEquals("three", cache.get("s3", null).content);
    }

    @Test
    public void missesAfterEviction() {
        RevokeCache cache = new RevokeCache(2);
        put(cache, "s1", "c1", "m1", "one");
        put(cache, "s2", null, "m2", "two");
        put(cache, "s3", null, "m3", "three");

        assertNull(cache.get("s1", "c1"));
        assertEquals("m2", cache.get("s2", null).messageId);
    }

    @Test
    public void aliasOverwrittenAfterMergePointsToSurvivingId() {
        RevokeCache cache = new RevokeCache(16);
        put(cache, "s1", null, "s1", "server text");
        put(cache, null, "c1", "c1", "client text");
        put(cache, "s1", "c1", "c1", "merged text");

        assertEquals("c1", cache.get("s1", null).messageId);
        assertEquals("merged text", cache.get("s1", null).content);
        assertEquals("c1", cache.get(null, "c1").messageId);
    }

    @Test
    public void invalidationByMessageIdRemovesEveryAlias() {
        RevokeCache cache = new RevokeCache(16);
        put(cache, "s1", "c1", "m1", "one");
        put(cache, "s2", "c2", "m2", "two");
        cache.removeMessages(Collections.singletonList("m1"));

        assertNull(cache.get("s1", null));
        assertNull(cache.get(null, "c1"));
        assertEquals("m2", cache.get("s2", "c2").messageId);
    }

    @Test
    public void repointedAliasesMissAfterSurvivorIsInvalidated() {
        RevokeCache cache = new RevokeCache(16);
        put(cache, "s1", null, "m1", "server text");
        put(cache, null, "c1", "m2", "client text");
        put(cache, "s1", "c1", "m1", "merged text");
        cache.removeMessages(Collections.singletonList("m1"));

        assertNull(cache.get("s1", null));
        assertNull(cache.get(null, "c1"));
    }

    @Test
    public void putReadBeforeInvalidationIsRejected() {
        RevokeCache cache = new RevokeCache(16);
        long staleGeneration = cache.generation();
        cache.removeMessages(Collections.singletonList("m1"));
        cache.put("s1", "c1", "m1", "deleted text", staleGeneration);

        assertNull(cache.get("s1", "c1"));

        cache.put("s1", "c1", "m1", "current text", cache.generation());
        assertEquals("current text", cache.get("s1", "c1").content);
    }

    @Test
    public void serverIdAndClientContextDoNotCollide() {
        RevokeCache cache = new RevokeCache(16);
        put(cache, "123", null, "m1", "one");

        assertNull(cache.get(null, "123"));
        assertEquals("m1", cache.get(" 123 ", null).messageId);
    }
}
