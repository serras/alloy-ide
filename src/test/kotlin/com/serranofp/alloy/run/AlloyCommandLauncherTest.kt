package com.serranofp.alloy.run

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.serranofp.alloy.lsp.AlloyExecutionMessage
import com.serranofp.alloy.lsp.AlloyMessageType
import com.serranofp.alloy.lsp.CommandsListResult

class AlloyCommandLauncherTest : BasePlatformTestCase() {
    private val service get() = AlloyRunService.getInstance(project)
    private val results get() = AlloyCommandResults.getInstance(project)
    private val uri = "file:///models/model.als"
    private val listed = CommandsListResult(
        listOf(
            item("Run show for 3", uri, 0, 2),
            item("Check noCycles for 3", uri, 1, 3),
            item("Run run\$3 for 2", uri, 2, 4),
        ),
    )
    private val show = AlloyCommandRef("show", 0)
    private lateinit var execution: AlloyCommandExecution

    override fun setUp() {
        super.setUp()
        results.clear()
        myFixture.configureByText("model.als", "sig A {}\nrun show for 3\ncheck noCycles for 3\nrun {} for 2\n")
    }

    override fun tearDown() {
        try {
            service.activeExecution?.terminate()
        } finally {
            super.tearDown()
        }
    }

    private val file get() = myFixture.file.virtualFile

    private fun launch(
        connection: AlloyServerConnection?,
        command: AlloyCommandRef,
        timeout: Long = 2_000,
        solverWarning: String? = null,
    ) {
        execution = newExecution(project, file, command, stopTimeoutMillis = 200)
        service.startRun(execution)
        AlloyCommandLauncher(service, timeout) { solverWarning }.launch(execution, connection, uri, "model.als", command)
    }

    private fun result(command: AlloyCommandRef = show) = results.get(file, command)!!

    fun testSolverFallbackWarningInDetails() {
        val connection = FakeServerConnection(service, listed)
        launch(connection, show, solverWarning = "The solver minisat is not available, using sat4j instead\n")
        assertEquals(listOf("The solver minisat is not available, using sat4j instead"), result().details.warnings)
        assertEquals(1, connection.executed.size)

        capturedOutput().take(8).forEach(service::executionOutput)
        assertEquals(CommandState.InstanceFound, result().state)
        assertEquals(listOf("The solver minisat is not available, using sat4j instead"), result().details.warnings)
    }

    fun testExecutesTheCommandFoundByLabel() {
        val connection = FakeServerConnection(service, listed)
        launch(connection, AlloyCommandRef("noCycles", 1))
        assertEquals(listOf(uri), connection.listed)
        assertEquals(listOf(listOf<Any?>(uri, 1, 3, 0)), connection.executed)
        assertFalse(execution.isFinished())
        assertEquals(CommandState.Running, result(AlloyCommandRef("noCycles", 1)).state)
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
        val renamed = AlloyCommandRef("renamed", 0)
        launch(connection, renamed)
        assertEmpty(connection.executed)
        assertTrue(execution.isFinished())
        assertEquals(CommandState.Failed, result(renamed).state)
        assertEquals("Command not found", result(renamed).summary)
        assertEquals("Command renamed was not found in model.als", result(renamed).details.failure)
    }

    fun testPositionNotFound() {
        val missing = AlloyCommandRef(null, 7)
        launch(FakeServerConnection(service, listed), missing)
        assertEquals("Command command 8 was not found in model.als", result(missing).details.failure)
    }

    fun testModelWithErrors() {
        val connection = FakeServerConnection(service, CommandsListResult(emptyList()))
        launch(connection, show)
        assertEmpty(connection.executed)
        assertTrue(execution.isFinished())
        assertEquals("Model has errors", result().summary)
        assertEquals("model.als has errors, see the problems reported in the editor", result().details.failure)
    }

    fun testServerDoesNotAnswer() {
        val connection = FakeServerConnection(service, commands = null)
        launch(connection, show, timeout = 100)
        assertEmpty(connection.executed)
        assertTrue(execution.isFinished())
        assertEquals("Analysis failed", result().summary)
        assertEquals("The Alloy language server did not list the commands of model.als in time", result().details.failure)
    }

    fun testServerDown() {
        launch(null, show)
        assertTrue(execution.isFinished())
        assertEquals(CommandState.Failed, result().state)
        assertEquals("Server unavailable", result().summary)
        assertEquals("The Alloy language server is unavailable", result().details.failure)
    }

    fun testStoppedWhileListingDoesNotExecute() {
        val inner = FakeServerConnection(service, commands = null)
        val connection = object : AlloyServerConnection by inner {
            override fun listCommands(uri: String) {
                execution.terminate()
                service.commandsListed(listed)
            }
        }
        launch(connection, show)
        assertEmpty(inner.executed)
        assertTrue(execution.isFinished())
        assertEquals(CommandState.Stopped, result().state)
    }

    fun testStopAsksTheServerAndEndsOnStopped() {
        val connection = FakeServerConnection(service, listed)
        launch(connection, show)
        execution.stop()
        assertEquals(1, connection.stops)
        assertTrue(execution.isFinished())
        assertEquals(CommandState.Stopped, result().state)
        assertEquals("Stopped", result().summary)
        assertNull(result().details.failure)
    }

    fun testStopEndsTheExecutionWhenTheServerDoesNotConfirm() {
        val connection = FakeServerConnection(service, listed, answersStop = false)
        launch(connection, show)
        execution.stop()
        assertFalse(execution.isFinished())
        assertTrue(execution.waitFor(5_000))
        assertNull(service.activeExecution)
        assertEquals(CommandState.Stopped, result().state)
        assertEquals("Stopped", result().summary)
        assertTrue(result().details.failure!!.contains("did not confirm"))
    }

    fun testStopBeforeTheServerIsKnown() {
        execution = newExecution(project, file, show)
        service.startRun(execution)
        execution.stop()
        assertTrue(execution.isFinished())
        assertEquals(CommandState.Stopped, result().state)
    }

    fun testOutputAfterStopIsIgnored() {
        val connection = FakeServerConnection(service, listed)
        launch(connection, show)
        execution.stop()
        val stopped = result()
        service.executionOutput(AlloyExecutionMessage(AlloyMessageType.RunInProgress.ordinal, "late\n"))
        assertSame(stopped, result())
    }
}
