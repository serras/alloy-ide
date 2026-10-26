package com.serranofp.alloy.viewer

import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.impl.ActionToolbarImpl
import com.intellij.openapi.diff.DiffColors
import com.intellij.testFramework.TestActionEvent
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.UIUtil
import com.intellij.util.xmlb.XmlSerializer
import com.serranofp.alloy.AlloyIcons
import com.serranofp.alloy.run.waitUntil
import edu.mit.csail.sdg.alloy4.Util
import java.awt.event.KeyEvent
import java.util.prefs.Preferences
import javax.swing.SwingUtilities
import javax.swing.text.AttributeSet
import javax.swing.text.StyleConstants

class AlloyInstanceTabsTest : AlloyViewerTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.configureByText("m.als", SHOW_FILE)
    }

    private fun AlloyInstancePanel.textAreas() = UIUtil.findComponentsOfType(this, JBTextArea::class.java)

    private fun AlloyInstancePanel.textPane() = UIUtil.findComponentOfType(this, AlloyTextPane::class.java)!!

    /** A single state, which shows no difference. */
    fun testTextAndTableAreTheAnalyzersOutput() {
        val panel = panel()
        open(STATIC)
        val model = viewer.session(myFixture.file.virtualFile)!!.model
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
        val model = viewer.session(myFixture.file.virtualFile)!!.model
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

    private fun AlloyInstancePanel.tablePanes() = UIUtil.findComponentsOfType(this, AlloyStatePanes::class.java).last()

    private fun AlloyStatePanes.tables() = UIUtil.findComponentsOfType(this, JBTextArea::class.java).map { it.text }

    fun testTableOfATraceShowsBothStates() {
        val panel = panel()
        open(LASSO)
        val model = viewer.session(myFixture.file.virtualFile)!!.model
        model.goTo(2)
        panel.stateChanged()
        val tables = panel.tablePanes()
        assertEquals(listOf(model.solution.format(2), model.solution.format(1)), tables.tables())
        assertEquals(listOf("State 2", "State 1 (loop)"), tables.shownCaptions)
    }

    fun testTableOfAStaticResultIsTheAnalyzersSingleTable() {
        val panel = panel()
        open(STATIC)
        val tables = panel.tablePanes()
        assertEquals(listOf(viewer.session(myFixture.file.virtualFile)!!.model.solution.format(0)), tables.tables())
        assertEquals(listOf(null), tables.shownCaptions)
    }

    fun testTabsFollowTheState() {
        val panel = panel()
        open(LASSO)
        val model = viewer.session(myFixture.file.virtualFile)!!.model
        // Right in the trace path strip selects the successor
        UIUtil.findComponentOfType(panel, AlloyTraceStrip::class.java)!!.press(KeyEvent.VK_RIGHT)
        waitUntil("state 1 was not shown") { model.state == 1 }
        val table = panel.textAreas().first()
        assertEquals(TraceDiff.of(model.solution, 1, 2).text, panel.textPane().text)
        assertEquals(model.solution.format(1), table.text)
        assertTrue(panel.textPane().text, panel.textPane().text.contains("this/Cur={V2$0, V3$0}"))
    }

    fun testVisualizationIsTheDefaultTab() {
        val panel = panel()
        open(STATIC)
        assertEquals(AlloyInstanceViewer.VISUALIZATION, panel.selectedTab)
    }

    fun testTheChosenTabStaysForTheNextResult() {
        val panel = panel()
        open(STATIC)
        panel.selectTab(AlloyInstanceViewer.TABLE)
        assertEquals(AlloyInstanceViewer.TABLE, viewer.selectedTab)

        open(LASSO)
        assertEquals(AlloyInstanceViewer.TABLE, panel.selectedTab)
        // A new tool window, as in a new IDE session, starts on it too
        assertEquals(AlloyInstanceViewer.TABLE, panel().selectedTab)
    }

    private fun AlloyInstancePanel.tabActions(): List<ToggleAction> {
        val toolbar = UIUtil.findComponentsOfType(this, ActionToolbarImpl::class.java).single { it.place == "AlloyInstanceTabs" }
        return (toolbar.actionGroup as DefaultActionGroup).childActionsOrStubs.map { it as ToggleAction }
    }

    private fun ToggleAction.selected() = isSelected(TestActionEvent.createTestEvent(this))

    /** The view of each tab, in the order of the tab buttons. */
    private fun AlloyInstancePanel.views() = SwingUtilities.getAncestorOfClass(JBScrollPane::class.java, textPane()).parent.components.toList()

    fun testTabButtonsAreTheTabs() {
        val actions = panel().tabActions()
        assertEquals(listOf("Visualization", "Text", "Table"), actions.map { it.templateText })
        for (action in actions) assertNotNull(action.templateText, action.templatePresentation.icon)
    }

    fun testPressingATabButtonShowsItsTab() {
        val panel = panel()
        open(STATIC)
        val actions = panel.tabActions()
        assertEquals(listOf(true, false, false), actions.map { it.selected() })
        actions[1].setSelected(TestActionEvent.createTestEvent(actions[1]), true)
        assertEquals(AlloyInstanceViewer.TEXT, panel.selectedTab)
        assertEquals(listOf(false, true, false), panel.views().map { it.isVisible })
        assertEquals(listOf(false, true, false), actions.map { it.selected() })
    }

    private fun ToggleAction.press() = TestActionEvent.createTestEvent(this).let { setSelected(it, !isSelected(it)) }

    private fun ToggleAction.icon() = TestActionEvent.createTestEvent(this).also(::update).presentation.icon

    /** The panes of the Visualization tab, then those of the Table tab. */
    private fun AlloyInstancePanel.statePanes() = UIUtil.findComponentsOfType(this, AlloyStatePanes::class.java)

    private fun AlloyInstancePanel.assertLayout(layout: PaneLayout) {
        assertEquals(layout, viewer.paneLayout)
        assertEquals(listOf(layout, layout), statePanes().map { it.paneLayout })
        val (visualization, _, table) = tabActions()
        val column = layout == PaneLayout.COLUMN
        assertEquals(if (column) AlloyIcons.VISUALIZATION_COLUMN else AlloyIcons.VISUALIZATION_ROW, visualization.icon())
        assertEquals(if (column) AlloyIcons.TABLE_COLUMN else AlloyIcons.TABLE_ROW, table.icon())
    }

    fun testPressingTheSelectedTabButtonSwitchesTheLayout() {
        val panel = panel()
        open(LASSO)
        val table = panel.tabActions()[2]
        table.press()
        panel.assertLayout(PaneLayout.ROW)
        // Pressed again, the tab stays selected, and its panes are stacked
        table.press()
        assertEquals(AlloyInstanceViewer.TABLE, panel.selectedTab)
        assertTrue(table.selected())
        assertEquals(listOf(false, false, true), panel.views().map { it.isVisible })
        panel.assertLayout(PaneLayout.COLUMN)
        table.press()
        panel.assertLayout(PaneLayout.ROW)
    }

    fun testSwitchingTabsKeepsTheLayout() {
        val panel = panel()
        open(LASSO)
        val (visualization, _, table) = panel.tabActions()
        // Visualization is selected: pressing it stacks its panes; Table then shows its tables stacked too
        visualization.press()
        panel.assertLayout(PaneLayout.COLUMN)
        table.press()
        assertEquals(AlloyInstanceViewer.TABLE, panel.selectedTab)
        assertTrue(panel.tablePanes().isColumn)
        panel.assertLayout(PaneLayout.COLUMN)
    }

    fun testTheLayoutStaysForTheNextResult() {
        val panel = panel()
        open(LASSO)
        panel.tabActions()[0].press()
        open(STATIC)
        open(LASSO)
        panel.assertLayout(PaneLayout.COLUMN)
        // A new viewer, as in a new IDE session, starts with it too
        panel().assertLayout(PaneLayout.COLUMN)
    }

    fun testPressingTheSelectedTextButtonKeepsTheLayout() {
        val panel = panel()
        open(LASSO)
        val text = panel.tabActions()[1]
        text.press()
        val icon = text.icon()
        text.setSelected(TestActionEvent.createTestEvent(text), false)
        assertEquals(AlloyInstanceViewer.TEXT, panel.selectedTab)
        assertTrue(text.selected())
        panel.assertLayout(PaneLayout.ROW)
        assertEquals(icon, text.icon())
    }

    fun testTheChosenTabIsSaved() {
        viewer.selectedTab = AlloyInstanceViewer.TEXT
        val saved = XmlSerializer.deserialize(XmlSerializer.serialize(viewer.state), AlloyInstanceViewerState::class.java)
        assertEquals(AlloyInstanceViewer.TEXT, saved.selectedTab)
    }

    fun testThePaneLayoutIsSaved() {
        assertEquals(PaneLayout.ROW, viewer.paneLayout)
        viewer.paneLayout = PaneLayout.COLUMN
        val saved = XmlSerializer.deserialize(XmlSerializer.serialize(viewer.state), AlloyInstanceViewerState::class.java)
        assertEquals(PaneLayout.COLUMN.name, saved.paneLayout)
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
        panel.selectTab(AlloyInstanceViewer.TEXT)
        panel.selectTab(AlloyInstanceViewer.TABLE)
        viewer.session(myFixture.file.virtualFile)!!.model.clearProjections()
        panel.rebuild()
        assertEquals(before, snapshot())
    }
}
