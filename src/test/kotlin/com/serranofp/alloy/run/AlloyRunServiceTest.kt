package com.serranofp.alloy.run

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.serranofp.alloy.lsp.AlloyExecutionMessage
import com.serranofp.alloy.lsp.AlloyMessageType
import com.serranofp.alloy.lsp.AlloyServerNotifications

class AlloyRunServiceTest : BasePlatformTestCase() {
    private val service get() = AlloyRunService.getInstance(project)
    private val results get() = AlloyCommandResults.getInstance(project)
    private val show = AlloyCommandRef("show", 0)
    private val noCycles = AlloyCommandRef("noCycles", 1)

    override fun setUp() {
        super.setUp()
        results.clear()
        myFixture.configureByText("model.als", "run show for 3\ncheck noCycles for 3\n")
    }

    override fun tearDown() {
        try {
            service.activeExecution?.terminate()
        } finally {
            super.tearDown()
        }
    }

    private val file get() = myFixture.file.virtualFile

    private fun newExecution(command: AlloyCommandRef = show) = newExecution(project, file, command)

    private fun result(command: AlloyCommandRef = show) = results.get(file, command)

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
        capturedOutput().subList(8, 16).forEach(service::executionOutput)
        val before = result(noCycles)
        assertEquals(CommandState.CounterexampleFound, before!!.state)

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

    fun testOutputGoesToTheActiveExecution() {
        service.startRun(newExecution())
        AlloyServerNotifications.publisher(project).executionOutput(AlloyExecutionMessage(AlloyMessageType.RunInProgress.ordinal, "   Solving...\n"))
        assertEquals(CommandState.Running, result()!!.state)
        assertEquals(listOf("   Solving..."), result()!!.details.log.map { it.text })
    }

    fun testCapturedRunRecordsItsResult() {
        val execution = newExecution()
        service.startRun(execution)
        capturedOutput().take(8).forEach(service::executionOutput)
        assertTrue(execution.isFinished())
        assertNull(service.activeExecution)
        val result = result()!!
        assertEquals(CommandState.InstanceFound, result.state)
        assertEquals("Instance found · 4 ms", result.summary)
        assertEquals("XML: /tmp/alloy-tmp-user/tmp/526174/0.cnf.xml", result.link)
        assertEquals("   Instance found. Predicate is consistent. 4ms.", result.details.log.last().text)
    }

    fun testOutputWithNoActiveExecutionIsIgnored() {
        val execution = newExecution()
        service.startRun(execution)
        service.executionOutput(AlloyExecutionMessage(AlloyMessageType.RunCompleted.ordinal, "Stopped"))
        assertTrue(execution.isFinished())
        assertNull(service.activeExecution)
        val stopped = result()

        // A late message of the finished execution reaches no one
        AlloyServerNotifications.publisher(project).executionOutput(AlloyExecutionMessage(AlloyMessageType.RunInProgress.ordinal, "late\n"))
        assertSame(stopped, result())
        assertEquals(CommandState.Stopped, stopped!!.state)
    }

    fun testCompletionWithoutResultIsAFailure() {
        service.startRun(newExecution())
        service.executionOutput(AlloyExecutionMessage(AlloyMessageType.RunResult.ordinal, "There were errors/warnings!"))
        service.executionOutput(AlloyExecutionMessage(AlloyMessageType.RunCompleted.ordinal, ""))
        assertEquals(CommandState.Failed, result()!!.state)
        assertEquals("Analysis failed", result()!!.summary)
    }

    fun testServerStoppingEndsTheActiveExecution() {
        val execution = newExecution()
        service.startRun(execution)
        service.serverStopped()
        assertTrue(execution.isFinished())
        assertNull(service.activeExecution)
        assertEquals(CommandState.Failed, result()!!.state)
        assertEquals("Server unavailable", result()!!.summary)
        assertEquals("The Alloy language server is unavailable", result()!!.details.failure)
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
        capturedOutput().take(8).forEach(service::executionOutput)
        assertTrue(results.isOutdated(result()!!, myFixture.editor.document))
    }
}
