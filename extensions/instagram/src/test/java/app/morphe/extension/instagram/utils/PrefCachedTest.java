package app.morphe.extension.instagram.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

public class PrefCachedTest {
    private static final String KEY = "hide_save_button_surfaces";
    private static final String LEGACY_KEY = "hide_save_buttons";

    @Test
    public void keepsTheSetUntilAWatchedKeyChanges() {
        AtomicInteger loads = new AtomicInteger();
        AtomicReference<Set<String>> stored = new AtomicReference<>(Collections.emptySet());
        Pref.Cached<Set<String>> cached = new Pref.Cached<>(() -> {
            loads.incrementAndGet();
            return new HashSet<>(stored.get());
        }, listener -> true, KEY, LEGACY_KEY);

        Set<String> first = cached.get();
        assertSame(first, cached.get());
        assertEquals(1, loads.get());

        cached.onChanged("disable_ads");
        assertSame(first, cached.get());
        assertEquals(1, loads.get());

        stored.set(Collections.singleton("posts"));
        cached.onChanged(KEY);
        assertEquals(Collections.singleton("posts"), cached.get());
        assertEquals(2, loads.get());

        cached.onChanged(LEGACY_KEY);
        cached.get();
        assertEquals(3, loads.get());
    }

    @Test
    public void clearingTheFileReloads() {
        AtomicInteger loads = new AtomicInteger();
        Pref.Cached<Integer> cached = new Pref.Cached<>(loads::incrementAndGet, listener -> true, KEY);

        assertEquals(Integer.valueOf(1), cached.get());
        cached.onChanged(null);
        assertEquals(Integer.valueOf(2), cached.get());
        assertEquals(Integer.valueOf(2), cached.get());
    }

    @Test
    public void readsEveryTimeUntilTheListenerIsRegistered() {
        AtomicInteger loads = new AtomicInteger();
        AtomicInteger registrations = new AtomicInteger();
        AtomicBoolean settingsOpen = new AtomicBoolean(false);
        Pref.Cached<Integer> cached = new Pref.Cached<>(loads::incrementAndGet, listener -> {
            registrations.incrementAndGet();
            return settingsOpen.get();
        }, KEY);

        cached.get();
        cached.get();
        assertEquals(2, loads.get());

        settingsOpen.set(true);
        cached.get();
        cached.get();
        assertEquals(3, loads.get());
        assertEquals(3, registrations.get());
    }

    @Test
    public void aFailedRegistrationStillReturnsTheValue() {
        AtomicInteger loads = new AtomicInteger();
        Pref.Cached<Integer> cached = new Pref.Cached<>(loads::incrementAndGet, listener -> {
            throw new IllegalStateException("no settings file");
        }, KEY);

        assertEquals(Integer.valueOf(1), cached.get());
        assertEquals(Integer.valueOf(2), cached.get());
    }
}
