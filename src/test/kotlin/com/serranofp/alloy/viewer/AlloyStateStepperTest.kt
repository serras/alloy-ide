package com.serranofp.alloy.viewer

import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.ActionToolbarImpl
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.DropDownLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.UIUtil
import com.serranofp.alloy.run.AlloyCommandRef
import com.serranofp.alloy.run.AlloyCommandResults
import com.serranofp.alloy.run.CommandLink
import com.serranofp.alloy.run.CommandResult
import com.serranofp.alloy.run.CommandState
import com.serranofp.alloy.run.waitUntil
import com.serranofp.alloy.settings.AlloySettings
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.KeyStroke

class AlloyStateStepperTest : BasePlatformTestCase() {
    fun testThreeStatesLoopingBackToStateOne() {
        val model = AlloySolutionModel(solve(LASSO))
        assertTrue(model.hasStates)
        assertEquals(3, model.traceLength)
        assertEquals(1, model.loopState)
        assertEquals(0, model.state)

        model.next()
        assertEquals(1, model.state)
        assertTrue(model.text.contains("this/Cur={V2$0}"))
        model.next()
        assertEquals(2, model.state)
        assertTrue(model.text.contains("this/Cur={V3$0}"))

        // Past the last state, the loop
        model.next()
        assertEquals(1, model.state)
        assertTrue(model.text.contains("this/Cur={V2$0}"))

        model.previous()
        assertEquals(0, model.state)
        model.previous()
        assertEquals(0, model.state)
    }

    fun testSteppingKeepsTheTheme() {
        val model = AlloySolutionModel(solve(LASSO))
        val theme = model.vizState
        model.next()
        assertSame(theme, model.vizState)
        assertSame(model.instance(1), model.vizState.originalInstance)
    }

    fun testSuccessorsOfTheLasso() {
        val model = AlloySolutionModel(solve(LASSO))
        assertEquals(listOf(1, 2, 1), (0 until 3).map { model.successor(it) })
    }

    fun testTransitionsOfTheLasso() {
        val model = AlloySolutionModel(solve(LASSO))
        assertEquals(listOf(Transition(0, 1, false), Transition(1, 2, false), Transition(2, 1, true)), model.transitions)
    }

    fun testTransitionsOfASelfLoop() {
        val model = AlloySolutionModel(solve(SELF_LOOP))
        assertEquals(2, model.traceLength)
        assertEquals(listOf(Transition(0, 1, false), Transition(1, 1, true)), model.transitions)
    }

    fun testStaticResultHasNoTransitions() {
        assertEquals(emptyList<Transition>(), AlloySolutionModel(solve(STATIC)).transitions)
    }

    fun testBothStatesFollowTheStepper() {
        val model = AlloySolutionModel(solve(LASSO))
        val first = model.vizState
        val second = model.nextVizState!!
        assertSame(model.instance(0), first.originalInstance)
        assertSame(model.instance(1), second.originalInstance)

        // One state at a time, so that each transition is shown once
        model.next()
        assertEquals(1, model.state)
        assertSame(model.instance(1), first.originalInstance)
        assertSame(model.instance(2), second.originalInstance)
        model.next()
        assertSame(model.instance(2), first.originalInstance)
        assertSame(model.instance(1), second.originalInstance)
        model.previous()
        assertEquals(1, model.state)
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
        model.next()
        assertEquals(0, model.state)
        assertNull(model.nextVizState)
        assertNull(model.nextCaption)
    }

    private fun panelFor(model: String): AlloyInstancePanel {
        AlloyCommandResults.getInstance(project).clear()
        AlloyInstanceViewer.getInstance(project).clear()
        myFixture.configureByText("m.als", "pred show {}\nrun show for 3\n")
        val panel = AlloyInstancePanel(project, testRootDisposable)
        val results = AlloyCommandResults.getInstance(project)
        val result = CommandResult(
            CommandState.InstanceFound, "found", link = CommandLink.Solution(solve(model)),
            documentStamp = results.stamp(myFixture.editor.document), settingsGeneration = AlloySettings.getInstance().generation,
        )
        AlloyInstanceViewer.getInstance(project).open(myFixture.file.virtualFile, AlloyCommandRef("show", 0), result)
        UIUtil.dispatchAllInvocationEvents()
        return panel
    }

    private fun AlloyInstancePanel.strip() = UIUtil.findComponentOfType(this, AlloyTraceStrip::class.java)!!

    fun testThePanelHasNoStepper() {
        val panel = panelFor(LASSO)
        assertEmpty(UIUtil.findComponentsOfType(panel, JBLabel::class.java).filter { it.text.matches(Regex("State \\d+ of \\d+|Loops back.*")) })
        val group = UIUtil.findComponentOfType(panel, ActionToolbarImpl::class.java)!!.actionGroup as DefaultActionGroup
        val actions = group.childActionsOrStubs.filter { it !is Separator }.map { it.templateText }
        assertEquals(SolutionKind.entries.map { it.label } + AlloyInstancePanel.EVALUATOR, actions)
    }

