package com.serranofp.alloy.run

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import com.serranofp.alloy.lsp.CommandsListResult
import java.util.concurrent.TimeUnit

class AlloyRunLaunchTest : BasePlatformTestCase() {
    private val service get() = AlloyRunService.getInstance(project)
    private val results get() = AlloyCommandResults.getInstance(project)
    private val show = AlloyCommandRef("show", 0)
    private val noCycles = AlloyCommandRef("noCycles", 1)
    private val uri get() = "file://${file.path}"

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

    private fun listed() = CommandsListResult(listOf(item("Run show for 3", uri, 0, 0), item("Check noCycles for 3", uri, 1, 1)))

    private fun launcher() = AlloyCommandLauncher(service, 2_000) { null }

    /** The task may run synchronously (headless tests) or in the background: wait for the execution to end. */
    private fun waitUntilIdle() {
        val deadline = System.currentTimeMillis() + 10_000
        while (service.activeExecution != null) {
            assertTrue("execution did not finish", System.currentTimeMillis() < deadline)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(10)
        }
        UIUtil.dispatchAllInvocationEvents()
    }

    fun testLaunchStoresRunningThenTheResult() {
        val connection = FakeServerConnection(service, listed())
        var whileExecuting: CommandResult? = null
        connection.onExecute = {
            whileExecuting = results.get(file, show)
            capturedOutput().take(8).forEach(service::executionOutput)
        }
        assertTrue(service.launch(file, show, myFixture.editor, { connection }, launcher()))
        waitUntilIdle()

        assertEquals(1, connection.executed.size)
        assertEquals(CommandState.Running, whileExecuting!!.state)
        val result = results.get(file, show)!!
        assertEquals(CommandState.InstanceFound, result.state)
        assertEquals("Instance found · 4 ms", result.summary)
        assertFalse(results.isOutdated(result, myFixture.editor.document))
    }

    fun testLaunchWithoutServerFails() {
        assertTrue(service.launch(file, show, myFixture.editor, { null }, launcher()))
        waitUntilIdle()
        assertEquals("Server unavailable", results.get(file, show)!!.summary)
    }

    fun testSecondLaunchWhileBusyLeavesTheStoreUnchanged() {
        val first = newExecution(project, file, show)
        service.startRun(first)
        val before = results.results(file)

        val connection = FakeServerConnection(service, listed())
        assertFalse(service.launch(file, noCycles, myFixture.editor, { connection }, launcher()))
        assertSame(first, service.activeExecution)
        assertEquals(before, results.results(file))
        assertNull(results.get(file, noCycles))
        assertTrue(connection.listed.isEmpty())
    }

    fun testCancellingTheIndicatorSendsOneStop() {
        val connection = FakeServerConnection(service, listed())
        val execution = newExecution(project, file, show)
        service.startRun(execution)
        val indicator = EmptyProgressIndicator()
        val task = AlloyCommandTask(project, execution, launcher()) { connection }
        val running = ApplicationManager.getApplication().executeOnPooledThread { task.run(indicator) }

        val deadline = System.currentTimeMillis() + 5_000
        while (connection.executed.isEmpty()) {
            assertTrue(System.currentTimeMillis() < deadline)
            Thread.sleep(10)
        }
        indicator.cancel()
        running.get(5, TimeUnit.SECONDS)
        UIUtil.dispatchAllInvocationEvents()

        assertEquals(1, connection.stops)
        assertEquals(CommandState.Stopped, results.get(file, show)!!.state)
        assertNull(service.activeExecution)
    }

    fun testTaskIsNamedAfterTheCommand() {
        val execution = newExecution(project, file, show)
        assertEquals("Alloy: Run show", AlloyCommandTask(project, execution, launcher()) { null }.title)
    }
}
