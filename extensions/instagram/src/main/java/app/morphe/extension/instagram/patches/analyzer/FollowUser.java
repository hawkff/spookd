package app.morphe.extension.instagram.patches.analyzer;

/** One account from a followers or following page. */
public final class FollowUser {
    public final String id;
    public final String username;
    public final String fullName;
    public final boolean verified;
    public final String picId;

    public FollowUser(String id, String username, String fullName, boolean verified, String picId) {
        this.id = id;
        this.username = username;
        this.fullName = fullName;
        this.verified = verified;
        this.picId = picId;
    }
}
