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

    static final int MAX_OWNERS = 500;
    // Media pk to its sender, oldest first; seen reports carry only pks. A forgotten pk is unknown, which fails closed.
    private static final LinkedHashMap<String, Owner> OWNERS = new LinkedHashMap<String, Owner>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Owner> eldest) {
            return size() > MAX_OWNERS;
        }
    };

    static final class Owner {
        final String userId;
        final String username;

        Owner(String userId, String username) {
            this.userId = userId;
            this.username = username;
        }
    }

    private InstantsSeen() {
    }

    /**
     * From the per-instant item's constructor, on the thread that builds it, with its Media and its
     * author; items rebuilt from Instagram's cache carry the author only in {@code user}.
     */
    public static void noteInstant(Object media, Object skipped1, Object skipped2, Object skipped3, Object user) {
        try {
            if (media == null || SafeStartup.featuresOff()) return;
            MediaData data = new MediaData(media);
            String pk = pkOf(data.getMediaPkId());
            if (pk == null) return;
            synchronized (OWNERS) {
                if (OWNERS.containsKey(pk)) return;
            }
            Owner owner = sender(userOf(() -> data.getUserData()), userOf(() -> user == null ? null : new UserData(user)));
            if (owner != null) remember(pk, owner);
        } catch (Throwable t) {
            Logger.printException(() -> "Failed to note an Instant's sender", t);
        }
    }

    private interface UserCall { UserData get() throws Exception; }

    /** {user id, username} of a user, or null when it has no id. */
    private static String[] userOf(UserCall call) {
        try {
            UserData user = call.get();
            String userId = user == null ? null : user.getUserId();
            return userId == null || userId.isEmpty() ? null : new String[]{userId, user.getUsername()};
        } catch (Throwable t) {
            return null;
        }
    }

    /** The media's user when it has one, else the item's author; a missing username shows the id. */
    static Owner sender(String[] fromMedia, String[] fromAuthor) {
        String[] user = fromMedia != null ? fromMedia : fromAuthor;
        if (user == null) return null;
        return new Owner(user[0], user[1] == null || user[1].isEmpty() ? user[0] : user[1]);
    }

    static void remember(String pk, Owner owner) {
        synchronized (OWNERS) {
            OWNERS.put(pk, owner);
        }
    }

    static int knownCount() {
        synchronized (OWNERS) {
            return OWNERS.size();
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
            Map<String, String> always = ALWAYS.all();
            Map<String, String> never = NEVER.all();
            if (!dontMark && never.isEmpty()) return ids;
            return kept(ids, dontMark, InstantsSeen::ownerOf, always, never);
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

    /**
     * Whether an Instant of {@code ownerId} is reported as seen. An unknown (null) sender is held back
     * while the switch is on or a Never list exists, since it could be on it.
     */
    static boolean marksSeen(boolean dontMark, String ownerId, Map<String, String> always, Map<String, String> never) {
        // shortcut: a held receipt is never sent again once its sender becomes known; upgrade by keeping held pks and resubmitting them.
        if (ownerId == null) return !dontMark && never.isEmpty();
        if (never.containsKey(ownerId)) return false;
        if (always.containsKey(ownerId)) return true;
        return !dontMark;
    }

    /** A media id without its owner suffix, or null when empty. */
    static String pkOf(String id) {
        if (id == null || id.isEmpty()) return null;
        int split = id.indexOf('_');
        return split == 0 ? null : split > 0 ? id.substring(0, split) : id;
    }

    static String ownerOf(String pk) {
        if (pk == null) return null;
        synchronized (OWNERS) {
            Owner owner = OWNERS.get(pk);
            return owner == null ? null : owner.userId;
        }
    }

    /** Senders of the Instants built this session, newest first: usernames by user id. */
    static Map<String, String> recentSenders() {
        Map<String, String> senders = new LinkedHashMap<>();
        synchronized (OWNERS) {
            ListIterator<Owner> owners = new ArrayList<>(OWNERS.values()).listIterator(OWNERS.size());
            while (owners.hasPrevious()) {
                Owner owner = owners.previous();
                senders.putIfAbsent(owner.userId, owner.username);
            }
        }
        return senders;
    }
}
