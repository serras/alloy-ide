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

    fun testStaticResultHasNoStepper() {
        val model = AlloySolutionModel(solve(STATIC))
        assertFalse(model.isTemporal)
        assertFalse(model.hasStates)
        model.next()
        assertEquals(0, model.state)
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
        val stepper = UIUtil.findComponentsOfType(panel, JBLabel::class.java).single { it.text.startsWith("State ") }
        return stepper.isVisible
    }

    fun testPanelShowsTheStepperOnlyForTraces() {
        assertTrue(stepperShownFor(LASSO))
        assertFalse(stepperShownFor(STATIC))
    }
}
