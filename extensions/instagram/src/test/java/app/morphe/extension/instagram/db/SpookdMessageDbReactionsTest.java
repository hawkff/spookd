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

    private static String[] reaction(String sender, String emoji) {
        return new String[]{sender, emoji};
    }
}
