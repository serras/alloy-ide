package com.serranofp.alloy.viewer

import com.intellij.openapi.diff.DiffColors
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
import javax.swing.text.AttributeSet
import javax.swing.text.StyleConstants

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

    private fun AlloyInstancePanel.textPane() = UIUtil.findComponentOfType(this, AlloyTextPane::class.java)!!

    /** A single state, which shows no difference. */
    fun testTextAndTableAreTheAnalyzersOutput() {
        val panel = panel()
        open(STATIC)
        val model = viewer.session!!.model
        val text = panel.textPane()
        val table = panel.textAreas().single()
        assertEquals(model.solution.toString(0), text.text)
        assertEquals(model.solution.format(0), table.text)
        assertTrue(text.text, text.text.contains("this/Node="))
        // Without styles
        val document = text.styledDocument
        for (i in 0 until document.length) {
            val attributes = document.getCharacterElement(i).attributes
            assertFalse(attributes.isDefined(StyleConstants.Background) || attributes.isDefined(StyleConstants.Foreground) || StyleConstants.isStrikeThrough(attributes))
        }
    }

    private fun AlloyTextPane.attributesOf(tuple: String, after: String): AttributeSet {
        val offset = text.indexOf(tuple, text.indexOf(after))
        assertTrue("$tuple after $after", offset >= 0)
        return styledDocument.getCharacterElement(offset).attributes
    }

    fun testTextOfATraceIsTheDiffToTheNextState() {
        val panel = panel()
        open(LASSO)
        val model = viewer.session!!.model
        val text = panel.textPane()
        // State 0 to state 1: Cur goes from V1 to V2
        val diff = model.diff!!
        for (line in diff.lines) assertTrue(line.text, text.text.contains(line.text + "\n"))
        assertEquals(diff.text, text.text)

        val inserted = AlloyTextPane.attributes(DiffColors.DIFF_INSERTED)
        val deleted = AlloyTextPane.attributes(DiffColors.DIFF_DELETED)
        assertNotNull(StyleConstants.getBackground(inserted))
        val added = text.attributesOf("V2$0", "this/Cur=")
        assertEquals(StyleConstants.getBackground(inserted), StyleConstants.getBackground(added))
        assertFalse(StyleConstants.isStrikeThrough(added))
        val removed = text.attributesOf("V1$0", "this/Cur=")
        assertEquals(StyleConstants.getBackground(deleted), StyleConstants.getBackground(removed))
        assertTrue(StyleConstants.isStrikeThrough(removed))
        // An unchanged line is dimmed, and has no marks
        val unchanged = text.attributesOf("V1$0", "this/V1=")
        assertEquals(UIUtil.getContextHelpForeground(), StyleConstants.getForeground(unchanged))
        assertFalse(StyleConstants.isStrikeThrough(unchanged))
        assertFalse(unchanged.isDefined(StyleConstants.Background))
    }

    private fun AlloyInstancePanel.tablePanes() = UIUtil.findComponentOfType(this, JBTabbedPane::class.java)!!.getComponentAt(2) as AlloyStatePanes

    private fun AlloyStatePanes.tables() = UIUtil.findComponentsOfType(this, JBTextArea::class.java).map { it.text }

    fun testTableOfATraceShowsBothStates() {
        val panel = panel()
        open(LASSO)
        val model = viewer.session!!.model
        model.goTo(2)
        panel.stateChanged()
        val tables = panel.tablePanes()
        assertEquals(listOf(model.solution.format(2), model.solution.format(1)), tables.tables())
        assertEquals(listOf("State 2", "State 1 (loop)"), tables.captions)
    }

    fun testTableOfAStaticResultIsTheAnalyzersSingleTable() {
        val panel = panel()
        open(STATIC)
        val tables = panel.tablePanes()
        assertEquals(listOf(viewer.session!!.model.solution.format(0)), tables.tables())
        assertEquals(listOf(null), tables.captions)
    }

    fun testTabsFollowTheState() {
        val panel = panel()
        open(LASSO)
        val model = viewer.session!!.model
        // As clicking the successor in the trace path strip does
        model.next()
        panel.stateChanged()
        val table = panel.textAreas().first()
        assertEquals(TraceDiff.of(model.solution, 1, 2).text, panel.textPane().text)
        assertEquals(model.solution.format(1), table.text)
        assertTrue(panel.textPane().text, panel.textPane().text.contains("this/Cur={V2$0, V3$0}"))
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
