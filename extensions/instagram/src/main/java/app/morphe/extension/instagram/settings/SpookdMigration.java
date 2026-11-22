/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.settings;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Moves stored data from its v1 names to the spookd names once, from index 0 of
 * {@code InstagramAppShell.onCreate} on the main thread of every app process, before anything
 * reads settings or opens a database. The tables below are a frozen record of the v1 names.
 * Uses only {@code app} and Android APIs: the extension's settings and logger read storage on load.
 */
public final class SpookdMigration {

    private static final String TAG = "spookd";
    private static final String MARKER = "spookd_migration_v1";
    private static final String LOCK = "spookd_migration.lock";

    private static final String SETTINGS_OLD = "piko_settings";
    private static final String SETTINGS_NEW = "spookd_settings";
    private static final String SETTINGS_ACCOUNTS_NEW = "spookd_settings_accounts";

    private static final String[][] PREFS = {
            {"piko_recommended_flags_v3", "spookd_recommended_flags_v3"},
            {"piko_hidden_chats", "spookd_hidden_chats"},
            {"piko_ephemeral_media_viewed", "spookd_ephemeral_media_viewed"},
            {"piko_ephemeral_modes", "spookd_ephemeral_modes"},
            {"piko_profile_analyzer", "spookd_profile_analyzer"},
            {"piko_dm", "spookd_dm"},
    };

    static final String[][] LEGACY_KEYS = {
            {"piko_account_owner", "spookd_account_owner"},
            {"piko_debug", "spookd_debug"},
            {"first_time_piko", "first_time_spookd"},
    };

    private static final String[][] DATABASES = {
            {"databases/piko_dm_vault.db", "databases/spookd_dm_vault.db"},
            {"databases/piko_instants_vault.db", "databases/spookd_instants_vault.db"},
            {"databases/piko_follow_snapshots.db", "databases/spookd_follow_snapshots.db"},
            {"databases/piko_gallery.db", "databases/spookd_gallery.db"},
    };
    private static final String[] DATABASE_SIDECARS = {"-journal", "-wal", "-shm"};

    private static final String[][] DIRECTORIES = {
            {"no_backup/piko_vault", "no_backup/spookd_vault"},
            {"no_backup/piko_gallery", "no_backup/spookd_gallery"},
            {"cache/piko_gallery_thumbs", "cache/spookd_gallery_thumbs"},
    };
    private static final String SHARE_DIRECTORY = "cache/piko_share";
    private static final String[] CATALOG =
            {"files/piko_recommended_flags_v3.json", "files/spookd_recommended_flags_v3.json"};

    private static final String[][] CHANNELS = {
            {"piko_profile_analyzer", "spookd_profile_analyzer"},
            {"piko_deleted_messages", "spookd_deleted_messages"},
    };

    private static final String[] ROOTS = {"shared_prefs", "databases", "no_backup", "cache", "files"};

    enum Op { RENAME, MERGE_PREFS, MERGE_DIRECTORY, DELETE, KEEP_BOTH }

    /** One step of the plan. Paths are {@code <root>/<name>}; preference paths have no extension. */
    static final class Move {
        final Op op;
        final String from;
        final String to;

        Move(Op op, String from, String to) {
            this.op = op;
            this.from = from;
            this.to = to;
        }

        @Override
        public String toString() {
            return op + " " + from + (to == null ? "" : " -> " + to);
        }
    }

    private SpookdMigration() {
    }

    public static void run(Context app) {
        try {
            File dir = app.getNoBackupFilesDir();
            File marker = new File(dir, MARKER);
            if (marker.exists()) return;
            // Closing the file releases the lock.
            try (RandomAccessFile lock = new RandomAccessFile(new File(dir, LOCK), "rw")) {
                lock.getChannel().lock();
                if (marker.exists()) return;
                if (migrate(app) && !marker.createNewFile()) {
                    throw new IOException("Could not write " + marker);
                }
            }
        } catch (Throwable t) {
            log("Migration failed", t);
        }
    }

    /** Settings exports from older builds carry the v1 key names; values cannot contain {@code name="}. */
    public static String renameLegacyKeys(String xml) {
        for (String[] key : LEGACY_KEYS) {
            xml = xml.replace("name=\"" + key[0] + "\"", "name=\"" + key[1] + "\"");
        }
        return xml;
    }

    /** True when every step finished; a conflict that keeps both copies still counts as finished. */
    private static boolean migrate(Context app) {
        boolean finished = true;
        for (List<Move> group : plan(existing(app))) {
            finished &= apply(app, group);
        }
        try {
            finished &= renameKeys(app);
        } catch (Throwable t) {
            log("Key rename failed", t);
            finished = false;
        }
        try {
            moveChannels(app);
        } catch (Throwable t) {
            log("Notification channel move failed", t);
            finished = false;
        }
        return finished;
    }

