package app.morphe.extension.instagram.patches.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.widget.LinearLayout;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE)
public final class AudioPageDownloadTest {

    interface Sound {
    }

    static final class SoundImpl implements Sound {
    }

    static final class Node {
        Object value;
        String name = "skipped";

        Node(Object value) {
            this.value = value;
        }
    }

    static final class Pair {
        final Object first;
        final Object second;

        Pair(Object first, Object second) {
            this.first = first;
            this.second = second;
        }
    }

    @Test
    public void findsTheSoundThroughTheModelChain() throws Exception {
        SoundImpl sound = new SoundImpl();
        Object handler = new Node(new Node(new Node(sound)));
        assertSame(sound, AudioPageDownload.findOriginalSound(handler, Sound.class));
    }

    @Test
    public void prefersTheNearestSoundAndStopsAtTheDepthLimit() throws Exception {
        Object tooDeep = new Node(new Node(new Node(new Node(new SoundImpl()))));
        assertNull(AudioPageDownload.findOriginalSound(tooDeep, Sound.class));
        SoundImpl near = new SoundImpl();
        Pair handler = new Pair(new Node(new Node(new SoundImpl())), near);
        assertSame(near, AudioPageDownload.findOriginalSound(handler, Sound.class));
    }

    @Test
    public void aRetainedPageHidesAndShowsItsButtonWithTheSwitch() {
        LinearLayout actionBar = new LinearLayout(RuntimeEnvironment.getApplication());
        assertNull(AudioPageDownload.reconcile(actionBar, true));
        View button = new View(actionBar.getContext());
        button.setTag(AudioPageDownload.BUTTON_TAG);
        actionBar.addView(button);

        assertSame(button, AudioPageDownload.reconcile(actionBar, false));
        assertEquals(View.GONE, button.getVisibility());
        assertSame(button, AudioPageDownload.reconcile(actionBar, true));
        assertEquals(View.VISIBLE, button.getVisibility());
        assertEquals(1, actionBar.getChildCount());
    }

    @Test
    public void savesAsArtistDashTitleWithTheServedAudioExtension() {
        assertEquals("dj.k - Night Drive.m4a",
                AudioPageDownload.audioFileName("dj.k", "Night Drive", "https://cdn.example/a/b.mp4?x=1.mp3"));
        assertEquals("dj.k - AC_DC.mp3", AudioPageDownload.audioFileName("dj.k", "AC/DC", "https://cdn.example/a.MP3?s=1"));
        assertEquals("dj.k.m4a", AudioPageDownload.audioFileName("dj.k", " ", null));
        assertEquals("Night Drive.m4a", AudioPageDownload.audioFileName(null, "Night Drive", ""));
        assertEquals("download.m4a", AudioPageDownload.audioFileName(null, null, null));
    }

    @Test
    public void theGalleryKeepsTheAudioPageLink() {
        String link = AudioPageDownload.audioPageLink("1234567890");
        assertEquals("https://www.instagram.com/reels/audio/1234567890/", link);
        assertTrue(AudioPageDownload.isAudioPageLink(link));
        assertFalse(AudioPageDownload.isAudioPageLink("https://www.instagram.com/p/ABC/"));
        assertNull(AudioPageDownload.audioPageLink(""));
    }

    @Test
    public void cyclesEnd() throws Exception {
        Node first = new Node(null);
        Node second = new Node(first);
        first.value = second;
        assertNull(AudioPageDownload.findOriginalSound(first, Sound.class));
    }
}
