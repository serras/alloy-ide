package com.serranofp.alloy.viewer

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.impl.ActionToolbarImpl
import com.intellij.openapi.progress.Task
import com.intellij.testFramework.TestActionEvent
import com.intellij.ui.components.DropDownLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.UIUtil
import com.serranofp.alloy.AlloyIcons
import com.serranofp.alloy.run.waitUntil
import java.awt.event.KeyEvent
import javax.swing.JComponent

/** Selecting the states of a trace: in the model, and in the tool window through the trace path strip. */
class AlloyTraceNavigationTest : AlloyViewerTestCase() {
    /** The state the strip's Right key selects: the successor, which after the last state is the loop target. */
    private fun AlloySolutionModel.right() = goTo(successor())

    fun testThreeStatesLoopingBackToStateOne() {
        val model = AlloySolutionModel(solve(LASSO))
        assertTrue(model.hasStates)
        assertEquals(3, model.traceLength)
        assertEquals(1, model.loopState)
        assertEquals(0, model.state)

        model.right()
        assertEquals(1, model.state)
        assertTrue(model.text.contains("this/Cur={V2$0}"))
        model.right()
        assertEquals(2, model.state)
        assertTrue(model.text.contains("this/Cur={V3$0}"))

        // Past the last state, the loop
        model.right()
        assertEquals(1, model.state)
        assertTrue(model.text.contains("this/Cur={V2$0}"))
    }

    fun testSteppingKeepsTheTheme() {
        val model = AlloySolutionModel(solve(LASSO))
        val theme = model.vizState
        model.goTo(1)
        assertSame(theme, model.vizState)
        assertSame(model.instance(1), model.vizState.originalInstance)
    }

    fun testSuccessorsOfTheLasso() {
        val model = AlloySolutionModel(solve(LASSO))
        assertEquals(listOf(1, 2, 1), (0 until 3).map { model.successor(it) })
    }

    fun testSuccessorsOfASelfLoop() {
        val model = AlloySolutionModel(solve(SELF_LOOP))
        assertEquals(listOf(1, 1), (0 until 2).map { model.successor(it) })
    }

    fun testTheStripShowsTheTransitionsOfTheLasso() {
        val geometry = panelFor(LASSO).strip().geometry!!
        assertEquals(listOf(Transition(0, 1, false), Transition(1, 2, false), Transition(2, 1, true)), geometry.transitions)
    }

    fun testTheStripShowsTheTransitionsOfASelfLoop() {
        val geometry = panelFor(SELF_LOOP).strip().geometry!!
        assertEquals(2, geometry.traceLength)
        assertEquals(listOf(Transition(0, 1, false), Transition(1, 1, true)), geometry.transitions)
    }

    /** The strip highlights what the model says follows each state: the two do not decide it apart. */
    fun testTheStripsSuccessorIsTheModels() {
        for (fixture in listOf(LASSO, SELF_LOOP, "var sig X {}\nfact { no X and after (one X and after no X) and always (no X implies after one X) }\nrun {} for 1 but 2 steps")) {
            val panel = panelFor(fixture)
            val model = viewer.session(myFixture.file.virtualFile)!!.model
            val strip = panel.strip()
            for (state in 0 until model.traceLength) {
                strip.pressState(state)
                waitUntil("state $state was not shown") { strip.geometry!!.selected == state }
                assertEquals(fixture, model.successor(state), strip.geometry!!.successor)
                assertEquals(setOf(state, model.successor(state)), strip.geometry!!.highlighted)
            }
        }
    }

    fun testBothStatesFollowTheSelection() {
        val model = AlloySolutionModel(solve(LASSO))
        val first = model.vizState
        val second = model.nextVizState!!
        assertSame(model.instance(0), first.originalInstance)
        assertSame(model.instance(1), second.originalInstance)

        // One state at a time, so that each transition is shown once
        model.goTo(1)
        assertSame(model.instance(1), first.originalInstance)
        assertSame(model.instance(2), second.originalInstance)
        model.goTo(2)
        assertSame(model.instance(2), first.originalInstance)
        assertSame(model.instance(1), second.originalInstance)
        model.goTo(1)
        assertSame(model.instance(1), first.originalInstance)
        assertSame(model.instance(2), second.originalInstance)

        // The same two theme objects, with the same theme
        assertSame(first, model.vizState)
        assertSame(second, model.nextVizState)
        assertEquals(first.nodePalette, second.nodePalette)
        assertEquals(first.edgePalette, second.edgePalette)
        assertEquals(first.fontSize, second.fontSize)
        assertEquals(first.projectedTypes, second.projectedTypes)
        for (atom in first.originalInstance.allAtoms) assertEquals(atom.toString(), first.nodeColor(atom, first.originalInstance), second.nodeColor(atom, second.originalInstance))
    }

    fun testCaptions() {
        val model = AlloySolutionModel(solve(LASSO))
        assertEquals("State 0", model.caption)
        assertEquals("State 1", model.nextCaption)
        model.goTo(2)
        assertEquals("State 2", model.caption)
        assertEquals("State 1 (loop)", model.nextCaption)
    }

