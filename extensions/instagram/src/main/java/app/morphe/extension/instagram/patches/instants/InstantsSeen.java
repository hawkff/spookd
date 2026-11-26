/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.instants;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.function.Function;

import app.morphe.extension.instagram.entity.MediaData;
import app.morphe.extension.instagram.entity.UserData;
import app.morphe.extension.instagram.patches.story.StoryUserList;
import app.morphe.extension.instagram.settings.SafeStartup;
import app.morphe.extension.instagram.settings.Settings;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;

/**
 * Which Instants Instagram reports as seen. With "Don't mark Instants seen" none are, except those of
 * senders on the Always list; those of senders on the Never list never are.
 */
@SuppressWarnings("unused")
public final class InstantsSeen {
    static final StoryUserList ALWAYS = new StoryUserList(Settings.INSTANTS_ALWAYS_SEEN_USERS,
            "spookd_instants_always_seen_users", "spookd_story_ghost_remove_confirm");
    static final StoryUserList NEVER = new StoryUserList(Settings.INSTANTS_NEVER_SEEN_USERS,
            "spookd_instants_never_seen_users", "spookd_story_ghost_remove_confirm");

    private static final int MAX_OWNERS = 500;
    // Media pk to its sender's {user id, username}, oldest first; seen reports carry only pks.
    private static final LinkedHashMap<String, String[]> OWNERS = new LinkedHashMap<String, String[]>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String[]> eldest) {
            return size() > MAX_OWNERS;
        }
    };

    private InstantsSeen() {
    }

    /** From the per-instant item's constructor, on the thread that builds it: notes who sent it. */
    public static void noteInstant(Object media) {
        try {
            if (media == null || SafeStartup.featuresOff()) return;
            MediaData data = new MediaData(media);
            String pk = pkOf(data.getMediaPkId());
            if (pk == null) return;
            synchronized (OWNERS) {
                if (OWNERS.containsKey(pk)) return;
            }
            UserData owner = data.getUserData();
            String userId = owner == null ? null : owner.getUserId();
            if (userId == null || userId.isEmpty()) return;
            String username = owner.getUsername();
            synchronized (OWNERS) {
                OWNERS.put(pk, new String[]{userId, username == null || username.isEmpty() ? userId : username});
            }
        } catch (Throwable t) {
            Logger.printException(() -> "Failed to note an Instant's sender", t);
        }
    }

    /**
     * Start of Instagram's seen report for Instants, on its coroutine thread: the media ids to report,
     * or null to report none, which Instagram handles as a failed report.
     */
    public static List<?> keptSeenIds(List<?> ids) {
        try {
            if (SafeStartup.featuresOff()) return ids;
            boolean dontMark = Pref.instantsDontMarkSeen();
            Map<String, String> never = NEVER.all();
            if (!dontMark && never.isEmpty()) return ids;
            return kept(ids, dontMark, InstantsSeen::ownerOf, ALWAYS.all(), never);
        } catch (Throwable t) {
            Logger.printException(() -> "Failed to filter the Instants seen report", t);
            return ids;
        }
    }

    /** {@code ids} unchanged when all are reported, the reported ones otherwise, or null when none are. */
    static List<?> kept(List<?> ids, boolean dontMark, Function<String, String> ownerOf,
                        Map<String, String> always, Map<String, String> never) {
        if (ids.isEmpty()) return ids;
        List<Object> kept = new ArrayList<>(ids.size());
        for (Object id : ids) {
            if (marksSeen(dontMark, ownerOf.apply(pkOf(String.valueOf(id))), always, never)) kept.add(id);
        }
        if (kept.size() == ids.size()) return ids;
        return kept.isEmpty() ? null : kept;
    }

    /** Whether an Instant of {@code ownerId} is reported as seen; an unknown (null) sender follows the switch. */
    static boolean marksSeen(boolean dontMark, String ownerId, Map<String, String> always, Map<String, String> never) {
        if (ownerId != null && never.containsKey(ownerId)) return false;
        if (ownerId != null && always.containsKey(ownerId)) return true;
        return !dontMark;
    }

    /** A media id without its owner suffix, or null when empty. */
    static String pkOf(String id) {
        if (id == null || id.isEmpty()) return null;
        int split = id.indexOf('_');
        return split == 0 ? null : split > 0 ? id.substring(0, split) : id;
    }

    private static String ownerOf(String pk) {
        if (pk == null) return null;
        synchronized (OWNERS) {
            String[] owner = OWNERS.get(pk);
            return owner == null ? null : owner[0];
        }
    }

    /** Senders of the Instants built this session, newest first: usernames by user id. */
    static Map<String, String> recentSenders() {
        Map<String, String> senders = new LinkedHashMap<>();
        synchronized (OWNERS) {
            ListIterator<String[]> owners = new ArrayList<>(OWNERS.values()).listIterator(OWNERS.size());
            while (owners.hasPrevious()) {
                String[] owner = owners.previous();
                senders.putIfAbsent(owner[0], owner[1]);
            }
        }
        return senders;
    }
}
