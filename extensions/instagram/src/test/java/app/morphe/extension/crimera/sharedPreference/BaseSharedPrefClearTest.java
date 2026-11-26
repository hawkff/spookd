package app.morphe.extension.crimera.sharedPreference;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE)
public final class BaseSharedPrefClearTest {

    /** Preferences whose writes never reach the disk. */
    private static final class FailingPreferences implements SharedPreferences, SharedPreferences.Editor {
        @Override public Map<String, ?> getAll() { return Collections.emptyMap(); }
        @Override public String getString(String key, String defValue) { return defValue; }
        @Override public Set<String> getStringSet(String key, Set<String> defValues) { return defValues; }
        @Override public int getInt(String key, int defValue) { return defValue; }
        @Override public long getLong(String key, long defValue) { return defValue; }
        @Override public float getFloat(String key, float defValue) { return defValue; }
        @Override public boolean getBoolean(String key, boolean defValue) { return defValue; }
        @Override public boolean contains(String key) { return false; }
        @Override public Editor edit() { return this; }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}
        @Override public Editor putString(String key, String value) { return this; }
        @Override public Editor putStringSet(String key, Set<String> values) { return this; }
        @Override public Editor putInt(String key, int value) { return this; }
        @Override public Editor putLong(String key, long value) { return this; }
        @Override public Editor putFloat(String key, float value) { return this; }
        @Override public Editor putBoolean(String key, boolean value) { return this; }
        @Override public Editor remove(String key) { return this; }
        @Override public Editor clear() { return this; }
        @Override public boolean commit() { return false; }
        @Override public void apply() {}
    }

    private static BaseSharedPref prefs(Context context) {
        return new BaseSharedPref(context, "base_shared_pref_clear_test") {};
    }

    @Test
    public void aClearThatIsNotWrittenReportsFailure() {
        Context failing = new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override
            public SharedPreferences getSharedPreferences(String name, int mode) {
                return new FailingPreferences();
            }
        };

        assertFalse(prefs(failing).clear());
    }

    @Test
    public void aWrittenClearReportsSuccess() {
        assertTrue(prefs(RuntimeEnvironment.getApplication()).clear());
    }
}
