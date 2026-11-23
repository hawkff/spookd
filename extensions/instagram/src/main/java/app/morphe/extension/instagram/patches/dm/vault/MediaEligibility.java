/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.dm.vault;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Decides whether a received DM item may be saved and with which view mode. Unless view-once media is
 * saved too, anything unknown, unreadable or unresolved skips it.
 */
public final class MediaEligibility {
    public enum Kind { VISUAL, VOICE }

    /** One item field whose type is a media wrapper. */
    public static final class WrapperField {
        final Kind kind;
        final boolean unreadable;
        final boolean present;
        final String viewMode;

        private WrapperField(Kind kind, boolean unreadable, boolean present, String viewMode) {
            this.kind = kind;
            this.unreadable = unreadable;
            this.present = present;
            this.viewMode = viewMode;
        }

        public static WrapperField absent(Kind kind) {
            return new WrapperField(kind, false, false, null);
        }

        public static WrapperField present(Kind kind, String viewMode) {
            return new WrapperField(kind, false, true, viewMode);
        }

        public static WrapperField unreadable(Kind kind) {
            return new WrapperField(kind, true, false, null);
        }
    }

    // Wrappers do not override equals, so this keeps one entry per wrapper object while it is alive.
    private static final Map<Object, String> ORIGINAL_MODES = new WeakHashMap<>();

    private MediaEligibility() {
    }

    /** Records a wrapper's view mode before "Make ephemeral media permanent" rewrites it; the first value stays. */
    public static void recordOriginalMode(Object wrapper, String viewMode) {
        synchronized (ORIGINAL_MODES) {
            ORIGINAL_MODES.putIfAbsent(wrapper, viewMode);
        }
    }

    /** The view mode recorded for this wrapper object before the rewrite, or null. */
    public static String originalMode(Object wrapper) {
        synchronized (ORIGINAL_MODES) {
            return ORIGINAL_MODES.get(wrapper);
        }
    }

    /**
     * With {@code viewOnce} every received photo, video and voice item is saved whatever its wrappers say;
     * without it the fail-closed rule below decides.
     */
    public static boolean shouldCapture(String itemType, List<WrapperField> wrappers, boolean rewriteActive,
                                        boolean viewOnce) {
        if (!viewOnce) return shouldCapture(itemType, wrappers, rewriteActive);
        return "media".equals(itemType) || "visual_media".equals(itemType) || "raven_media".equals(itemType)
                || "voice_media".equals(itemType);
    }

    /** The item's view mode: once or replayable when any wrapper has it, else the first known mode, else null. */
    public static String viewMode(List<WrapperField> wrappers) {
        if (wrappers == null) return null;
        String first = null;
        for (WrapperField field : wrappers) {
            if (field == null || field.viewMode == null) continue;
            if ("once".equals(field.viewMode) || "replayable".equals(field.viewMode)) return field.viewMode;
            if (first == null) first = field.viewMode;
        }
        return first;
    }

    /**
     * @param wrappers      every wrapper field of the item; null or empty when they could not be read.
     * @param rewriteActive whether "Make ephemeral media permanent" rewrites the visual mode in this process.
     */
    public static boolean shouldCapture(String itemType, List<WrapperField> wrappers, boolean rewriteActive) {
        if (wrappers == null || wrappers.isEmpty()) return false;
        boolean wrapped = false;
        for (WrapperField field : wrappers) {
            if (field == null || field.unreadable) return false;
            if (!field.present) continue;
            if (!isOrdinary(field.kind, field.viewMode, rewriteActive)) return false;
            wrapped = true;
        }
        if ("media".equals(itemType)) return true;
        if ("visual_media".equals(itemType) || "raven_media".equals(itemType) || "voice_media".equals(itemType)) {
            return wrapped;
        }
        return false;
    }

    private static boolean isOrdinary(Kind kind, String viewMode, boolean rewriteActive) {
        if ("keep_in_chat".equals(viewMode)) return true;
        if (!"permanent".equals(viewMode)) return false;
        // The rewrite turns any visual mode into "permanent", so that value is unknown while it is active.
        return kind == Kind.VOICE || (kind == Kind.VISUAL && !rewriteActive);
    }
}