    fun testStaticResultHasASingleState() {
        val model = AlloySolutionModel(solve(STATIC))
        assertFalse(model.isTemporal)
        assertFalse(model.hasStates)
        assertEquals(0, model.successor())
        assertNull(model.nextVizState)
        assertNull(model.nextCaption)
    }

    private fun AlloyInstancePanel.strip() = UIUtil.findComponentOfType(this, AlloyTraceStrip::class.java)!!

    private fun AlloyInstancePanel.captions() = UIUtil.findComponentsOfType(this, AlloyStatePanes::class.java).map { it.shownCaptions }

    private fun AlloyInstancePanel.actions(): List<AnAction> {
        val group = UIUtil.findComponentOfType(this, ActionToolbarImpl::class.java)!!.actionGroup as DefaultActionGroup
        return group.childActionsOrStubs.filter { it !is Separator }
    }

    fun testThePanelHasNoStepper() {
        val panel = panelFor(LASSO)
        assertEmpty(UIUtil.findComponentsOfType(panel, JBLabel::class.java).filter { it.text.matches(Regex("State \\d+ of \\d+|Loops back.*")) })
        assertEquals(SolutionKind.entries.map { it.label } + AlloyInstancePanel.EVALUATOR, panel.actions().map { it.templateText })
    }

    /** The presentation of each New action, as the toolbar updates it. */
    private fun AlloyInstancePanel.newActions(): Map<SolutionKind, Presentation> =
        SolutionKind.entries.zip(actions()).associate { (kind, action) ->
            assertEquals(kind.label, action.templateText)
            kind to TestActionEvent.createTestEvent(action).also(action::update).presentation
        }

    fun testTheNewActionsFollowTheSession() {
        myFixture.configureByText("m.als", SHOW_FILE)
        val panel = panel()
        // Without a result, none
        assertTrue(panel.newActions().values.none { it.isVisible })
        open(TRACES)
        for ((kind, presentation) in panel.newActions()) {
            assertTrue(presentation.isVisible && presentation.isEnabled)
            assertEquals(kind.description, presentation.description)
        }
        // While a new solution is searched for, none can be asked for
        var task: Task.Backgroundable? = null
        viewer.schedule = { task = it }
        assertTrue(viewer.requestSolution(myFixture.file.virtualFile, SolutionKind.Config))
        assertTrue(panel.newActions().values.all { it.isVisible && !it.isEnabled })
        task!!.onFinished()
        // An outdated result says to run the command again
        myFixture.type("x")
        for (presentation in panel.newActions().values) {
            assertTrue(presentation.isVisible && !presentation.isEnabled)
            assertEquals(AlloyInstanceViewer.RUN_AGAIN, presentation.description)
        }
    }

    fun testOnlyNewConfigForAStaticResult() {
        val actions = panelFor(STATIC).newActions()
        // Without a trace path strip, the kinds of a trace are not shown at all
        assertEquals(listOf(SolutionKind.Config), actions.filterValues { it.isVisible }.keys.toList())
        assertTrue(actions.getValue(SolutionKind.Config).isEnabled)
    }

    fun testATraceShowsEveryNewAction() {
        val actions = panelFor(LASSO).newActions()
        assertTrue(actions.values.all { it.isVisible && it.isEnabled })
    }

    fun testTheNewActionsHaveTheirOwnIcons() {
        val icons = mapOf(
            SolutionKind.Config to AlloyIcons.NEW_CONFIG, SolutionKind.Trace to AlloyIcons.NEW_TRACE,
            SolutionKind.Init to AlloyIcons.NEW_INIT, SolutionKind.Fork to AlloyIcons.NEW_FORK,
        )
        for ((kind, presentation) in panelFor(LASSO).newActions()) {
            assertSame(icons.getValue(kind), kind.icon)
            assertSame(kind.icon, presentation.icon)
        }
    }

    fun testPanelShowsTheStripOnlyForTraces() {
        val trace = panelFor(LASSO).strip()
        assertTrue(trace.scrollPane.isVisible)
        assertEquals(setOf(0, 1), trace.geometry!!.highlighted)
        assertFalse(panelFor(STATIC).strip().scrollPane.isVisible)
    }

