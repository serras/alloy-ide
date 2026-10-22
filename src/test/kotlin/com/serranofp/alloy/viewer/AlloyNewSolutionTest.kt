package com.serranofp.alloy.viewer

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.serranofp.alloy.run.AlloyCommandExecution
import com.serranofp.alloy.run.AlloyRunService
import com.serranofp.alloy.run.newExecution
import com.serranofp.alloy.run.waitUntil
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class AlloyNewSolutionTest : AlloyViewerTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.configureByText("m.als", SHOW_FILE)
    }

    private fun request(kind: SolutionKind): ViewerSession {
        assertTrue(viewer.requestSolution(kind))
        waitUntil("the new solution did not arrive") { !viewer.enumerating }
        return viewer.session!!
    }

    private fun enabled() = SolutionKind.entries.filter(viewer::isEnabled)

    /** Whether the project's analysis is free: a command can run. */
    private fun analysisIsFree(): Boolean {
        val run = AlloyRunService.getInstance(project)
        val started = run.startRun(newExecution(project, myFixture.file.virtualFile, SHOW))
        run.activeExecution?.terminate()
        return started
    }

    fun testStaticModelOnlyHasNewConfig() {
        open(STATIC)
        assertEquals(listOf(SolutionKind.Config), enabled())
        assertFalse(viewer.requestSolution(SolutionKind.Trace))
    }

    fun testTemporalModelHasEveryKind() {
        open(TRACES)
        assertEquals(SolutionKind.entries, enabled())
    }

    fun testNewConfig() {
        open(STATIC)
        val before = viewer.session!!
        val after = request(SolutionKind.Config)
        assertNotSame(before, after)
        assertNotSame(before.model.solution, after.model.solution)
        assertTrue(after.model.solution.satisfiable())
        assertFalse(before.model.text == after.model.text)
        assertEquals(before.origin, after.origin)
        assertNull(viewer.status)
    }

    /** Each kind asks `A4Solution.fork` for its code: a temporal model's from its selected state, state 1 here. */
    fun testEachKindForksWithItsCode() {
        val recorded = mutableListOf<Int>()
        fun codeOf(model: String, kind: SolutionKind, state: Int = 0): Int {
            open(model).model.goTo(state)
            recorded.clear()
            viewer.fork = { solution, p -> synchronized(recorded) { recorded += p }; solution.fork(p) }
            request(kind)
            return synchronized(recorded) { recorded.single() }
        }
        assertEquals(-3, codeOf(STATIC, SolutionKind.Config))
        assertEquals(-1, codeOf(TRACES, SolutionKind.Config, state = 1))
        assertEquals(-2, codeOf(TRACES, SolutionKind.Trace, state = 1))
        assertEquals(0, codeOf(TRACES, SolutionKind.Init, state = 1))
        assertEquals(2, codeOf(TRACES, SolutionKind.Fork, state = 1))
    }

    fun testOpenedSolutionHasTheModule() {
        open(STATIC)
        assertNotNull(viewer.session!!.model.module)
    }

    fun testNewSolutionKeepsTheModule() {
        open(STATIC)
        val before = viewer.session!!.model
        val after = request(SolutionKind.Config).model
        assertNotSame(before, after)
        assertSame(before.module, after.module)
    }

    fun testNewSolutionKeepsTheThemeAndProjections() {
        open("sig Item {}\nsig State { holds: set Item }\nrun { some holds } for 3")
        val before = viewer.session!!.model
        before.setProjected(before.projectableTypes.single { it.name == "State" }, true)
        before.vizState.setFontSize(17)
        val after = request(SolutionKind.Config).model
        assertNotSame(before.vizState, after.vizState)
        assertEquals(listOf("State"), after.projections.map { it.name })
        assertEquals(17, after.vizState.fontSize)
        assertSame(after.instance(0), after.vizState.originalInstance)
    }

    fun testNewForkAgreesUpToTheCurrentState() {
        open(TRACES)
        val before = viewer.session!!.model
        before.goTo(1)
        val after = request(SolutionKind.Fork).model
        assertNotSame(before.solution, after.solution)
        for (state in 0..1) assertEquals(before.solution.toString(state), after.solution.toString(state))
        // After the current state, the new trace is another one: in a later state, its length or its loop
        val laterStates = (2 until maxOf(before.traceLength, after.traceLength))
        val differs = before.traceLength != after.traceLength || before.loopState != after.loopState ||
            laterStates.any { before.solution.toString(it) != after.solution.toString(it) }
        assertTrue(differs)
    }

    fun testNoMoreSolutions() {
        open("one sig A {}\nrun {} for 1")
        val before = viewer.session!!
        assertSame(before, request(SolutionKind.Config))
        assertEquals(AlloyInstanceViewer.NO_MORE_SOLUTIONS, viewer.status)
    }

    /** `fork` gives back the same solution when there is no other one of that kind. */
    fun testTheSameSolutionIsNoMoreSolutions() {
        val before = open(STATIC)
        viewer.fork = { solution, _ -> solution }
        assertSame(before, request(SolutionKind.Config))
        assertEquals(AlloyInstanceViewer.NO_MORE_SOLUTIONS, viewer.status)
    }

    /** Or an unsatisfiable solution. */
    fun testAnUnsatisfiableSolutionIsNoMoreSolutions() {
        val before = open(STATIC)
        val unsat = solve("sig A {}\nrun { some A and no A } for 1")
        assertFalse(unsat.satisfiable())
        viewer.fork = { _, _ -> unsat }
        assertSame(before, request(SolutionKind.Config))
        assertEquals(AlloyInstanceViewer.NO_MORE_SOLUTIONS, viewer.status)
    }

    fun testAFailingSearchSaysWhyAndGivesTheAnalysisBack() {
        val before = open(STATIC)
        viewer.fork = { _, _ -> throw IllegalStateException("boom") }
        assertSame(before, request(SolutionKind.Config))
        assertEquals("boom", viewer.status)
        assertFalse(viewer.enumerating)
        assertTrue(analysisIsFree())
        // And the next search runs
        viewer.fork = { solution, p -> solution.fork(p) }
        assertNotSame(before, request(SolutionKind.Config))
        assertNull(viewer.status)
    }

    fun testBusyProjectRefuses() {
        open(STATIC)
        val execution = newExecution(project, myFixture.file.virtualFile, SHOW)
        AlloyRunService.getInstance(project).startRun(execution)
        val before = viewer.session
        assertFalse(viewer.requestSolution(SolutionKind.Config))
        assertEquals(AlloyRunService.BUSY_MESSAGE, viewer.status)
        assertSame(before, viewer.session)
    }

    /**
     * Requests a new configuration whose search never ends, runs [check] while it searches, then cancels the
     * search's progress indicator, as its Cancel button does. The task runs through the progress manager; only
     * its indicator is taken on the way. It may run on the test's own thread, so [check] and the cancel run on
     * another one.
     */
    private fun whileSearching(check: () -> Unit) {
        viewer.fork = { _, _ ->
            while (true) Thread.sleep(10)
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }
        val indicator = AtomicReference<ProgressIndicator>()
        viewer.schedule = { task ->
            ProgressManager.getInstance().run(object : Task.Backgroundable(project, task.title, true) {
                override fun run(progress: ProgressIndicator) {
                    indicator.set(progress)
                    task.run(progress)
                }

                override fun onFinished() = task.onFinished()
            })
        }
        val checked = AppExecutorUtil.getAppExecutorService().submit(Callable {
            val deadline = System.currentTimeMillis() + 10_000
            while ((!viewer.enumerating || indicator.get() == null) && System.currentTimeMillis() < deadline) Thread.sleep(10)
            Thread.sleep(100)
            val outcome = runCatching(check)
            indicator.get()?.cancel()
            outcome
        })
        assertTrue(viewer.requestSolution(SolutionKind.Config))
        waitUntil("the search did not stop") { !viewer.enumerating }
        checked.get(10, TimeUnit.SECONDS).getOrThrow()
    }

    fun testCommandsAreRefusedDuringAnEnumeration() {
        open(STATIC)
        whileSearching {
            assertTrue(viewer.enumerating)
            val execution = ReadAction.compute<AlloyCommandExecution, Throwable> { newExecution(project, myFixture.file.virtualFile, SHOW) }
            assertFalse(AlloyRunService.getInstance(project).startRun(execution))
        }
        assertTrue(analysisIsFree())
    }

    fun testCancellingTheProgressKeepsThePreviousSolution() {
        open(STATIC)
        val before = viewer.session
        whileSearching {}
        assertSame(before, viewer.session)
        assertEquals(AlloyInstanceViewer.STOPPED, viewer.status)
        assertTrue(analysisIsFree())
    }

    /** The platform skips `run` of a task cancelled before it starts, such as when the project closes, but not `onFinished`. */
    fun testATaskThatNeverRunsGivesTheAnalysisBack() {
        open(STATIC)
        val before = viewer.session
        viewer.schedule = { it.onFinished() }
        assertTrue(viewer.requestSolution(SolutionKind.Config))
        assertFalse(viewer.enumerating)
        assertSame(before, viewer.session)
        assertTrue(analysisIsFree())
    }

    /** Its first state is prepared with it, in the background task. */
    fun testANewSolutionComesPrepared() {
        open(STATIC)
        val after = request(SolutionKind.Config).model
        assertTrue(after.isPrepared(0))
    }

    /** A new solution shares its command's anchor, which follows the edits made after it was found. */
    fun testNavigatingFromANewSolutionFollowsEdits() {
        open(STATIC)
        val before = viewer.session!!
        val after = request(SolutionKind.Config)
        assertNotSame(before, after)
        myFixture.editor.caretModel.moveToOffset(0)
        myFixture.type("// a comment\n")
        viewer.navigateToOrigin()
        val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
        assertEquals(editor.document.text.indexOf("run show"), editor.caretModel.offset)
        assertTrue(viewer.header(), viewer.header().startsWith("m.als: run show for 3 (line 3)"))
    }

    fun testOutdatedResultDisablesEveryKind() {
        open(TRACES)
        myFixture.type("x")
        assertEmpty(enabled())
        assertFalse(viewer.requestSolution(SolutionKind.Config))
    }
}
