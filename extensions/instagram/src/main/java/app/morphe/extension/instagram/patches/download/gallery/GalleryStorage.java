package app.morphe.extension.instagram.patches.download.gallery;

/** What the storage screen shows and clears of the gallery's database; disk I/O on the caller's background thread. */
public final class GalleryStorage {
    /** The cache folder of files handed to other apps, which a cache clear must leave alone. */
    public static final String SHARE_DIR = GalleryActions.SHARE_DIR;
    /** The database file the gallery rows and the download history share. */
    public static final String DATABASE = GalleryDb.DB_NAME;

    private GalleryStorage() {
    }

    public static long galleryBytes() {
        return GalleryStore.get().totals()[1];
    }

    public static int galleryEntries() {
        return (int) GalleryStore.get().totals()[0];
    }

    /** False when a saved file could not be deleted; throws on database errors. */
    public static boolean clearGallery() {
        return GalleryStore.get().deleteAll();
    }

    public static int historyEntries() {
        return DownloadHistory.get().count();
    }

    /** Saved files stay; only the rows go. */
    public static void clearHistory() {
        DownloadHistory.get().clear();
    }
}