    /** The toolbar line of [panel], laid out at a width, after its toolbars have their actions. */
    private inner class ToolbarLine(panel: AlloyInstancePanel) {
        val strip = panel.strip().scrollPane
        val line = strip.parent
        private val toolbars = UIUtil.findComponentsOfType(panel, ActionToolbarImpl::class.java)
        val tabs = toolbars.single { it.place == "AlloyInstanceTabs" }
        val controls = line.components.single { it !== strip && it !== tabs }

        init {
            for (toolbar in toolbars) toolbar.updateActionsAsync()
            waitUntil("the toolbars did not update") { toolbars.all { it.componentCount > 0 } }
        }

        fun layOut(width: Int) {
            line.setSize(width, 0)
            line.setSize(width, line.preferredSize.height)
            line.doLayout()
            (controls as JComponent).doLayout()
            // A layout that moves the strip asks for another one, for the line's new height
            UIUtil.dispatchAllInvocationEvents()
        }

        fun assertTabsAtTheRight(width: Int) {
            assertTrue(tabs.width > 0)
            assertEquals(width, tabs.x + tabs.width)
            assertTrue(tabs.y < controls.y + controls.height && controls.y < tabs.y + tabs.height)
        }

        fun assertCentered(width: Int) {
            assertTrue(strip.y < controls.y + controls.height && controls.y < strip.y + strip.height)
            assertEquals(strip.preferredSize.width, strip.width)
            assertEquals((width - strip.width) / 2, strip.x)
            assertTrue(strip.x >= controls.x + controls.width && strip.x + strip.width <= tabs.x)
        }
    }

    fun testTheStripIsCenteredOnTheToolbarLineWhenItFits() {
        val line = ToolbarLine(panelFor(LASSO))
        val projection = UIUtil.findComponentOfType(line.controls as JComponent, DropDownLink::class.java)!!
        val toolbar = UIUtil.findComponentOfType(line.controls, ActionToolbarImpl::class.java)!!

        line.layOut(2000)
        // The New actions, then the projection chooser; the strip in the middle; the tab buttons at the right
        assertTrue(toolbar.x < projection.x)
        line.assertCentered(2000)
        line.assertTabsAtTheRight(2000)

        // Too narrow for the strip between the groups: it goes below, as wide as the line
        val narrow = line.controls.preferredSize.width + line.tabs.preferredSize.width + line.strip.preferredSize.width / 2
        line.layOut(narrow)
        assertEquals(0, line.strip.x)
        assertTrue(line.strip.y >= line.controls.y + line.controls.height)
        assertEquals(narrow, line.strip.width)
        assertTrue(line.line.preferredSize.height >= line.controls.height + line.strip.height)
        line.assertTabsAtTheRight(narrow)

        // Wide again: back on the line
        line.layOut(2000)
        line.assertCentered(2000)
    }

    fun testAStaticResultHasOnlyTheTwoGroups() {
        val line = ToolbarLine(panelFor(STATIC))
        line.layOut(2000)
        assertFalse(line.strip.isVisible)
        line.assertTabsAtTheRight(2000)
        assertEquals(maxOf(line.controls.preferredSize.height, line.tabs.preferredSize.height), line.line.preferredSize.height)
    }

    fun testClickingAStateInTheStrip() {
        val panel = panelFor(LASSO)
        val strip = panel.strip()
        strip.pressState(2)
        // The state is prepared off the EDT, then shown
        waitUntil("state 2 was not shown") { viewer.session(myFixture.file.virtualFile)!!.model.state == 2 }
        assertEquals(List(2) { listOf("State 2", "State 1 (loop)") }, panel.captions())
        assertEquals(setOf(2, 1), strip.geometry!!.highlighted)
    }

    fun testTheKeysStepThroughTheTrace() {
        val panel = panelFor(LASSO)
        val strip = panel.strip()
        val model = viewer.session(myFixture.file.virtualFile)!!.model
        // Right goes to the successor, which after the last state is the loop target
        for (state in listOf(1, 2, 1)) {
            strip.press(KeyEvent.VK_RIGHT)
            waitUntil("state $state was not shown") { model.state == state && strip.geometry!!.selected == state }
        }
        strip.press(KeyEvent.VK_LEFT)
        waitUntil("state 0 was not shown") { model.state == 0 }
        strip.press(KeyEvent.VK_END)
        waitUntil("state 2 was not shown") { model.state == 2 }
        assertEquals(List(2) { listOf("State 2", "State 1 (loop)") }, panel.captions())
    }

    /** Each state is prepared off the EDT: the latest one selected is shown, whichever is prepared last. */
    fun testTheLatestSelectionWins() {
        val panel = panelFor(LASSO)
        val strip = panel.strip()
        val model = viewer.session(myFixture.file.virtualFile)!!.model
        strip.press(KeyEvent.VK_END)
        strip.press(KeyEvent.VK_RIGHT)
        waitUntil("the states were not prepared") { model.isPrepared(2) && model.isPrepared(1) }
        Thread.sleep(50)
        UIUtil.dispatchAllInvocationEvents()
        // The strip still showed state 0 when Right was pressed
        assertEquals(1, model.state)
    }

    fun testClickingAgainMovesTheHighlighting() {
        val panel = panelFor(LASSO)
        val strip = panel.strip()
        for ((state, pair) in listOf(1 to setOf(1, 2), 0 to setOf(0, 1))) {
            strip.pressState(state)
            waitUntil("state $state was not shown") { strip.geometry!!.selected == state }
            assertEquals(pair, strip.geometry!!.highlighted)
            assertEquals(List(2) { listOf("State $state", "State ${state + 1}") }, panel.captions())
        }
    }
}
