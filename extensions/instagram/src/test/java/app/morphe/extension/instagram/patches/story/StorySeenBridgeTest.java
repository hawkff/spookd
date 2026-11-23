package app.morphe.extension.instagram.patches.story;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class StorySeenBridgeTest {
    private enum Source { USER_REEL, FAN_CLUB_STORY_REEL }

    private static final class RecordingOperations implements StorySeenBridge.Operations {
        int scheduled;

        @Override
        public Object buildRequest(Object session, Object item, Object reel) {
            return new Object();
        }

        @Override
        public void markLocal(Object session, Object item, Object reel) {
        }

        @Override
        public void scheduleRequest(Object session, Object request) {
            scheduled++;
        }
    }

    @Test
    public void automaticReceiptsGoOutForSourcesTheEyeButtonSkips() {
        RecordingOperations operations = new RecordingOperations();

        assertFalse(StorySeenBridge.sendSupportedSource(false, Source.FAN_CLUB_STORY_REEL, null, null, null,
                StorySeenKey.from("viewer", "fan_club_manual"), operations, null));
        assertEquals(0, operations.scheduled);

        assertTrue(StorySeenBridge.sendSupportedSource(true, Source.FAN_CLUB_STORY_REEL, null, null, null,
                StorySeenKey.from("viewer", "fan_club_automatic"), operations, null));
        assertEquals(1, operations.scheduled);
    }

    @Test
    public void theEyeButtonStillSendsForUserReels() {
        RecordingOperations operations = new RecordingOperations();

        assertTrue(StorySeenBridge.sendSupportedSource(false, Source.USER_REEL, null, null, null,
                StorySeenKey.from("viewer", "user_reel_manual"), operations, null));
        assertEquals(1, operations.scheduled);
    }
}
