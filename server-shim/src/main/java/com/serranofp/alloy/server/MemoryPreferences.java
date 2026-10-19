package com.serranofp.alloy.server;

import java.util.HashMap;
import java.util.Map;
import java.util.prefs.AbstractPreferences;

/** A preference node that lives only in memory and is discarded when the process exits. */
final class MemoryPreferences extends AbstractPreferences {
    private final Map<String, String> values = new HashMap<>();

    MemoryPreferences() {
        super(null, "");
    }

    private MemoryPreferences(MemoryPreferences parent, String name) {
        super(parent, name);
    }

    @Override
    protected void putSpi(String key, String value) {
        values.put(key, value);
    }

    @Override
    protected String getSpi(String key) {
        return values.get(key);
    }

    @Override
    protected void removeSpi(String key) {
        values.remove(key);
    }

    @Override
    protected void removeNodeSpi() {
        values.clear();
    }

    @Override
    protected String[] keysSpi() {
        return values.keySet().toArray(new String[0]);
    }

    @Override
    protected String[] childrenNamesSpi() {
        // AbstractPreferences caches every child it creates, and nothing exists beyond those
        return new String[0];
    }

    @Override
    protected AbstractPreferences childSpi(String name) {
        return new MemoryPreferences(this, name);
    }

    @Override
    protected void syncSpi() {
    }

    @Override
    protected void flushSpi() {
    }
}
