package app.morphe.extension.instagram.patches.download.gallery;

/** What a download knows about its media when it is saved to the gallery; null fields are unknown. */
public final class GalleryItem {
    public static final String PHOTO = "photo";
    public static final String VIDEO = "video";
    public static final String AUDIO = "audio";

    public final GalleryTab tab;
    /** {@link #PHOTO}, {@link #VIDEO} or {@link #AUDIO}. */
    public final String kind;
    public final String ownerUsername;
    public final String ownerId;
    public final String mediaId;
    public final String link;
    /** The account that saved it. */
    public final String accountId;
    /** Upload time in ms. */
    public final Long takenAt;

    public GalleryItem(GalleryTab tab, String kind, String ownerUsername, String ownerId,
                       String mediaId, String link, String accountId, Long takenAt) {
        this.tab = tab;
        this.kind = kind;
        this.ownerUsername = ownerUsername;
        this.ownerId = ownerId;
        this.mediaId = mediaId;
        this.link = link;
        this.accountId = accountId;
        this.takenAt = takenAt;
    }
}