    fun testPanelShowsTheStripOnlyForTraces() {
        val trace = panelFor(LASSO).strip()
        assertTrue(trace.scrollPane.isVisible)
        assertEquals(setOf(0, 1), trace.geometry!!.highlighted)
        assertFalse(panelFor(STATIC).strip().scrollPane.isVisible)
    }

    fun testTheStripIsBesideTheToolbarWhenItFits() {
        val strip = panelFor(LASSO).strip().scrollPane
        val line = strip.parent
        val controls = line.components.single { it !== strip }
        val projection = UIUtil.findComponentOfType(controls as JComponent, DropDownLink::class.java)!!
        val toolbar = UIUtil.findComponentOfType(controls, ActionToolbarImpl::class.java)!!

        line.setSize(2000, line.preferredSize.height)
        line.doLayout()
        controls.doLayout()
        // The New actions, then the projection chooser, then the strip
        assertTrue(toolbar.x < projection.x)
        assertTrue(strip.x >= controls.x + controls.width)
        assertTrue(strip.y < controls.y + controls.height && controls.y < strip.y + strip.height)
        assertEquals(strip.preferredSize.width, strip.width)

        // Too narrow for both: the strip goes below, as wide as the line
        val narrow = controls.preferredSize.width + strip.preferredSize.width / 2
        line.setSize(narrow, 0)
        line.setSize(narrow, line.preferredSize.height)
        line.doLayout()
        assertEquals(0, strip.x)
        assertTrue(strip.y >= controls.y + controls.height)
        assertEquals(narrow, strip.width)
        assertTrue(line.preferredSize.height >= controls.height + strip.height)
    }

    fun testClickingAStateInTheStrip() {
        val panel = panelFor(LASSO)
        val strip = panel.strip()
        val circle = strip.geometry!!.circles[2]
        strip.dispatchEvent(MouseEvent(strip, MouseEvent.MOUSE_PRESSED, 0, 0, circle.centerX.toInt(), circle.centerY.toInt(), 1, false, MouseEvent.BUTTON1))
        // The state is prepared off the EDT, then shown
        waitUntil("state 2 was not shown") { AlloyInstanceViewer.getInstance(project).session!!.model.state == 2 }
        for (panes in UIUtil.findComponentsOfType(panel, AlloyStatePanes::class.java)) assertEquals(listOf("State 2", "State 1 (loop)"), panes.captions)
        assertEquals(setOf(2, 1), strip.geometry!!.highlighted)
    }

    /** Runs the strip's action for [key], as pressing it in the focused strip does. */
    private fun AlloyTraceStrip.press(key: Int) {
        val stroke = KeyStroke.getKeyStroke(key, 0)
        val action = ActionUtil.getActions(this).single { action -> action.shortcutSet.shortcuts.any { (it as? KeyboardShortcut)?.firstKeyStroke == stroke } }
        action.actionPerformed(TestActionEvent.createTestEvent(action))
    }

    fun testTheKeysStepThroughTheTrace() {
        val panel = panelFor(LASSO)
        val strip = panel.strip()
        val model = AlloyInstanceViewer.getInstance(project).session!!.model
        // Right goes to the successor, which after the last state is the loop target
        for (state in listOf(1, 2, 1)) {
            strip.press(KeyEvent.VK_RIGHT)
            waitUntil("state $state was not shown") { model.state == state && strip.geometry!!.selected == state }
        }
        strip.press(KeyEvent.VK_LEFT)
        waitUntil("state 0 was not shown") { model.state == 0 }
        strip.press(KeyEvent.VK_END)
        waitUntil("state 2 was not shown") { model.state == 2 }
        for (panes in UIUtil.findComponentsOfType(panel, AlloyStatePanes::class.java)) assertEquals(listOf("State 2", "State 1 (loop)"), panes.captions)
    }

    /** Each state is prepared off the EDT: the latest one selected is shown, whichever is prepared last. */
    fun testTheLatestSelectionWins() {
        val panel = panelFor(LASSO)
        val strip = panel.strip()
        val model = AlloyInstanceViewer.getInstance(project).session!!.model
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
            val circle = strip.geometry!!.circles[state]
            strip.dispatchEvent(MouseEvent(strip, MouseEvent.MOUSE_PRESSED, 0, 0, circle.centerX.toInt(), circle.centerY.toInt(), 1, false, MouseEvent.BUTTON1))
            waitUntil("state $state was not shown") { strip.geometry!!.selected == state }
            assertEquals(pair, strip.geometry!!.highlighted)
            for (panes in UIUtil.findComponentsOfType(panel, AlloyStatePanes::class.java)) assertEquals(listOf("State $state", "State ${state + 1}"), panes.captions)
        }
    }
}
