package com.serranofp.alloy.viewer

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.UIUtil
import com.serranofp.alloy.run.AlloyCommandRef
import com.serranofp.alloy.run.AlloyCommandResults
import com.serranofp.alloy.run.CommandLink
import com.serranofp.alloy.run.CommandResult
import com.serranofp.alloy.run.CommandState
import com.serranofp.alloy.settings.AlloySettings

class AlloyStateStepperTest : BasePlatformTestCase() {
    fun testThreeStatesLoopingBackToStateOne() {
        val model = AlloySolutionModel(solve(LASSO))
        assertTrue(model.hasStates)
        assertEquals(3, model.traceLength)
        assertEquals(1, model.loopState)
        assertEquals("State 0 of 3", model.stepperText)
        assertEquals("Loops back to state 1", model.loopText)

        model.next()
        assertEquals("State 1 of 3", model.stepperText)
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
        // The stepper still names the first pane
        assertEquals("State 2 of 3", model.stepperText)
    }

    fun testStaticResultHasNoStepper() {
        val model = AlloySolutionModel(solve(STATIC))
        assertFalse(model.isTemporal)
        assertFalse(model.hasStates)
        model.next()
        assertEquals(0, model.state)
        assertNull(model.nextVizState)
        assertNull(model.nextCaption)
    }

    private fun stepperShownFor(model: String): Boolean {
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
        val stepper = UIUtil.findComponentsOfType(panel, JBLabel::class.java).single { it.text.matches(Regex("State \\d+ of \\d+")) }
        return stepper.isVisible
    }

    fun testPanelShowsTheStepperOnlyForTraces() {
        assertTrue(stepperShownFor(LASSO))
        assertFalse(stepperShownFor(STATIC))
    }
}
