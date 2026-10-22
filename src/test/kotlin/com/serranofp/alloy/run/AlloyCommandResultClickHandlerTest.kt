package com.serranofp.alloy.run

import com.intellij.codeInsight.hints.declarative.InlayActionHandler
import com.intellij.codeInsight.hints.declarative.StringInlayActionPayload
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.serranofp.alloy.settings.AlloySettings
import com.serranofp.alloy.viewer.AlloyInstanceViewer
import java.util.concurrent.TimeUnit

class AlloyCommandResultClickHandlerTest : AlloyRunTestCase() {
    private val show = AlloyCommandRef("show", 0)
    private val noCycles = AlloyCommandRef("noCycles", 1)

    private val model = "sig Node { succ: lone Node }\nfact { no succ & iden }\npred show {}\nrun show for 3\ncheck noCycles { no iden & succ } for 3\n"

    override fun setUp() {
        super.setUp()
        myFixture.configureByText("model.als", model)
    }

    private val file get() = myFixture.file.virtualFile

    private fun record(command: AlloyCommandRef, state: CommandState, summary: String, link: CommandLink?) =
        results.record(
            file,
            command,
            CommandResult(state, summary, link = link, documentStamp = results.stamp(myFixture.editor.document), settingsGeneration = AlloySettings.getInstance().generation),
        )

    private fun click(command: AlloyCommandRef) =
        AlloyCommandResultClickHandler().click(myFixture.editor, StringInlayActionPayload(AlloyCommandResultHintsProvider.Codec.encode(command)))

    fun testHandlerIsRegistered() {
        assertInstanceOf(
            InlayActionHandler.getActionHandler(AlloyCommandResultClickHandler.HANDLER_ID),
            AlloyCommandResultClickHandler::class.java,
        )
    }

    fun testInstanceClickOpensItAndLeavesTheResult() {
        val viewer = AlloyInstanceViewer.getInstance(project)
        viewer.clear()
        try {
            record(show, CommandState.InstanceFound, "Instance found · 4 ms", instanceLink())
            val before = results.get(file, show)
            click(show)
            assertSame(before, results.get(file, show))
            assertEquals(file, viewer.session!!.origin.file)
            assertEquals(show, viewer.session!!.origin.command)
        } finally {
            viewer.clear()
        }
    }

    fun testResultWithoutASolutionDoesNothing() {
        record(noCycles, CommandState.NoCounterexample, "No counterexample found · 3 ms", null)
        val before = results.get(file, noCycles)
        click(noCycles)
        assertSame(before, results.get(file, noCycles))
    }

    /** A running result of another command, such as one of a suite, cannot stop this execution. */
    fun testClickOnAnotherRunningCommandLeavesTheExecution() {
        val execution = newExecution(project, file, show)
        assertTrue(service.startRun(execution))
        record(noCycles, CommandState.Running, "Running…", null)
        click(noCycles)
        assertFalse(execution.isFinished())
        assertSame(execution, service.activeExecution)
    }

    private val showResult get() = results.get(myFixture.file.virtualFile, show)!!

    fun testRunningClickStopsTheAnalysis() {
        val (execution, running) = startTask(PIGEONHOLE, EmptyProgressIndicator())
        click(show)
        running.get(10, TimeUnit.SECONDS)
        assertTrue(execution.isFinished())
        assertEquals(CommandState.Stopped, showResult.state)
        assertEquals("Stopped before completion", showResult.summary)
        assertNull(showResult.details.failure)
        // The output received before the stop
        assertEquals("   Solving...", showResult.details.log.last().text)
        assertTrue(showResult.details.log.any { it.text.startsWith("Executing \"Run show") })
    }

    fun testClickDuringTranslationIsStoppingFirst() {
        val (execution, running) = startTask(HEAVY_TRANSLATION, EmptyProgressIndicator()) { it.log.progress == "Generating CNF..." }
        click(show)
        // The translation reaches no checkpoint for seconds
        assertEquals(CommandState.Running, showResult.state)
        assertEquals("Stopping…", showResult.summary)
        assertFalse(execution.isFinished())

        // Clicking again does not end it before the analyzer stops
        click(show)
        assertEquals("Stopping…", showResult.summary)
        assertFalse(execution.isFinished())
        running.get(60, TimeUnit.SECONDS)
        assertEquals(CommandState.Stopped, showResult.state)
        assertEquals("Stopped before completion", showResult.summary)
    }

    fun testCancellingTheProgressIndicatorStopsTheAnalysis() {
        val indicator = EmptyProgressIndicator()
        val (execution, running) = startTask(PIGEONHOLE, indicator)
        indicator.cancel()
        running.get(10, TimeUnit.SECONDS)
        assertTrue(execution.isFinished())
        assertEquals("Stopped before completion", showResult.summary)
        assertEquals("   Solving...", showResult.details.log.last().text)
    }
}
