package com.serranofp.alloy.run

import com.serranofp.alloy.analysis.AlloyAnalysisEvent
import com.serranofp.alloy.analysis.AlloyFailure

class AlloyRunServiceTest : AlloyRunTestCase() {
    private val show = AlloyCommandRef("show", 0)
    private val noCycles = AlloyCommandRef("noCycles", 1)

    override fun setUp() {
        super.setUp()
        myFixture.configureByText("model.als", "run show for 3\ncheck noCycles for 3\n")
    }

    private val file get() = myFixture.file.virtualFile

    private fun newExecution(command: AlloyCommandRef = show) = newExecution(project, file, command)

    private fun result(command: AlloyCommandRef = show) = results.get(file, command)

    /** The events of `run show for 3`, as the engine reports them. */
    private val showRun by lazy { analysisEvents("sig Node {}\npred show { some Node }\nrun show for 3") }

    fun testStartRecordsRunning() {
        val execution = newExecution()
        assertTrue(service.startRun(execution))
        assertSame(execution, service.activeExecution)
        assertEquals(CommandState.Running, result()!!.state)
        assertEquals("Running…", result()!!.summary)
    }

    fun testSecondLaunchIsRefused() {
        val first = newExecution()
        service.startRun(first)
        assertFalse(service.startRun(newExecution(noCycles)))
        assertSame(first, service.activeExecution)
        assertNull(result(noCycles))
    }

    fun testBusyRefusalLeavesAnEarlierResult() {
        val earlier = newExecution(noCycles)
        service.startRun(earlier)
        showRun.forEach(earlier::accept)
        val before = result(noCycles)
        assertEquals(CommandState.InstanceFound, before!!.state)

        service.startRun(newExecution())
        assertFalse(service.startRun(newExecution(noCycles)))
        assertSame(before, result(noCycles))
    }

    fun testNextLaunchIsAllowedOnceTheRunFinished() {
        val first = newExecution()
        service.startRun(first)
        first.terminate()
        assertNull(service.activeExecution)
        assertTrue(service.startRun(newExecution()))
    }

    fun testProgressIsRecordedAsRunning() {
        val execution = newExecution()
        service.startRun(execution)
        showRun.takeWhile { it !is AlloyAnalysisEvent.End }.forEach(execution::accept)
        assertEquals(CommandState.Running, result()!!.state)
        assertEquals("   Solving...", result()!!.details.log.last().text)
    }

    fun testAnalysisRecordsItsResult() {
        val execution = newExecution()
        service.startRun(execution)
        showRun.forEach(execution::accept)
        assertTrue(execution.isFinished())
        assertNull(service.activeExecution)
        val result = result()!!
        assertEquals(CommandState.InstanceFound, result.state)
        assertTrue(result.summary, result.summary.startsWith("Instance found · "))
        assertSame((showRun.last() as AlloyAnalysisEvent.Sat).solution, (result.link as CommandLink.Solution).solution)
        assertTrue(result.details.log.last().text.startsWith("   Instance found. Predicate is consistent. "))
    }

    fun testEventsAfterTheEndAreIgnored() {
        val execution = newExecution()
        service.startRun(execution)
        showRun.forEach(execution::accept)
        val finished = result()
        execution.accept(AlloyAnalysisEvent.Stopped(0))
        assertSame(finished, result())
    }

    fun testCallsAfterTheEndChangeNothing() {
        val execution = newExecution()
        service.startRun(execution)
        showRun.forEach(execution::accept)
        val finished = result()
        execution.warn("A late warning")
        execution.fail("Analysis failed", "a late failure")
        execution.stop()
        execution.terminate()
        assertSame(finished, result())
        assertNull(service.activeExecution)
    }

    fun testStopBeforeTheAnalysisEndsTheExecution() {
        val execution = newExecution()
        service.startRun(execution)
        execution.stop()
        assertTrue(execution.isFinished())
        assertEquals(CommandState.Stopped, result()!!.state)
        // Nor does the analysis start afterwards
        var started = false
        execution.begin({ started = true; error("started") })
        assertFalse(started)
    }

    fun testFailureShowsItsCause() {
        val execution = newExecution()
        service.startRun(execution)
        execution.accept(AlloyAnalysisEvent.Failed(AlloyFailure.MemoryExhausted, 0))
        assertEquals(CommandState.Failed, result()!!.state)
        assertEquals("Analysis failed", result()!!.summary)
        assertEquals("The analysis ran out of memory", result()!!.details.failure)
    }

    fun testDisposeEndsTheActiveExecution() {
        val execution = newExecution()
        service.startRun(execution)
        service.dispose()
        assertTrue(execution.isFinished())
        assertEquals(CommandState.Stopped, result()!!.state)
    }

    fun testResultKeepsTheStampOfTheLaunch() {
        val execution = newExecution()
        service.startRun(execution)
        myFixture.type("x")
        showRun.forEach(execution::accept)
        assertEquals(Outdated.FileChanged, results.outdated(result()!!, myFixture.editor.document))
    }

    fun testSuiteCannotStartWhileAnExecutionRuns() {
        service.startRun(newExecution())
        assertNull(service.startSuite())
    }

    fun testSuiteCannotStartWhileAnEnumerationRuns() {
        assertTrue(service.startEnumeration())
        try {
            assertNull(service.startSuite())
        } finally {
            service.enumerationFinished()
        }
    }

    fun testOnlyOneSuiteAtATime() {
        val suite = service.startSuite()!!
        try {
            assertNull(service.startSuite())
        } finally {
            service.suiteFinished(suite)
        }
    }

    fun testSuiteRefusesOtherLaunchesAndEnumerations() {
        val suite = service.startSuite()!!
        try {
            assertFalse(service.startRun(newExecution()))
            assertNull(result())
            assertFalse(service.launch(file, show, myFixture.editor))
            assertNull(result())
            assertFalse(service.startEnumeration())
        } finally {
            service.suiteFinished(suite)
        }
    }

    fun testSuiteRunsItsOwnExecutionsOneAtATime() {
        val suite = service.startSuite()!!
        try {
            val first = newExecution()
            assertTrue(service.startRun(first, suite))
            assertFalse(service.startRun(newExecution(noCycles), suite))
            first.terminate()
            assertTrue(service.startRun(newExecution(noCycles), suite))
            service.activeExecution!!.terminate()
        } finally {
            service.suiteFinished(suite)
        }
        assertNotNull(service.startSuite()?.also(service::suiteFinished))
        assertTrue(service.startRun(newExecution()))
    }

    fun testExecutionOfAFinishedSuiteIsRefused() {
        val suite = service.startSuite()!!
        service.suiteFinished(suite)
        assertTrue(service.startEnumeration())
        service.enumerationFinished()
        assertFalse(service.startRun(newExecution(), AlloyRunService.Suite()))
    }
}
