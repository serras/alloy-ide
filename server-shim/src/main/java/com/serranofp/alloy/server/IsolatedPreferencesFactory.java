package com.serranofp.alloy.server;

import edu.mit.csail.sdg.alloy4.Util;
import java.util.Map;
import java.util.Properties;
import java.util.prefs.Preferences;
import java.util.prefs.PreferencesFactory;

/**
 * Keeps the analyzer preferences of the language server apart from the Alloy desktop application.
 *
 * <p>Installed with {@code -Djava.util.prefs.PreferencesFactory=}, so that it is in place before any
 * {@link Preferences} is used. Nothing is read from or written to the platform preference store: the user
 * tree starts with the {@value #PROPERTY_PREFIX}{@code <id>} system properties copied into the node that
 * {@code A4Preferences} uses, and every change stays in memory.
 */
public final class IsolatedPreferencesFactory implements PreferencesFactory {
    public static final String PROPERTY_PREFIX = "alloy.ide.pref.";

    private Preferences userRoot;
    private Preferences systemRoot;

    @Override
    public synchronized Preferences userRoot() {
        if (userRoot == null) {
            userRoot = new MemoryPreferences();
            seed(userRoot, System.getProperties());
        }
        return userRoot;
    }

    @Override
    public synchronized Preferences systemRoot() {
        if (systemRoot == null) {
            systemRoot = new MemoryPreferences();
        }
        return systemRoot;
    }

    static void seed(Preferences root, Properties properties) {
        // Same node as Preferences.userNodeForPackage(Util.class), which A4Preferences.Pref reads and writes
        Preferences analyzer = root.node("/" + Util.class.getPackageName().replace('.', '/'));
        for (Map.Entry<Object, Object> property : properties.entrySet()) {
            String key = property.getKey().toString();
            if (key.startsWith(PROPERTY_PREFIX) && key.length() > PROPERTY_PREFIX.length()) {
                analyzer.put(key.substring(PROPERTY_PREFIX.length()), property.getValue().toString());
            }
        }
    }
}