    /**
     * Groups of moves in the order they run. A group moves completely or is renamed back, so a
     * preference file never loses its .bak and a database never loses its sidecars.
     */
    static List<List<Move>> plan(Set<String> existing) {
        List<List<Move>> groups = new ArrayList<>();

        Set<String> prefs = new TreeSet<>();
        for (String path : existing) {
            String base = path.startsWith("shared_prefs/") ? prefsBase(path.substring("shared_prefs/".length())) : null;
            if (base != null && newPrefsName(base) != null) prefs.add(base);
        }
        for (String base : prefs) {
            String from = "shared_prefs/" + base;
            String to = "shared_prefs/" + newPrefsName(base);
            if (existing.contains(to + ".xml") || existing.contains(to + ".xml.bak")) {
                groups.add(Collections.singletonList(new Move(Op.MERGE_PREFS, from, to)));
                continue;
            }
            List<Move> group = new ArrayList<>();
            // Android restores an existing .bak over the .xml on load, so the .bak is the real copy.
            renameIfExists(group, existing, from + ".xml.bak", to + ".xml.bak");
            renameIfExists(group, existing, from + ".xml", to + ".xml");
            groups.add(group);
        }

        for (String[] db : DATABASES) {
            if (!existing.contains(db[0])) continue;
            boolean conflict = existing.contains(db[1]);
            for (String sidecar : DATABASE_SIDECARS) {
                conflict |= existing.contains(db[0] + sidecar) && existing.contains(db[1] + sidecar);
            }
            if (conflict) {
                groups.add(Collections.singletonList(new Move(Op.KEEP_BOTH, db[0], db[1])));
                continue;
            }
            List<Move> group = new ArrayList<>();
            for (String sidecar : DATABASE_SIDECARS) {
                renameIfExists(group, existing, db[0] + sidecar, db[1] + sidecar);
            }
            group.add(new Move(Op.RENAME, db[0], db[1]));
            groups.add(group);
        }

        for (String[] dir : DIRECTORIES) {
            if (!existing.contains(dir[0])) continue;
            Op op = existing.contains(dir[1]) ? Op.MERGE_DIRECTORY : Op.RENAME;
            groups.add(Collections.singletonList(new Move(op, dir[0], dir[1])));
        }
        if (existing.contains(SHARE_DIRECTORY)) {
            groups.add(Collections.singletonList(new Move(Op.DELETE, SHARE_DIRECTORY, null)));
        }
        if (existing.contains(CATALOG[0])) {
            groups.add(Collections.singletonList(existing.contains(CATALOG[1])
                    ? new Move(Op.DELETE, CATALOG[0], null)
                    : new Move(Op.RENAME, CATALOG[0], CATALOG[1])));
        }
        return groups;
    }

    private static void renameIfExists(List<Move> group, Set<String> existing, String from, String to) {
        if (existing.contains(from)) group.add(new Move(Op.RENAME, from, to));
    }

    /** The preference name of a file in shared_prefs, or null for other files. */
    private static String prefsBase(String fileName) {
        if (fileName.endsWith(".xml.bak")) return fileName.substring(0, fileName.length() - ".xml.bak".length());
        if (fileName.endsWith(".xml")) return fileName.substring(0, fileName.length() - ".xml".length());
        return null;
    }

    private static String newPrefsName(String base) {
        if (base.startsWith(SETTINGS_OLD)) return SETTINGS_NEW + base.substring(SETTINGS_OLD.length());
        for (String[] pair : PREFS) {
            if (pair[0].equals(base)) return pair[1];
        }
        return null;
    }

    private static Set<String> existing(Context app) {
        Set<String> result = new HashSet<>();
        for (String root : ROOTS) {
            String[] names = file(app, root).list();
            if (names == null) continue;
            for (String name : names) {
                if (name.startsWith("piko_") || name.startsWith("spookd_")) result.add(root + "/" + name);
            }
        }
        return result;
    }

    private static File file(Context app, String path) {
        int slash = path.indexOf('/');
        String root = slash < 0 ? path : path.substring(0, slash);
        File dir;
        switch (root) {
            case "shared_prefs":
                dir = new File(app.getDataDir(), "shared_prefs");
                break;
            case "databases":
                dir = app.getDatabasePath("spookd").getParentFile();
                break;
            case "no_backup":
                dir = app.getNoBackupFilesDir();
                break;
            case "cache":
                dir = app.getCacheDir();
                break;
            default:
                dir = app.getFilesDir();
        }
        return slash < 0 ? dir : new File(dir, path.substring(slash + 1));
    }

    private static boolean apply(Context app, List<Move> group) {
        List<Move> done = new ArrayList<>();
        try {
            for (Move move : group) {
                if (!apply(app, move)) throw new IOException("Could not " + move);
                done.add(move);
            }
            return true;
        } catch (Throwable t) {
            log("Migration step failed", t);
            for (int i = done.size() - 1; i >= 0; i--) {
                Move move = done.get(i);
                if (move.op == Op.RENAME && !file(app, move.to).renameTo(file(app, move.from))) {
                    log("Could not undo " + move, null);
                }
            }
            return false;
        }
    }

