package com.serranofp.alloy.viewer

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.openapi.application.ReadAction
import com.serranofp.alloy.run.AlloyCommandExecution
import com.serranofp.alloy.run.AlloyCommandRef
import com.serranofp.alloy.run.AlloyCommandResults
import com.serranofp.alloy.run.AlloyRunService
import com.serranofp.alloy.run.CommandResult
import com.serranofp.alloy.run.CommandState
import com.serranofp.alloy.run.newExecution
import com.serranofp.alloy.run.waitUntil
import com.serranofp.alloy.settings.AlloySettings
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit

class AlloyNewSolutionTest : BasePlatformTestCase() {
    private val viewer get() = AlloyInstanceViewer.getInstance(project)
    private val show = AlloyCommandRef("show", 0)

    override fun setUp() {
        super.setUp()
        AlloyCommandResults.getInstance(project).clear()
        viewer.clear()
        myFixture.configureByText("m.als", "pred show {}\nrun show for 3\n")
    }

    override fun tearDown() {
        try {
            viewer.stopEnumeration()
            AlloyRunService.getInstance(project).activeExecution?.terminate()
            viewer.clear()
        } finally {
            super.tearDown()
        }
    }

    private fun open(model: String) {
        val results = AlloyCommandResults.getInstance(project)
        val result = CommandResult(
            CommandState.InstanceFound, "found", link = link(model),
            documentStamp = results.stamp(myFixture.editor.document), settingsGeneration = AlloySettings.getInstance().generation,
        )
        viewer.open(myFixture.file.virtualFile, show, result)
    }

    private fun request(kind: SolutionKind): ViewerSession {
        assertTrue(viewer.requestSolution(kind))
        waitUntil("the new solution did not arrive") { !viewer.enumerating }
        return viewer.session!!
    }

    private fun enabled() = SolutionKind.entries.filter(viewer::isEnabled)

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
        assertFalse((0 until maxOf(before.traceLength, after.traceLength)).all { before.solution.toString(it) == after.solution.toString(it) } &&
            before.traceLength == after.traceLength && before.loopState == after.loopState)
    }

    fun testNoMoreSolutions() {
        open("one sig A {}\nrun {} for 1")
        val before = viewer.session!!
        assertSame(before, request(SolutionKind.Config))
        assertEquals(AlloyInstanceViewer.NO_MORE_SOLUTIONS, viewer.status)
    }

    fun testBusyProjectRefuses() {
        open(STATIC)
        val execution = newExecution(project, myFixture.file.virtualFile, show)
        AlloyRunService.getInstance(project).startRun(execution)
        val before = viewer.session
        assertFalse(viewer.requestSolution(SolutionKind.Config))
        assertEquals(AlloyRunService.BUSY_MESSAGE, viewer.status)
        assertSame(before, viewer.session)
    }

    /**
     * Requests a new configuration whose search never ends, runs [check] while it searches, then stops it.
     * The background task may run on the test's own thread, so [check] and the stop run on another one.
     */
    private fun whileSearching(check: () -> Unit) {
        viewer.fork = { _, _ ->
            while (true) Thread.sleep(10)
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }
        val checked = AppExecutorUtil.getAppExecutorService().submit(Callable {
            val deadline = System.currentTimeMillis() + 10_000
            while (!viewer.enumerating && System.currentTimeMillis() < deadline) Thread.sleep(10)
            Thread.sleep(100)
            val outcome = runCatching(check)
            viewer.stopEnumeration()
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
            val execution = ReadAction.compute<AlloyCommandExecution, Throwable> { newExecution(project, myFixture.file.virtualFile, show) }
            assertFalse(AlloyRunService.getInstance(project).startRun(execution))
        }
        assertTrue(AlloyRunService.getInstance(project).startRun(newExecution(project, myFixture.file.virtualFile, show)))
    }

    fun testStoppingKeepsThePreviousSolution() {
        open(STATIC)
        val before = viewer.session
        whileSearching {}
        assertSame(before, viewer.session)
        assertEquals(AlloyInstanceViewer.STOPPED, viewer.status)
    }

    fun testOutdatedResultDisablesEveryKind() {
        open(TRACES)
        myFixture.type("x")
        assertEmpty(enabled())
        assertFalse(viewer.requestSolution(SolutionKind.Config))
    }
}
