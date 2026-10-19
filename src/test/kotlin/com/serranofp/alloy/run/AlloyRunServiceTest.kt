package com.serranofp.alloy.run

import com.intellij.execution.ExecutionException
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.serranofp.alloy.lsp.AlloyExecutionMessage
import com.serranofp.alloy.lsp.AlloyMessageType
import com.serranofp.alloy.lsp.AlloyServerNotifications

class AlloyRunServiceTest : BasePlatformTestCase() {
    private val service get() = AlloyRunService.getInstance(project)

    private fun newRun(sink: RecordingSink = RecordingSink()) =
        AlloyRunProcessHandler(service, sink).also { it.startNotify() }

    override fun tearDown() {
        try {
            service.activeRun?.terminate(0)
        } finally {
            super.tearDown()
        }
    }

    fun testSecondLaunchIsRefused() {
        val first = newRun()
        service.startRun(first)
        val error = runCatching { service.startRun(newRun()) }.exceptionOrNull()
        assertInstanceOf(error, ExecutionException::class.java)
        assertEquals("An Alloy analysis is already running", error!!.message)
        assertSame(first, service.activeRun)
    }

    fun testNextLaunchIsAllowedOnceTheRunFinished() {
        val first = newRun()
        service.startRun(first)
        first.terminate(0)
        assertNull(service.activeRun)
        service.startRun(newRun())
    }

    fun testOutputGoesToTheActiveRun() {
        val sink = RecordingSink()
        service.startRun(newRun(sink))
        AlloyServerNotifications.publisher(project).executionOutput(AlloyExecutionMessage(AlloyMessageType.RunInProgress.ordinal, "Solving...\n"))
        assertEquals("Solving...\n", sink.text)
    }

    fun testOutputWithNoActiveRunIsIgnored() {
        val sink = RecordingSink()
        val run = newRun(sink)
        service.startRun(run)
        service.executionOutput(AlloyExecutionMessage(AlloyMessageType.RunCompleted.ordinal, ""))
        assertTrue(run.isProcessTerminated)
        assertNull(service.activeRun)

        // A late message of the finished run reaches no one
        AlloyServerNotifications.publisher(project).executionOutput(AlloyExecutionMessage(AlloyMessageType.RunInProgress.ordinal, "late\n"))
        assertEquals("", sink.text)
    }

    fun testServerStoppingEndsTheActiveRun() {
        val sink = RecordingSink()
        val run = newRun(sink)
        service.startRun(run)
        service.serverStopped()
        assertTrue(run.isProcessTerminated)
        assertEquals(1, run.exitCode)
        assertEquals("The Alloy language server is unavailable\n", sink.text)
    }
}
