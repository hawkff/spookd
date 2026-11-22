package app.morphe.extension.instagram.patches.download.gallery;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import app.morphe.extension.instagram.constants.PostType;

public final class GalleryTabTest {
    private static final String USER = "alice";

    @Test
    public void postsAndCarousels() {
        assertEquals(GalleryTab.POSTS, GalleryTab.of(PostType.POST, null, USER));
        assertEquals(GalleryTab.POSTS, GalleryTab.of(PostType.CAROUSEL, null, USER));
    }

    @Test
    public void reels() {
        assertEquals(GalleryTab.REELS, GalleryTab.of(PostType.REEL, null, USER));
    }

    @Test
    public void storiesByTypeOrByHintOverTheFallbackType() {
        assertEquals(GalleryTab.STORIES, GalleryTab.of(PostType.STORY, null, USER));
        assertEquals(GalleryTab.STORIES, GalleryTab.of(PostType.STORY, PostType.STORY, USER));
        assertEquals(GalleryTab.STORIES, GalleryTab.of(PostType.POST, PostType.STORY, USER));
    }

    @Test
    public void hintDoesNotReplaceAKnownType() {
        assertEquals(GalleryTab.REELS, GalleryTab.of(PostType.REEL, PostType.STORY, USER));
    }

    @Test
    public void audioFollowsItsPost() {
        assertEquals(GalleryTab.REELS, GalleryTab.of(PostType.REEL, null, "Audio"));
        assertEquals(GalleryTab.POSTS, GalleryTab.of(PostType.POST, null, "Audio"));
    }

    @Test
    public void dmFolderIsDms() {
        assertEquals(GalleryTab.DMS, GalleryTab.of(null, null, "DM"));
        assertEquals(GalleryTab.DMS, GalleryTab.of(PostType.POST, null, "DM"));
    }

    @Test
    public void profilePicturesCommentMediaAndInstantsArePosts() {
        assertEquals(GalleryTab.POSTS, GalleryTab.of(null, null, USER));
        assertEquals(GalleryTab.POSTS, GalleryTab.of(null, null, "Gif"));
        assertEquals(GalleryTab.POSTS, GalleryTab.of(null, null, ""));
        assertEquals(GalleryTab.POSTS, GalleryTab.of(null, null, null));
    }
}
