package app.morphe.extension.instagram.patches.dm.voice;

import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The chat one mic long press picks an audio file for. Every step after that long press carries it,
 * and the file is sent only to it, never to a chat whose mic was held since.
 */
final class VoiceDestination {
    private final WeakReference<Object> controller;
    private final WeakReference<Object> screen;
    final String thread;

    /** {@code controller} is the chat's voice controller, {@code screen} the chat screen that owns it. */
    VoiceDestination(Object controller, Object screen, String thread) {
        this.controller = new WeakReference<>(controller);
        this.screen = new WeakReference<>(screen);
        this.thread = thread;
    }

    /**
     * The voice controller when its chat is still the one open and still has the same thread, else
     * null, so the caller drops the send.
     */
    Object openController(Predicate<Object> screenOpen, Function<Object, String> threadOf) {
        Object voice = controller.get();
        Object shown = screen.get();
        if (voice == null || shown == null || !screenOpen.test(shown)) return null;
        return Objects.equals(thread, threadOf.apply(voice)) ? voice : null;
    }
}
