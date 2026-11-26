package app.morphe.extension.instagram.patches.download.gallery;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/** The private gallery's rows, one per downloaded file; separate from the DM vault's database. */
public final class GalleryDb extends SQLiteOpenHelper {

    static final String DB_NAME = "piko_gallery.db";
    private static final int DB_VERSION = 3;

    private static GalleryDb instance;

    public static synchronized GalleryDb getInstance(Context context) {
        if (instance == null) {
            instance = new GalleryDb(context.getApplicationContext());
        }
        return instance;
    }

    GalleryDb(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE gallery_items ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "file TEXT NOT NULL UNIQUE,"
                + "tab TEXT NOT NULL,"
                + "kind TEXT NOT NULL,"
                + "file_name TEXT NOT NULL,"
                + "sub_folder TEXT,"
                + "owner_username TEXT,"
                + "owner_id TEXT,"
                + "media_id TEXT,"
                + "link TEXT,"
                + "account_id TEXT,"
                + "saved_at INTEGER NOT NULL,"
                + "taken_at INTEGER,"
                + "size_bytes INTEGER NOT NULL,"
                + "duration_ms INTEGER"
                + ")");
        db.execSQL("CREATE INDEX idx_gallery_tab_time ON gallery_items(tab, saved_at)");
        db.execSQL("CREATE INDEX idx_gallery_name ON gallery_items(sub_folder, file_name)");
        DownloadHistory.createTable(db);
        createBrowsing(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) DownloadHistory.createTable(db);
        if (oldVersion < 3) createBrowsing(db);
    }

    /** Version 3: stars of downloads ({@code vault} 0) and of stored DM items (1), and indexes for each sort. */
    private static void createBrowsing(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE gallery_favorites (vault INTEGER NOT NULL, item_id INTEGER NOT NULL,"
                + " PRIMARY KEY (vault, item_id))");
        // Whichever path deletes a download, its star goes with it.
        db.execSQL("CREATE TRIGGER gallery_favorites_cleanup AFTER DELETE ON gallery_items"
                + " BEGIN DELETE FROM gallery_favorites WHERE vault = 0 AND item_id = OLD.id; END");
        db.execSQL("CREATE INDEX idx_gallery_time ON gallery_items(saved_at)");
        db.execSQL("CREATE INDEX idx_gallery_size ON gallery_items(size_bytes)");
        db.execSQL("CREATE INDEX idx_gallery_tab_size ON gallery_items(tab, size_bytes)");
    }
}
