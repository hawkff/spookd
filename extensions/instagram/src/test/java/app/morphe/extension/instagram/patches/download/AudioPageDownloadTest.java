package app.morphe.extension.instagram.patches.download;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
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
    public void cyclesEnd() throws Exception {
        Node first = new Node(null);
        Node second = new Node(first);
        first.value = second;
        assertNull(AudioPageDownload.findOriginalSound(first, Sound.class));
    }
}
