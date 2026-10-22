package com.serranofp.alloy.run

import com.intellij.codeInsight.hints.declarative.InlayActionHandler
import com.intellij.codeInsight.hints.declarative.StringInlayActionPayload
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.serranofp.alloy.settings.AlloySettings
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

class AlloyCommandResultClickHandlerTest : BasePlatformTestCase() {
    private val service get() = AlloyRunService.getInstance(project)
    private val results get() = AlloyCommandResults.getInstance(project)
    private val show = AlloyCommandRef("show", 0)
    private val noCycles = AlloyCommandRef("noCycles", 1)

    private val model = "sig Node { succ: lone Node }\nfact { no succ & iden }\npred show {}\nrun show for 3\ncheck noCycles { no iden & succ } for 3\n"

    override fun setUp() {
        super.setUp()
        results.clear()
        myFixture.configureByText("model.als", model)
    }

    override fun tearDown() {
        try {
            service.activeExecution?.terminate()
        } finally {
            super.tearDown()
        }
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

    fun testInstanceClickLeavesTheResult() {
        record(show, CommandState.InstanceFound, "Instance found · 4 ms", instanceLink())
        val before = results.get(file, show)
        click(show)
        assertSame(before, results.get(file, show))
    }

    fun testResultWithoutASolutionDoesNothing() {
        record(noCycles, CommandState.NoCounterexample, "No counterexample found · 3 ms", null)
        val before = results.get(file, noCycles)
        click(noCycles)
        assertSame(before, results.get(file, noCycles))
    }

    /** Starts [model]'s `run show` as the background task does, on [indicator], and waits for [ready]. */
    private fun startTask(model: String, indicator: EmptyProgressIndicator, ready: (AlloyCommandExecution) -> Boolean): Pair<AlloyCommandExecution, Future<*>> {
        myFixture.configureByText("model.als", model)
        val execution = newExecution(project, myFixture.file.virtualFile, show)
        service.startRun(execution)
        val task = AlloyCommandTask(project, execution, AlloyCommandLauncher(project, options = ::slowOptions), testRootDisposable)
        val running = ApplicationManager.getApplication().executeOnPooledThread { task.run(indicator) }
        waitUntil("the analysis did not get going") { ready(execution) }
        return execution to running
    }

    private val showResult get() = results.get(myFixture.file.virtualFile, show)!!

    fun testRunningClickStopsTheAnalysis() {
        val (execution, running) = startTask(PIGEONHOLE, EmptyProgressIndicator()) { it.log.progress == "Solving..." }
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

        // Clicking again does not stop it twice
        click(show)
        running.get(60, TimeUnit.SECONDS)
        assertEquals(CommandState.Stopped, showResult.state)
        assertEquals("Stopped before completion", showResult.summary)
    }

    fun testCancellingTheProgressIndicatorStopsTheAnalysis() {
        val indicator = EmptyProgressIndicator()
        val (execution, running) = startTask(PIGEONHOLE, indicator) { it.log.progress == "Solving..." }
        indicator.cancel()
        running.get(10, TimeUnit.SECONDS)
        assertTrue(execution.isFinished())
        assertEquals("Stopped before completion", showResult.summary)
        assertEquals("   Solving...", showResult.details.log.last().text)
    }

    private companion object {
        /** Several seconds of translation, without any analyzer callback. */
        const val HEAVY_TRANSLATION =
            "sig A { r: A -> A }\npred show { all a, b, c, d: A | a->b->c in r implies (b->c->d in r or c->d->a in r) }\nrun show for 32"
    }
}
