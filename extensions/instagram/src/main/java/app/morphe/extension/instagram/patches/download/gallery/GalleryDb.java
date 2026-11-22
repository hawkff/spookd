package app.morphe.extension.instagram.patches.download.gallery;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/** The private gallery's rows, one per downloaded file; separate from the DM vault's database. */
public final class GalleryDb extends SQLiteOpenHelper {

    static final String DB_NAME = "spookd_gallery.db";
    private static final int DB_VERSION = 1;

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
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    }
}
