package app.morphe.extension.instagram.patches.dm.voice;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public final class VoiceDestinationTest {
    private final Object controllerA = new Object();
    private final Object screenA = new Object();
    private final Object controllerB = new Object();
    private final Object screenB = new Object();
    private final Map<Object, String> threads = new HashMap<>();

    {
        threads.put(controllerA, "a");
        threads.put(controllerB, "b");
    }

    @Test
    public void aConversionForChatASendsToAWhileAIsOpen() {
        VoiceDestination a = new VoiceDestination(controllerA, screenA, "a");
        // Chat B's mic is held and its sheet cancelled while A's file converts.
        new VoiceDestination(controllerB, screenB, "b");
        assertSame(controllerA, a.openController(screen -> screen == screenA, threads::get));
    }

    @Test
    public void aConversionForChatAIsDroppedWhileChatBIsOpen() {
        VoiceDestination a = new VoiceDestination(controllerA, screenA, "a");
        new VoiceDestination(controllerB, screenB, "b");
        assertNull(a.openController(screen -> screen == screenB, threads::get));
    }

    @Test
    public void aChatWhoseThreadChangedIsDropped() {
        VoiceDestination a = new VoiceDestination(controllerA, screenA, "a");
        threads.put(controllerA, "c");
        assertNull(a.openController(screen -> true, threads::get));
    }

    @Test
    public void aChatWithoutItsScreenIsDropped() {
        VoiceDestination a = new VoiceDestination(controllerA, null, "a");
        assertNull(a.openController(screen -> true, threads::get));
    }
}
