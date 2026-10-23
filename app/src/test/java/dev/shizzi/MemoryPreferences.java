package dev.shizzi;

import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** SharedPreferences transaction fixture: failed commits leave storage intact. */
final class MemoryPreferences implements SharedPreferences {
    private final Map<String, Object> values = new HashMap<>();
    boolean failWrites;
    public Map<String, ?> getAll() { return new HashMap<>(values); }
    public String getString(String key, String fallback) { return (String) values.getOrDefault(key, fallback); }
    @SuppressWarnings("unchecked")
    public Set<String> getStringSet(String key, Set<String> fallback) { return (Set<String>) values.getOrDefault(key, fallback); }
    public int getInt(String key, int fallback) { return (Integer) values.getOrDefault(key, fallback); }
    public long getLong(String key, long fallback) { return (Long) values.getOrDefault(key, fallback); }
    public float getFloat(String key, float fallback) { return (Float) values.getOrDefault(key, fallback); }
    public boolean getBoolean(String key, boolean fallback) { return (Boolean) values.getOrDefault(key, fallback); }
    public boolean contains(String key) { return values.containsKey(key); }
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}
    public Editor edit() {
        return new Editor() {
            private final Map<String, Object> next = new HashMap<>(values);
            public Editor putString(String key, String value) { next.put(key, value); return this; }
            public Editor putStringSet(String key, Set<String> value) { next.put(key, value); return this; }
            public Editor putInt(String key, int value) { next.put(key, value); return this; }
            public Editor putLong(String key, long value) { next.put(key, value); return this; }
            public Editor putFloat(String key, float value) { next.put(key, value); return this; }
            public Editor putBoolean(String key, boolean value) { next.put(key, value); return this; }
            public Editor remove(String key) { next.remove(key); return this; }
            public Editor clear() { next.clear(); return this; }
            public boolean commit() {
                if (failWrites) return false;
                values.clear(); values.putAll(next); return true;
            }
            public void apply() { throw new AssertionError("Accounting must commit synchronously"); }
        };
    }
}
