package com.serranofp.alloy.viewer

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.UIUtil
import com.intellij.util.xmlb.XmlSerializer
import com.serranofp.alloy.run.AlloyCommandRef
import com.serranofp.alloy.run.AlloyCommandResults
import com.serranofp.alloy.run.CommandLink
import com.serranofp.alloy.run.CommandResult
import com.serranofp.alloy.run.CommandState
import com.serranofp.alloy.settings.AlloySettings
import edu.mit.csail.sdg.alloy4.Util
import java.util.prefs.Preferences

class AlloyInstanceTabsTest : BasePlatformTestCase() {
    private val viewer get() = AlloyInstanceViewer.getInstance(project)
    private val show = AlloyCommandRef("show", 0)

    override fun setUp() {
        super.setUp()
        AlloyCommandResults.getInstance(project).clear()
        viewer.clear()
        viewer.selectedTab = AlloyInstanceViewer.VISUALIZATION
        myFixture.configureByText("m.als", "pred show {}\nrun show for 3\n")
    }

    private fun open(model: String) {
        val results = AlloyCommandResults.getInstance(project)
        val result = CommandResult(
            CommandState.InstanceFound, "found", link = CommandLink.Solution(solve(model)),
            documentStamp = results.stamp(myFixture.editor.document), settingsGeneration = AlloySettings.getInstance().generation,
        )
        viewer.open(myFixture.file.virtualFile, show, result)
        UIUtil.dispatchAllInvocationEvents()
    }

    private fun panel() = AlloyInstancePanel(project, testRootDisposable)

    private fun AlloyInstancePanel.tabs() = UIUtil.findComponentOfType(this, JBTabbedPane::class.java)!!

    private fun AlloyInstancePanel.textAreas() = UIUtil.findComponentsOfType(this, JBTextArea::class.java)

    fun testTextAndTableAreTheAnalyzersOutput() {
        val panel = panel()
        open(LASSO)
        val model = viewer.session!!.model
        val (text, table) = panel.textAreas()
        assertEquals(model.solution.toString(0), text.text)
        assertEquals(model.solution.format(0), table.text)
        assertTrue(text.text, text.text.contains("this/Cur={V1$0}"))
    }

    fun testTabsFollowTheState() {
        val panel = panel()
        open(LASSO)
        val model = viewer.session!!.model
        // As the stepper's actions do
        model.next()
        panel.stateChanged()
        val (text, table) = panel.textAreas()
        assertEquals(model.solution.toString(1), text.text)
        assertEquals(model.solution.format(1), table.text)
        assertTrue(text.text, text.text.contains("this/Cur={V2$0}"))
    }

    fun testVisualizationIsTheDefaultTab() {
        val panel = panel()
        open(STATIC)
        assertEquals(AlloyInstanceViewer.VISUALIZATION, panel.tabs().getTitleAt(panel.tabs().selectedIndex))
    }

    fun testTheChosenTabStaysForTheNextResult() {
        val panel = panel()
        open(STATIC)
        panel.tabs().selectedIndex = 2
        assertEquals(AlloyInstanceViewer.TABLE, viewer.selectedTab)

        open(LASSO)
        assertEquals(AlloyInstanceViewer.TABLE, panel.tabs().getTitleAt(panel.tabs().selectedIndex))
        // A new tool window, as in a new IDE session, starts on it too
        assertEquals(AlloyInstanceViewer.TABLE, panel().tabs().let { it.getTitleAt(it.selectedIndex) })
    }

    fun testTheChosenTabIsSaved() {
        viewer.selectedTab = AlloyInstanceViewer.TEXT
        val saved = XmlSerializer.deserialize(XmlSerializer.serialize(viewer.state), AlloyInstanceViewerState::class.java)
        assertEquals(AlloyInstanceViewer.TEXT, saved.selectedTab)
    }

    /** The desktop visualizer keeps its mode and themes in this node; the tool window leaves it alone. */
    fun testDesktopPreferencesAreUntouched() {
        val path = Preferences.userNodeForPackage(Util::class.java).absolutePath()
        fun snapshot(): Map<String, String?>? {
            if (!Preferences.userRoot().nodeExists(path)) return null
            val node = Preferences.userRoot().node(path)
            node.sync()
            return node.keys().associateWith { node.get(it, null) }
        }
        val before = snapshot()
        val panel = panel()
        open(ORDERING)
        panel.tabs().selectedIndex = 1
        panel.tabs().selectedIndex = 2
        viewer.session!!.model.clearProjections()
        panel.rebuild()
        assertEquals(before, snapshot())
    }
}
