package com.shakenokirimi12.uoa_app.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;

import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class DeviceIdTest {

    /** 平文の prefs の代わり。commit で書いた値だけを持つ。 */
    private static final class MemoryPrefs implements SharedPreferences {
        final Map<String, Object> values = new HashMap<>();
        int commits = 0;

        @Override public Map<String, ?> getAll() { synchronized (values) { return new HashMap<>(values); } }
        @Nullable @Override public String getString(String k, @Nullable String d) {
            synchronized (values) { return values.containsKey(k) ? (String) values.get(k) : d; }
        }
        @Nullable @Override public Set<String> getStringSet(String k, @Nullable Set<String> d) { return d; }
        @Override public int getInt(String k, int d) { return d; }
        @Override public long getLong(String k, long d) { return d; }
        @Override public float getFloat(String k, float d) { return d; }
        @Override public boolean getBoolean(String k, boolean d) { return d; }
        @Override public boolean contains(String k) { synchronized (values) { return values.containsKey(k); } }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}

        @Override public Editor edit() {
            return new Editor() {
                final Map<String, Object> pending = new HashMap<>();
                boolean clear = false;
                @Override public Editor putString(String k, @Nullable String v) { pending.put(k, v); return this; }
                @Override public Editor putStringSet(String k, @Nullable Set<String> v) { pending.put(k, v); return this; }
                @Override public Editor putInt(String k, int v) { pending.put(k, v); return this; }
                @Override public Editor putLong(String k, long v) { pending.put(k, v); return this; }
                @Override public Editor putFloat(String k, float v) { pending.put(k, v); return this; }
                @Override public Editor putBoolean(String k, boolean v) { pending.put(k, v); return this; }
                @Override public Editor remove(String k) { pending.put(k, null); return this; }
                @Override public Editor clear() { clear = true; return this; }
                @Override public boolean commit() {
                    synchronized (values) {
                        if (clear) values.clear();
                        for (Map.Entry<String, Object> e : pending.entrySet()) {
                            if (e.getValue() == null) values.remove(e.getKey());
                            else values.put(e.getKey(), e.getValue());
                        }
                        commits++;
                    }
                    return true;
                }
                @Override public void apply() { commit(); }
            };
        }
    }

    @Test
    public void createsOnceAndKeepsTheSameIdAfterwards() {
        MemoryPrefs prefs = new MemoryPrefs();
        String first = PreferenceManager.deviceIdIn(prefs);

        assertFalse(first.isEmpty());
        assertEquals(first, PreferenceManager.deviceIdIn(prefs));
        assertEquals(first, prefs.values.get("device_id"));
        assertEquals(1, prefs.commits);
    }

    @Test
    public void keepsAnIdWrittenByAnEarlierVersion() {
        MemoryPrefs prefs = new MemoryPrefs();
        prefs.values.put("device_id", "from-1.0");

        assertEquals("from-1.0", PreferenceManager.deviceIdIn(prefs));
        assertEquals(0, prefs.commits);
    }

    @Test
    public void callersOnSeveralThreadsAtFirstLaunchGetOneId() throws Exception {
        MemoryPrefs prefs = new MemoryPrefs();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return PreferenceManager.deviceIdIn(prefs);
            }));
        }
        start.countDown();
        Set<String> ids = new HashSet<>();
        for (Future<String> f : results) ids.add(f.get());
        pool.shutdown();

        assertEquals(1, ids.size());
        assertEquals(1, prefs.commits);
    }

    @Test
    public void aClearedStoreGetsANewId() {
        MemoryPrefs prefs = new MemoryPrefs();
        String before = PreferenceManager.deviceIdIn(prefs);
        prefs.edit().clear().commit();

        assertNotEquals(before, PreferenceManager.deviceIdIn(prefs));
    }
}
