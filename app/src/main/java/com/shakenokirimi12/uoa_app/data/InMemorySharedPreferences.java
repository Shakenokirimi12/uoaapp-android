package com.shakenokirimi12.uoa_app.data;

import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * ディスクに何も書かない SharedPreferences。暗号化 prefs がどうしても開けない端末で、資格情報を
 * 平文ファイルに置く代わりに使う。プロセスが終われば消える (次回起動でログインし直しになる)。
 */
final class InMemorySharedPreferences implements SharedPreferences {
    private final Map<String, Object> values = new HashMap<>();
    private final List<OnSharedPreferenceChangeListener> listeners = new CopyOnWriteArrayList<>();

    @Override
    public synchronized Map<String, ?> getAll() {
        return new HashMap<>(values);
    }

    @Nullable
    @Override
    public synchronized String getString(String key, @Nullable String defValue) {
        Object v = values.get(key);
        return v != null ? (String) v : defValue;
    }

    @Nullable
    @Override
    @SuppressWarnings("unchecked")
    public synchronized Set<String> getStringSet(String key, @Nullable Set<String> defValues) {
        Object v = values.get(key);
        return v != null ? new HashSet<>((Set<String>) v) : defValues;
    }

    @Override
    public synchronized int getInt(String key, int defValue) {
        Object v = values.get(key);
        return v != null ? (Integer) v : defValue;
    }

    @Override
    public synchronized long getLong(String key, long defValue) {
        Object v = values.get(key);
        return v != null ? (Long) v : defValue;
    }

    @Override
    public synchronized float getFloat(String key, float defValue) {
        Object v = values.get(key);
        return v != null ? (Float) v : defValue;
    }

    @Override
    public synchronized boolean getBoolean(String key, boolean defValue) {
        Object v = values.get(key);
        return v != null ? (Boolean) v : defValue;
    }

    @Override
    public synchronized boolean contains(String key) {
        return values.containsKey(key);
    }

    @Override
    public Editor edit() {
        return new MemoryEditor();
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        listeners.add(listener);
    }

    @Override
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        listeners.remove(listener);
    }

    private final class MemoryEditor implements Editor {
        private final Map<String, Object> pending = new HashMap<>();
        private final Set<String> removals = new HashSet<>();
        private boolean clear = false;

        @Override public Editor putString(String key, @Nullable String value) { return put(key, value); }
        @Override public Editor putStringSet(String key, @Nullable Set<String> values) {
            return put(key, values != null ? new HashSet<>(values) : null);
        }
        @Override public Editor putInt(String key, int value) { return put(key, value); }
        @Override public Editor putLong(String key, long value) { return put(key, value); }
        @Override public Editor putFloat(String key, float value) { return put(key, value); }
        @Override public Editor putBoolean(String key, boolean value) { return put(key, value); }

        private synchronized Editor put(String key, @Nullable Object value) {
            // SharedPreferences の仕様どおり、null を入れるのは remove と同じ。
            if (value == null) {
                removals.add(key);
                pending.remove(key);
            } else {
                pending.put(key, value);
                removals.remove(key);
            }
            return this;
        }

        @Override
        public synchronized Editor remove(String key) {
            removals.add(key);
            pending.remove(key);
            return this;
        }

        @Override
        public synchronized Editor clear() {
            clear = true;
            return this;
        }

        @Override
        public boolean commit() {
            List<String> changed = new ArrayList<>();
            synchronized (InMemorySharedPreferences.this) {
                if (clear) {
                    changed.addAll(values.keySet());
                    values.clear();
                }
                for (String key : removals) {
                    if (values.remove(key) != null) changed.add(key);
                }
                for (Map.Entry<String, Object> e : pending.entrySet()) {
                    values.put(e.getKey(), e.getValue());
                    changed.add(e.getKey());
                }
            }
            for (OnSharedPreferenceChangeListener l : listeners) {
                for (String key : changed) l.onSharedPreferenceChanged(InMemorySharedPreferences.this, key);
            }
            return true;
        }

        @Override
        public void apply() {
            commit();
        }
    }
}