    private static boolean apply(Context app, Move move) {
        switch (move.op) {
            case RENAME:
                return file(app, move.from).renameTo(file(app, move.to));
            case MERGE_PREFS:
                return mergePrefs(app, name(move.from), name(move.to));
            case MERGE_DIRECTORY:
                return mergeDirectory(file(app, move.from), file(app, move.to));
            case DELETE:
                if (!deleteRecursively(file(app, move.from))) log("Could not delete " + move.from, null);
                return true;
            default:
                log("Kept both " + move.from + " and " + move.to, null);
                return true;
        }
    }

    private static String name(String path) {
        return path.substring(path.indexOf('/') + 1);
    }

    /** Adds the old file's keys that the new file lacks, so the later edits in the new file win. */
    private static boolean mergePrefs(Context app, String oldName, String newName) {
        SharedPreferences source = app.getSharedPreferences(oldName, Context.MODE_PRIVATE);
        SharedPreferences target = app.getSharedPreferences(newName, Context.MODE_PRIVATE);
        Map<String, ?> old = source.getAll();
        Map<String, ?> current = target.getAll();
        String oldOwner = owner(old);
        String newOwner = owner(current);
        if (oldOwner != null && newOwner != null && !oldOwner.equals(newOwner)) {
            log("Kept " + oldName + ": it belongs to another account than " + newName, null);
            return true;
        }
        SharedPreferences.Editor editor = target.edit();
        for (Map.Entry<String, ?> entry : old.entrySet()) {
            if (!current.containsKey(entry.getKey())) put(editor, entry.getKey(), entry.getValue());
        }
        return editor.commit() && app.deleteSharedPreferences(oldName);
    }

    private static String owner(Map<String, ?> values) {
        Object owner = values.get(LEGACY_KEYS[0][1]);
        if (owner == null) owner = values.get(LEGACY_KEYS[0][0]);
        return owner instanceof String ? (String) owner : null;
    }

    /** Moves each child the new directory lacks; the old directory goes only once it is empty. */
    private static boolean mergeDirectory(File from, File to) {
        File[] children = from.listFiles();
        if (children == null) return false;
        boolean moved = true;
        for (File child : children) {
            File target = new File(to, child.getName());
            if (!target.exists() && !child.renameTo(target)) moved = false;
        }
        String[] left = from.list();
        if (left != null && left.length == 0) {
            from.delete();
        } else if (moved) {
            log("Kept " + from + ": its remaining files also exist in " + to, null);
        }
        return moved;
    }

    private static boolean deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        return file.delete() || !file.exists();
    }

    /** Renames the v1 keys in the live settings and every per-account snapshot, one commit per file. */
    private static boolean renameKeys(Context app) {
        String[] names = file(app, "shared_prefs").list();
        if (names == null) return true;
        Set<String> bases = new TreeSet<>();
        for (String name : names) {
            String base = prefsBase(name);
            if (base != null && base.startsWith(SETTINGS_NEW) && !base.equals(SETTINGS_ACCOUNTS_NEW)) bases.add(base);
        }
        boolean renamed = true;
        for (String base : bases) {
            SharedPreferences prefs = app.getSharedPreferences(base, Context.MODE_PRIVATE);
            Map<String, ?> values = prefs.getAll();
            SharedPreferences.Editor editor = null;
            for (String[] key : LEGACY_KEYS) {
                if (!values.containsKey(key[0])) continue;
                if (editor == null) editor = prefs.edit();
                if (!values.containsKey(key[1])) put(editor, key[1], values.get(key[0]));
                editor.remove(key[0]);
            }
            if (editor != null && !editor.commit()) {
                log("Could not rename the keys in " + base, null);
                renamed = false;
            }
        }
        return renamed;
    }

    @SuppressWarnings("unchecked")
    private static void put(SharedPreferences.Editor editor, String key, Object value) {
        if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
        else if (value instanceof String) editor.putString(key, (String) value);
        else if (value instanceof Set) editor.putStringSet(key, (Set<String>) value);
        else if (value instanceof Integer) editor.putInt(key, (Integer) value);
        else if (value instanceof Long) editor.putLong(key, (Long) value);
        else if (value instanceof Float) editor.putFloat(key, (Float) value);
    }

    /** Recreates each old channel under its new id with the user's importance, then deletes the old one. */
    private static void moveChannels(Context app) {
        NotificationManager manager = app.getSystemService(NotificationManager.class);
        if (manager == null) return;
        for (String[] id : CHANNELS) {
            NotificationChannel old = manager.getNotificationChannel(id[0]);
            if (old == null) continue;
            if (manager.getNotificationChannel(id[1]) == null) {
                NotificationChannel channel = new NotificationChannel(id[1], old.getName(), old.getImportance());
                channel.setDescription(old.getDescription());
                manager.createNotificationChannel(channel);
            }
            manager.deleteNotificationChannel(id[0]);
        }
    }

    private static void log(String message, Throwable t) {
        try {
            Log.e(TAG, message, t);
        } catch (Throwable ignored) {
        }
    }
}
