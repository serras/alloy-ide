package com.serranofp.alloy.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import edu.mit.csail.sdg.alloy4.A4Preferences;
import edu.mit.csail.sdg.alloy4.Util;
import java.lang.reflect.Constructor;
import java.util.Properties;
import java.util.prefs.Preferences;
import java.util.prefs.PreferencesFactory;
import org.junit.Test;

/**
 * The test JVM is started with the isolated factory, {@code alloy.ide.pref.SkolemDepth3=3} and
 * {@code alloy.ide.pref.NoOverflow=n} (see build.gradle.kts).
 */
public class IsolatedPreferencesFactoryTest {
    private static final String ANALYZER_NODE = "/" + Util.class.getPackageName().replace('.', '/');

    @Test
    public void factoryIsInstalled() {
        assertTrue(Preferences.userRoot() instanceof MemoryPreferences);
        assertTrue(Preferences.systemRoot() instanceof MemoryPreferences);
    }

    @Test
    public void seededValueIsSeenByAnalyzer() {
        assertEquals(Integer.valueOf(3), A4Preferences.SkolemDepth.get());
    }

    @Test
    public void seededBooleanUsesAnalyzerEncoding() {
        // NoOverflow defaults to true, so this only holds if "n" was read as false
        assertFalse(A4Preferences.NoOverflow.get());
    }

    @Test
    public void unseededKeysUseUpstreamDefaults() {
        assertEquals(Integer.valueOf(0), A4Preferences.CoreGranularity.get());
        assertEquals(Integer.valueOf(2), A4Preferences.CoreMinimization.get());
        assertFalse(A4Preferences.RecordKodkod.get());
        assertFalse(A4Preferences.InferPartialInstance.get());
    }

    @Test
    public void seedingCopiesOnlyPrefixedProperties() {
        Properties properties = new Properties();
        properties.setProperty("alloy.ide.pref.NoOverflow", "n");
        properties.setProperty("alloy.ide.pref.", "ignored");
        properties.setProperty("other.NoOverflow", "ignored");
        Preferences root = new MemoryPreferences();
        IsolatedPreferencesFactory.seed(root, properties);
        Preferences node = root.node(ANALYZER_NODE);
        assertEquals("n", node.get("NoOverflow", null));
        assertEquals(1, keyCount(node));
    }

    @Test
    public void writesDoNotReachThePlatformStore() throws Exception {
        Preferences platformRoot = platformFactory().userRoot();
        boolean existed = platformRoot.nodeExists(ANALYZER_NODE);
        String before = existed ? platformRoot.node(ANALYZER_NODE).get(A4Preferences.RecordKodkod.id, null) : null;

        boolean original = A4Preferences.RecordKodkod.get();
        A4Preferences.RecordKodkod.set(!original);
        try {
            assertEquals(!original, A4Preferences.RecordKodkod.get());
            // A4Preferences stores booleans as "y"/"n"
            assertEquals(original ? "n" : "y", Preferences.userRoot().node(ANALYZER_NODE).get(A4Preferences.RecordKodkod.id, null));
            assertEquals(existed, platformRoot.nodeExists(ANALYZER_NODE));
            if (existed) {
                platformRoot.node(ANALYZER_NODE).sync();
                assertEquals(before, platformRoot.node(ANALYZER_NODE).get(A4Preferences.RecordKodkod.id, null));
            }
        } finally {
            A4Preferences.RecordKodkod.set(original);
        }
    }

    private static int keyCount(Preferences node) {
        try {
            return node.keys().length;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /** The factory the JDK would use without the override; only read from. */
    private static PreferencesFactory platformFactory() throws Exception {
        String os = System.getProperty("os.name").toLowerCase();
        String name = os.contains("mac") ? "java.util.prefs.MacOSXPreferencesFactory"
                : os.contains("win") ? "java.util.prefs.WindowsPreferencesFactory"
                : "java.util.prefs.FileSystemPreferencesFactory";
        Constructor<?> constructor = Class.forName(name).getDeclaredConstructor();
        constructor.setAccessible(true);
        return (PreferencesFactory) constructor.newInstance();
    }
}
