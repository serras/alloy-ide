package com.serranofp.alloy.settings

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.serranofp.alloy.run.AlloyCommandRef
import com.serranofp.alloy.run.AlloyCommandResults
import com.serranofp.alloy.run.AlloyRunService
import com.serranofp.alloy.run.CommandState
import com.serranofp.alloy.run.waitUntilIdle
import edu.mit.csail.sdg.alloy4.Util
import java.util.prefs.Preferences

/** The Alloy desktop application keeps its preferences in this node; runs in the IDE leave it alone. */
class AlloyDesktopPreferencesTest : BasePlatformTestCase() {
    private val path = Preferences.userNodeForPackage(Util::class.java).absolutePath()

    /** The node's values, or null if it does not exist; reading it never creates it. */
    private fun snapshot(): Map<String, String?>? {
        val root = Preferences.userRoot()
        if (!root.nodeExists(path)) return null
        val node = root.node(path)
        node.sync()
        return node.keys().associateWith { node.get(it, null) }
    }

    /** The preferences are the build's in-memory ones, so the test cannot change the developer's own. */
    fun testPreferencesAreInMemory() {
        assertEquals(InMemoryPreferencesFactory::class.java.name, System.getProperty("java.util.prefs.PreferencesFactory"))
        assertEquals(InMemoryPreferencesFactory::class.java, Preferences.userRoot().javaClass.enclosingClass)
    }

    fun testRunLeavesTheDesktopPreferencesUnchanged() {
        // A value of the desktop application's, which the run must keep
        Preferences.userRoot().node(path).put("marker", "kept")
        val before = snapshot()
        AlloyCommandResults.getInstance(project).clear()
        val settings = AlloySettings.getInstance()
        val saved = AlloySettingsState().apply { copyFrom(settings.state) }
        settings.state.skolemDepth = 3
        try {
            val file = myFixture.configureByText("model.als", "sig A { f: set A }\npred show { some f }\nrun show for 3\n").virtualFile
            assertTrue(AlloyRunService.getInstance(project).launch(file, AlloyCommandRef("show", 0), myFixture.editor))
            waitUntilIdle(project)
            val result = AlloyCommandResults.getInstance(project).get(file, AlloyCommandRef("show", 0))!!
            assertEquals(CommandState.InstanceFound, result.state)
            assertTrue(result.details.log.any { "SkolemDepth=3" in it.text })
        } finally {
            settings.loadState(saved)
        }
        assertEquals(before, snapshot())
    }
}
