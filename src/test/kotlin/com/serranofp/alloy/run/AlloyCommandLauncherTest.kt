package com.serranofp.alloy.run

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.serranofp.alloy.lsp.AlloyExecutionMessage
import com.serranofp.alloy.lsp.AlloyMessageType
import com.serranofp.alloy.lsp.CommandsListResult

class AlloyCommandLauncherTest : BasePlatformTestCase() {
    private val service get() = AlloyRunService.getInstance(project)
    private val uri = "file:///models/model.als"
    private val listed = CommandsListResult(
        listOf(
            item("Run show for 3", uri, 0, 2),
            item("Check noCycles for 3", uri, 1, 3),
            item("Run run\$3 for 2", uri, 2, 4),
        ),
    )
    private val sink = RecordingSink()
    private lateinit var run: AlloyRunProcessHandler

    override fun setUp() {
        super.setUp()
        run = AlloyRunProcessHandler(service, sink, stopTimeoutMillis = 200).also { it.startNotify() }
        service.startRun(run)
    }

    override fun tearDown() {
        try {
            service.activeRun?.terminate(0)
        } finally {
            super.tearDown()
        }
    }

    private fun launch(connection: AlloyServerConnection?, command: AlloyCommandRef, timeout: Long = 2_000) =
        AlloyCommandLauncher(service, timeout).launch(run, connection, uri, "model.als", command)

    fun testExecutesTheCommandFoundByLabel() {
        val connection = FakeServerConnection(service, listed)
        launch(connection, AlloyCommandRef("noCycles", 1))
        assertEquals(listOf(uri), connection.listed)
        assertEquals(listOf(listOf<Any?>(uri, 1, 3, 0)), connection.executed)
        assertFalse(run.isProcessTerminated)
    }

    fun testLabelWinsOverAStalePosition() {
        val connection = FakeServerConnection(service, listed)
        launch(connection, AlloyCommandRef("show", 1))
        assertEquals(listOf(listOf<Any?>(uri, 0, 2, 0)), connection.executed)
    }

    fun testUnlabelledCommandIsFoundByPosition() {
        val connection = FakeServerConnection(service, listed)
        launch(connection, AlloyCommandRef(null, 2))
        assertEquals(listOf(listOf<Any?>(uri, 2, 4, 0)), connection.executed)
    }

    fun testCommandNotFound() {
        val connection = FakeServerConnection(service, listed)
        launch(connection, AlloyCommandRef("renamed", 0))
        assertEmpty(connection.executed)
        assertTrue(run.isProcessTerminated)
        assertEquals("Command renamed was not found in model.als\n", sink.text)
    }

    fun testPositionNotFound() {
        launch(FakeServerConnection(service, listed), AlloyCommandRef(null, 7))
        assertEquals("Command command 8 was not found in model.als\n", sink.text)
    }

    fun testModelWithErrors() {
        val connection = FakeServerConnection(service, CommandsListResult(emptyList()))
        launch(connection, AlloyCommandRef("show", 0))
        assertEmpty(connection.executed)
        assertTrue(run.isProcessTerminated)
        assertEquals("model.als has errors, see the problems reported in the editor\n", sink.text)
    }

    fun testServerDoesNotAnswer() {
        val connection = FakeServerConnection(service, commands = null)
        launch(connection, AlloyCommandRef("show", 0), timeout = 100)
        assertEmpty(connection.executed)
        assertTrue(run.isProcessTerminated)
        assertEquals("The Alloy language server did not list the commands of model.als in time\n", sink.text)
    }

    fun testServerDown() {
        launch(null, AlloyCommandRef("show", 0))
        assertTrue(run.isProcessTerminated)
        assertEquals(1, run.exitCode)
        assertEquals("The Alloy language server is unavailable\n", sink.text)
    }

    fun testStoppedWhileListingDoesNotExecute() {
        val inner = FakeServerConnection(service, commands = null)
        val connection = object : AlloyServerConnection by inner {
            override fun listCommands(uri: String) {
                run.terminate(1)
                service.commandsListed(listed)
            }
        }
        launch(connection, AlloyCommandRef("show", 0))
        assertEmpty(inner.executed)
        assertTrue(run.isProcessTerminated)
    }

    fun testStopAsksTheServerAndEndsOnStopped() {
        val connection = FakeServerConnection(service, listed)
        launch(connection, AlloyCommandRef("show", 0))
        run.destroyProcess()
        assertEquals(1, connection.stops)
        assertTrue(run.isProcessTerminated)
        assertEquals("Stopped", sink.text)
        assertSame(AlloyRunProcessHandler.BOLD_OUTPUT, sink.segments.single().type)
    }

    fun testStopEndsTheRunWhenTheServerDoesNotConfirm() {
        val connection = FakeServerConnection(service, listed, answersStop = false)
        launch(connection, AlloyCommandRef("show", 0))
        run.destroyProcess()
        assertFalse(run.isProcessTerminated)
        assertTrue(run.waitFor(5_000))
        assertTrue(run.isProcessTerminated)
        assertTrue(sink.text, sink.text.contains("did not confirm"))
        assertNull(service.activeRun)
    }

    fun testStopBeforeTheServerIsKnown() {
        run.destroyProcess()
        assertTrue(run.isProcessTerminated)
        assertEquals("Stopped\n", sink.text)
    }

    fun testOutputAfterStopIsIgnored() {
        val connection = FakeServerConnection(service, listed)
        launch(connection, AlloyCommandRef("show", 0))
        run.destroyProcess()
        service.executionOutput(AlloyExecutionMessage(AlloyMessageType.RunInProgress.ordinal, "late\n"))
        assertEquals("Stopped", sink.text)
    }
}
