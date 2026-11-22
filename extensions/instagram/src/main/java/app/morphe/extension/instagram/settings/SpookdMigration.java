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
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
    private static final String JOURNAL = "spookd_migration_journal";
    private static final String JOURNAL_END = "end";

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

    // Old and new database, then the media directory its rows point into, which must move with it.
    private static final String[][] DATABASES = {
            {"databases/piko_dm_vault.db", "databases/spookd_dm_vault.db",
                    "no_backup/piko_vault", "no_backup/spookd_vault"},
            {"databases/piko_instants_vault.db", "databases/spookd_instants_vault.db", null, null},
            {"databases/piko_follow_snapshots.db", "databases/spookd_follow_snapshots.db", null, null},
            {"databases/piko_gallery.db", "databases/spookd_gallery.db",
                    "no_backup/piko_gallery", "no_backup/spookd_gallery"},
    };
    private static final String[] DATABASE_SIDECARS = {"-journal", "-wal", "-shm"};

    private static final String[][] DIRECTORIES = {
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
    private static boolean migrate(Context app) throws IOException {
        boolean finished = true;
        File journal = journal(app);
        List<Move> pending = readJournal(journal);
        // Whatever a resumed group moved back is planned again below.
        if (pending != null) apply(app, pending, true);
        // A group that could neither finish nor go back keeps its entry, and no other group may replace it.
        if (journal.exists()) return false;
        for (List<Move> group : plan(existing(app))) {
            finished &= apply(app, group, isRenameGroup(group));
            if (journal.exists()) return false;
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
     * preference file never loses its .bak and a database never loses its sidecars or media.
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
            boolean newXml = existing.contains(to + ".xml");
            boolean newBak = existing.contains(to + ".xml.bak");
            if (newBak && !newXml) {
                // Only the journal can tell an interrupted move; here Android would drop the old file for the .bak.
                groups.add(Collections.singletonList(new Move(Op.KEEP_BOTH, from, to)));
                continue;
            }
            if (newXml) {
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
            boolean oldMedia = db[2] != null && existing.contains(db[2]);
            if (!existing.contains(db[0])) {
                // The database already moved: its media follows it.
                if (oldMedia) {
                    Op op = existing.contains(db[3]) ? Op.MERGE_DIRECTORY : Op.RENAME;
                    groups.add(Collections.singletonList(new Move(op, db[2], db[3])));
                }
                continue;
            }
            boolean conflict = existing.contains(db[1]) || (oldMedia && existing.contains(db[3]));
            for (String sidecar : DATABASE_SIDECARS) {
                conflict |= existing.contains(db[0] + sidecar) && existing.contains(db[1] + sidecar);
            }
            List<Move> group = new ArrayList<>();
            if (conflict) {
                // The old media stays with the old database, so no recovery reconciles it against other rows.
                group.add(new Move(Op.KEEP_BOTH, db[0], db[1]));
                if (oldMedia) group.add(new Move(Op.KEEP_BOTH, db[2], db[3]));
                groups.add(group);
                continue;
            }
            // Media first and the database last: a failed move renames the whole pair back.
            if (oldMedia) group.add(new Move(Op.RENAME, db[2], db[3]));
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

    private static boolean isRenameGroup(List<Move> group) {
        if (group.size() < 2) return false;
        for (Move move : group) {
            if (move.op != Op.RENAME) return false;
        }
        return true;
    }

    /**
     * Runs a group, also one resumed from the journal: forward when every remaining move can happen,
     * otherwise all its renames go back, including those of an earlier start. A journaled group keeps
     * its entry until it finished or every source is back under its old name.
     */
    private static boolean apply(Context app, List<Move> group, boolean journaled) {
        try {
            if (journaled) writeJournal(app, group);
            boolean forward = true;
            for (Move move : group) {
                if (move.op == Op.RENAME && !isDone(app, move) && !canMove(app, move)) forward = false;
            }
            Move failed = null;
            if (forward) {
                for (Move move : group) {
                    if (move.op == Op.RENAME && isDone(app, move)) continue;
                    if (!apply(app, move)) {
                        failed = move;
                        break;
                    }
                }
                if (failed == null) {
                    if (journaled) clearJournal(app);
                    return true;
                }
            }
            log("Could not " + (failed == null ? "resume " + group : failed) + "; moving the group back", null);
            if (rollBack(app, group) && journaled) clearJournal(app);
        } catch (Throwable t) {
            log("Migration step failed", t);
        }
        return false;
    }

    private static boolean isDone(Context app, Move move) {
        return !file(app, move.from).exists() && file(app, move.to).exists();
    }

    private static boolean canMove(Context app, Move move) {
        return file(app, move.from).exists() && !file(app, move.to).exists();
    }

    /** True when every source of the group is back under its old name. */
    private static boolean rollBack(Context app, List<Move> group) {
        boolean restored = true;
        for (int i = group.size() - 1; i >= 0; i--) {
            Move move = group.get(i);
            if (move.op != Op.RENAME) continue;
            if (isDone(app, move) && !file(app, move.to).renameTo(file(app, move.from))) {
                log("Could not undo " + move, null);
            }
            restored &= file(app, move.from).exists();
        }
        return restored;
    }

    private static File journal(Context app) {
        return new File(app.getFilesDir(), JOURNAL);
    }

    /** Records a group's renames on disk before the first of them. */
    static void writeJournal(Context app, List<Move> group) throws IOException {
        StringBuilder text = new StringBuilder();
        for (Move move : group) {
            text.append(move.from).append('\t').append(move.to).append('\n');
        }
        text.append(JOURNAL_END).append('\n');
        File file = journal(app);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        syncDirectory(file.getParentFile());
    }

    /** The journaled renames, or null when there is none; an entry without its end was cut before any rename. */
    private static List<Move> readJournal(File file) throws IOException {
        if (!file.exists()) return null;
        String[] lines = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).split("\n");
        List<Move> group = new ArrayList<>();
        for (int i = 0; i < lines.length - 1; i++) {
            String[] paths = lines[i].split("\t", -1);
            if (paths.length != 2) break;
            group.add(new Move(Op.RENAME, paths[0], paths[1]));
        }
        if (group.size() == lines.length - 1 && JOURNAL_END.equals(lines[lines.length - 1])) return group;
        if (!file.delete()) throw new IOException("Could not delete " + file);
        return null;
    }

    private static void clearJournal(Context app) throws IOException {
        File file = journal(app);
        if (!file.delete() && file.exists()) throw new IOException("Could not delete " + file);
        syncDirectory(file.getParentFile());
    }

    private static void syncDirectory(File dir) {
        try {
            FileDescriptor fd = Os.open(dir.getPath(), OsConstants.O_RDONLY, 0);
            try {
                Os.fsync(fd);
            } finally {
                Os.close(fd);
            }
        } catch (Throwable t) {
            log("Could not sync " + dir, t);
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
                return deleteRecursively(file(app, move.from));
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
