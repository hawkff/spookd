package app.morphe.extension.instagram.patches.download.gallery;

/** What the storage screen shows and clears of the gallery's database; disk I/O on the caller's background thread. */
public final class GalleryStorage {
    /** The cache folder of files handed to other apps, which a cache clear must leave alone. */
    public static final String SHARE_DIR = GalleryActions.SHARE_DIR;

    private GalleryStorage() {
    }

    public static long galleryBytes() {
        long total = 0;
        for (GalleryTab tab : GalleryTab.values()) {
            for (GalleryStore.Entry entry : GalleryStore.get().list(tab)) total += entry.sizeBytes;
        }
        return total;
    }

    public static void clearGallery() {
        GalleryStore store = GalleryStore.get();
        for (GalleryTab tab : GalleryTab.values()) {
            for (GalleryStore.Entry entry : store.list(tab)) store.delete(entry.id);
        }
    }

    public static int historyEntries() {
        return DownloadHistory.get().list().size();
    }

    /** Saved files stay; only the rows go. */
    public static void clearHistory() {
        DownloadHistory.get().clear();
    }
}
