package com.shakenokirimi12.uoa_app.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import org.junit.Test;

public class InMemorySharedPreferencesTest {
    @Test
    public void putAndGet() {
        SharedPreferences p = new InMemorySharedPreferences();
        p.edit().putString("password", "x").putBoolean("b", true).apply();
        assertEquals("x", p.getString("password", ""));
        assertTrue(p.getBoolean("b", false));
        assertEquals("d", p.getString("missing", "d"));
    }

    @Test
    public void removeAndNullPutDeleteKey() {
        SharedPreferences p = new InMemorySharedPreferences();
        p.edit().putString("a", "1").putString("b", "2").apply();
        p.edit().remove("a").putString("b", null).apply();
        assertFalse(p.contains("a"));
        assertFalse(p.contains("b"));
    }

    @Test
    public void clearRunsBeforePutsInSameEdit() {
        SharedPreferences p = new InMemorySharedPreferences();
        p.edit().putString("old", "1").apply();
        p.edit().putString("new", "2").clear().commit();
        assertFalse(p.contains("old"));
        assertEquals("2", p.getString("new", ""));
    }
}
