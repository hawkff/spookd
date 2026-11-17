package app.morphe.extension.instagram.patches.dm.vault;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.instagram.patches.dm.vault.MediaEligibility.Kind;
import app.morphe.extension.instagram.patches.dm.vault.MediaEligibility.WrapperField;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class MediaEligibilityTest {
    private static final String[] ITEM_TYPES = {
            "media", "visual_media", "raven_media", "voice_media",
            "clip", "media_share", "xma_media_share", "reel_share", "story_share",
            "animated_media", "text", "link", "like", "action_log", "placeholder", "unknown_type", null,
    };
    private static final String[] MODES = {"keep_in_chat", "permanent", "once", "replayable", null, "unknown_mode"};
    // The fields of 452: two visual (visual_media, message_item_dict) and one voice.
    private static final Kind[] FIELD_KINDS = {Kind.VISUAL, Kind.VISUAL, Kind.VOICE};

    /** The ordinary wrappers of the design, listed explicitly. */
    private static boolean ordinary(Kind kind, String mode, boolean rewriteActive) {
        if ("keep_in_chat".equals(mode)) return true;
        if (kind == Kind.VOICE) return "permanent".equals(mode);
        return "permanent".equals(mode) && !rewriteActive;
    }

    private static boolean capturedType(String itemType) {
        return "media".equals(itemType) || "visual_media".equals(itemType)
                || "raven_media".equals(itemType) || "voice_media".equals(itemType);
    }

    /** States of one field: index 0 absent, 1..MODES.length present with that mode, last unreadable. */
    private static WrapperField field(Kind kind, int state) {
        if (state == 0) return WrapperField.absent(kind);
        if (state <= MODES.length) return WrapperField.present(kind, MODES[state - 1]);
        return WrapperField.unreadable(kind);
    }

    @Test
    public void everyCombinationPassesOnlyOrdinaryCases() {
        int states = MODES.length + 2;
        int captured = 0;
        for (String itemType : ITEM_TYPES) {
            for (boolean rewriteActive : new boolean[]{false, true}) {
                for (int a = 0; a < states; a++) {
                    for (int b = 0; b < states; b++) {
                        for (int c = 0; c < states; c++) {
                            int[] picked = {a, b, c};
                            List<WrapperField> fields = new ArrayList<>();
                            boolean failed = false;
                            boolean extraordinary = false;
                            boolean wrapped = false;
                            for (int i = 0; i < picked.length; i++) {
                                int state = picked[i];
                                fields.add(field(FIELD_KINDS[i], state));
                                if (state == states - 1) failed = true;
                                else if (state > 0) {
                                    wrapped = true;
                                    if (!ordinary(FIELD_KINDS[i], MODES[state - 1], rewriteActive)) extraordinary = true;
                                }
                            }
                            boolean expected = capturedType(itemType) && !failed && !extraordinary
                                    && (wrapped || "media".equals(itemType));
                            if (expected) captured++;
                            assertEquals(itemType + " " + rewriteActive + " " + Arrays.toString(picked),
                                    expected, MediaEligibility.shouldCapture(itemType, fields, rewriteActive));
                        }
                    }
                }
            }
        }
        assertTrue(captured > 0);
    }

    @Test
    public void ordinaryModesPerWrapper() {
        assertTrue(capture("voice_media", WrapperField.present(Kind.VOICE, "keep_in_chat"), true));
        assertTrue(capture("voice_media", WrapperField.present(Kind.VOICE, "permanent"), true));
        assertTrue(capture("voice_media", WrapperField.present(Kind.VOICE, "permanent"), false));
        assertTrue(capture("visual_media", WrapperField.present(Kind.VISUAL, "keep_in_chat"), true));
        assertTrue(capture("raven_media", WrapperField.present(Kind.VISUAL, "permanent"), false));
        assertFalse(capture("raven_media", WrapperField.present(Kind.VISUAL, "permanent"), true));
        assertFalse(capture("visual_media", WrapperField.present(Kind.VISUAL, "once"), false));
        assertFalse(capture("voice_media", WrapperField.present(Kind.VOICE, "replayable"), false));
        assertFalse(capture("voice_media", WrapperField.present(Kind.VOICE, null), false));
        assertFalse(capture("voice_media", WrapperField.present(Kind.VOICE, "PERMANENT"), false));
    }

    @Test
    public void mediaWithoutWrapperIsCapturedAndWrapperTypesAreNot() {
        assertTrue(capture("media", WrapperField.absent(Kind.VISUAL), true));
        assertFalse(capture("visual_media", WrapperField.absent(Kind.VISUAL), false));
        assertFalse(capture("raven_media", WrapperField.absent(Kind.VISUAL), false));
        assertFalse(capture("voice_media", WrapperField.absent(Kind.VOICE), false));
    }

    @Test
    public void oneExtraordinaryWrapperSkipsTheWholeItem() {
        List<WrapperField> fields = Arrays.asList(
                WrapperField.present(Kind.VISUAL, "keep_in_chat"),
                WrapperField.present(Kind.VISUAL, "once"),
                WrapperField.absent(Kind.VOICE));
        assertFalse(MediaEligibility.shouldCapture("visual_media", fields, false));
        assertFalse(MediaEligibility.shouldCapture("media", fields, false));
    }

    @Test
    public void unreadableOrUnresolvedInputSkips() {
        assertFalse(MediaEligibility.shouldCapture("media", null, false));
        assertFalse(MediaEligibility.shouldCapture("media", Collections.<WrapperField>emptyList(), false));
        assertFalse(MediaEligibility.shouldCapture("media", Collections.<WrapperField>singletonList(null), false));
        assertFalse(capture("media", WrapperField.unreadable(Kind.VISUAL), false));
        assertFalse(capture("voice_media", WrapperField.unreadable(Kind.VOICE), false));
        assertFalse(MediaEligibility.shouldCapture("voice_media", Arrays.asList(
                WrapperField.present(Kind.VOICE, "permanent"), WrapperField.unreadable(Kind.VISUAL)), false));
    }

    private static boolean capture(String itemType, WrapperField field, boolean rewriteActive) {
        return MediaEligibility.shouldCapture(itemType, Collections.singletonList(field), rewriteActive);
    }
}
