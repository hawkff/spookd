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
        for (String[] row : db.getDeletedMessages(200, null)) all.add(row[0]);
        assertEquals(4, all.size());
        assertEquals(all, pageThrough(2, null));
        assertEquals("31000000000000000000000000012", all.get(0));
    }

    @Test
    public void rowsSavedBetweenPagesNeitherShiftNorRepeatAPage() {
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            // Equal times for some rows, so the id breaks the tie.
            String id = db.upsertMessage("3200000000000000000000000000" + i, null, "thread-1", OTHER,
                    "bob", "old " + i, "text", 1000 + (i / 2) * 100);
            db.markDeleted(id);
        }
        for (String[] row : db.getDeletedMessages(200, null)) expected.add(row[0]);

        List<String[]> first = db.getDeletedMessages(2, null);
        List<String> seen = new ArrayList<>();
        for (String[] row : first) seen.add(row[0]);
        // Newer rows saved while the list is open land above the cursor and leave later pages alone.
        for (int i = 0; i < 3; i++) {
            db.markDeleted(db.upsertMessage("3300000000000000000000000000" + i, null, "thread-1", OTHER,
                    "bob", "new " + i, "text", 5000 + i));
        }
        db.syncReactions(messageId, Collections.singletonList(reaction(OTHER, "\u2764")), VIEWER, 6000);
        db.syncReactions(messageId, Collections.emptyList(), VIEWER, 7000);
        seen.addAll(pageThrough(2, first.get(first.size() - 1)));

        assertEquals(expected, seen);
    }

    /** Every row after {@code after}, read {@code size} rows at a time with the keyset cursor. */
    private List<String> pageThrough(int size, String[] after) {
        List<String> out = new ArrayList<>();
        while (true) {
            List<String[]> page = db.getDeletedMessages(size, after);
            assertTrue(page.size() <= size);
            for (String[] row : page) out.add(row[0]);
            if (page.size() < size) return out;
            after = page.get(page.size() - 1);
        }
    }

    private static String[] reaction(String sender, String emoji) {
        return new String[]{sender, emoji};
    }
}
