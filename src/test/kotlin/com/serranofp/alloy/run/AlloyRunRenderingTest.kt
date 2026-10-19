package com.serranofp.alloy.run

import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.serranofp.alloy.lsp.AlloyExecutionMessage
import com.serranofp.alloy.lsp.AlloyMessageType

/** Renders the output recorded from the 6.2.0 server. */
class AlloyRunRenderingTest : BasePlatformTestCase() {
    private val service get() = AlloyRunService.getInstance(project)
    private val sink = RecordingSink()
    private lateinit var connection: FakeServerConnection
    private lateinit var run: AlloyRunProcessHandler

    override fun setUp() {
        super.setUp()
        connection = FakeServerConnection(service)
        run = AlloyRunProcessHandler(service, sink).also { it.connection = connection; it.startNotify() }
        service.startRun(run)
    }

    override fun tearDown() {
        try {
            service.activeRun?.terminate(0)
        } finally {
            super.tearDown()
        }
    }

    /** The first run of the recording: `run show for 3`, which finds an instance. */
    private val showRun = capturedOutput().take(8)

    fun testConsoleTextOfARun() {
        showRun.forEach(service::executionOutput)
        assertEquals(
            "Starting the solver...\n\n" +
                "Executing \"Run show for 3\"\n" +
                "   Solver=sat4j Bitwidth=4 MaxSeq=3 SkolemDepth=1 Symmetry=20 Mode=batch\n   Generating CNF...\n" +
                "   103 vars. 12 primary vars. 149 clauses. 29ms.\n   Solving...\n" +
                "   Writing the XML file..." +
                "\r   Instance found. Predicate is consistent. " +
                "4ms.\n\n",
            sink.text,
        )
    }

    fun testContentTypes() {
        showRun.forEach(service::executionOutput)
        val executing = sink.segments.single { it.text.startsWith("Executing") }
        assertSame(AlloyRunProcessHandler.BOLD_OUTPUT, executing.type)
        assertSame(ConsoleViewContentType.NORMAL_OUTPUT, sink.segments.first().type)
    }

    fun testReplaceLastOverwritesTheProgressLine() {
        showRun.forEach(service::executionOutput)
        val index = sink.segments.indexOfFirst { it.link != null }
        assertEquals("   Writing the XML file...", sink.segments[index - 2].text)
        assertEquals("\r", sink.segments[index - 1].text)
    }

    fun testInstanceLinkOpensTheVisualizer() {
        showRun.forEach(service::executionOutput)
        val link = sink.segments.single { it.link != null }
        assertEquals("   Instance found. Predicate is consistent. ", link.text)
        link.link!!.navigate(project)
        assertEquals(listOf(showRun[5].link), connection.opened)
        assertTrue(connection.opened.single().startsWith("XML: "))
    }

    fun testRunCompletedTerminates() {
        showRun.dropLast(1).forEach(service::executionOutput)
        assertFalse(run.isProcessTerminated)
        service.executionOutput(showRun.last())
        assertTrue(run.isProcessTerminated)
        assertEquals(0, run.exitCode)
        assertNull(service.activeRun)
    }

    fun testWarningsUseWarningStyle() {
        service.executionOutput(AlloyExecutionMessage(AlloyMessageType.Warning.ordinal, "Warning #1\nThe expression is redundant.\n"))
        assertSame(ConsoleViewContentType.LOG_WARNING_OUTPUT, sink.segments.single().type)
    }

    fun testCoreLinkIsSentToTheServer() {
        service.executionOutput(AlloyExecutionMessage(AlloyMessageType.RunResult.ordinal, "Core", link = "CORE: /tmp/core.xml", bold = true))
        sink.segments.single().link!!.navigate(project)
        assertEquals(listOf("CORE: /tmp/core.xml"), connection.opened)
    }
}
