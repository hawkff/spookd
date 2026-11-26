package app.morphe.extension.instagram.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE)
public final class SpookdMessageDbReactionsTest {
    private static final String VIEWER = "100";
    private static final String OTHER = "200";

    private SpookdMessageDb db;
    private String messageId;

    @Before
    public void setUp() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        Constructor<SpookdMessageDb> constructor = SpookdMessageDb.class.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        db = constructor.newInstance(context);
        messageId = db.upsertMessage("31000000000000000000000000001", "ctx-1", "thread-1", OTHER,
                "bob", "hello", "text", 1000);
        db.putUsername(OTHER, "bob");
    }

    @After
    public void tearDown() {
        db.close();
    }

    @Test
    public void marksOnlyReactionsAnotherUserRemoved() {
        assertEquals(0, db.syncReactions(messageId, Arrays.asList(
                reaction(OTHER, "\u2764"), reaction(VIEWER, "\uD83D\uDE02")), VIEWER, 2000));
        // Seeing the same reactions again changes nothing.
        assertEquals(0, db.syncReactions(messageId, Arrays.asList(
                reaction(OTHER, "\u2764"), reaction(VIEWER, "\uD83D\uDE02")), VIEWER, 2500));
        assertEquals(1, db.syncReactions(messageId, Collections.emptyList(), VIEWER, 3000));

        List<String[]> rows = db.getDeletedMessages();
        assertEquals(1, rows.size());
        String[] row = rows.get(0);
        assertTrue(row[0].startsWith(SpookdMessageDb.REACTION_ID_PREFIX));
        assertEquals(Arrays.asList("thread-1", "bob", "hello", SpookdMessageDb.REMOVED_REACTION, "3000",
                OTHER, "\u2764", "text"), Arrays.asList(row).subList(1, 9));

        // Reacting again keeps the logged removal and stores the new reaction.
        assertEquals(0, db.syncReactions(messageId, Collections.singletonList(reaction(OTHER, "\u2764")),
                VIEWER, 4000));
        assertEquals(1, db.syncReactions(messageId, Collections.emptyList(), VIEWER, 5000));
        rows = db.getDeletedMessages();
        assertEquals(Arrays.asList("5000", "3000"), Arrays.asList(rows.get(0)[5], rows.get(1)[5]));

        db.deleteSaved(Collections.singletonList(rows.get(0)[0]));
        assertEquals("3000", db.getDeletedMessages().get(0)[5]);
        assertEquals(1, db.getDeletedMessages().size());
    }

    @Test
    public void nullReactionsAreNotCompared() {
        db.syncReactions(messageId, Collections.singletonList(reaction(OTHER, "\u2764")), VIEWER, 2000);
        assertEquals(0, db.syncReactions(messageId, null, VIEWER, 3000));
        assertTrue(db.getDeletedMessages().isEmpty());
    }

    @Test
    public void removedReactionsSortAmongDeletedMessagesAndGoWithTheirMessage() {
        String deleted = db.upsertMessage("31000000000000000000000000002", null, "thread-1", OTHER,
                "bob", "gone", "text", 2500);
        db.markDeleted(deleted);
        db.syncReactions(messageId, Collections.singletonList(reaction(OTHER, "\u2764")), VIEWER, 2000);
        db.syncReactions(messageId, new ArrayList<>(), VIEWER, 3000);

        List<String[]> rows = db.getDeletedMessages();
        assertEquals(Arrays.asList(SpookdMessageDb.REMOVED_REACTION, "text"),
                Arrays.asList(rows.get(0)[4], rows.get(1)[4]));

        db.deleteSaved(Collections.singletonList(messageId));
        rows = db.getDeletedMessages();
        assertEquals(1, rows.size());
        assertEquals(deleted, rows.get(0)[0]);
    }

    @Test
    public void mergingCapturesKeepsRemovalsAndOneLiveReaction() {
        String primary = db.upsertMessage("31000000000000000000000000005", null, "thread-1", OTHER,
                "bob", "merged", "text", 1000);
        String secondary = db.upsertMessage(null, "ctx-9", "thread-1", OTHER, "bob", "merged", "text", 1000);
        db.syncReactions(primary, Collections.singletonList(reaction(OTHER, "\u2764")), VIEWER, 2000);
        db.syncReactions(secondary, Arrays.asList(reaction(OTHER, "\u2764"), reaction(OTHER, "x")), VIEWER, 2000);
        db.syncReactions(secondary, Collections.singletonList(reaction(OTHER, "\u2764")), VIEWER, 3000);

        assertEquals(primary, db.resolveAndMergeMessageId("31000000000000000000000000005", "ctx-9"));

        List<String[]> rows = db.getDeletedMessages();
        assertEquals(1, rows.size());
        assertEquals(Arrays.asList("merged", "3000", "x"), Arrays.asList(rows.get(0)[3], rows.get(0)[5], rows.get(0)[7]));
        // The two live hearts became one, so its removal is logged once.
        assertEquals(1, db.syncReactions(primary, Collections.emptyList(), VIEWER, 4000));
        assertEquals(2, db.getDeletedMessages().size());
    }

    @Test
    public void pagesFollowOneStableOrder() {
        for (int i = 0; i < 3; i++) {
            String id = db.upsertMessage("3100000000000000000000000001" + i, null, "thread-1", OTHER,
                    "bob", "gone " + i, "text", i == 2 ? 2000 : 1000);
            db.markDeleted(id);
        }
        db.syncReactions(messageId, Collections.singletonList(reaction(OTHER, "\u2764")), VIEWER, 1500);
        db.syncReactions(messageId, Collections.emptyList(), VIEWER, 1000);

        List<String> all = new ArrayList<>();
        for (String[] row : db.getDeletedMessages(200, 0)) all.add(row[0]);
        assertEquals(4, all.size());
        List<String> paged = new ArrayList<>();
        for (int offset = 0; offset < 6; offset += 2) {
            List<String[]> page = db.getDeletedMessages(2, offset);
            assertTrue(page.size() <= 2);
            for (String[] row : page) paged.add(row[0]);
        }
        assertEquals(all, paged);
        assertEquals("31000000000000000000000000012", all.get(0));
    }

    private static String[] reaction(String sender, String emoji) {
        return new String[]{sender, emoji};
    }
}
