package com.serranofp.alloy.run

import com.serranofp.alloy.lsp.AlloyExecutionMessage
import com.serranofp.alloy.lsp.AlloyMessageType
import com.serranofp.alloy.run.AlloyRunLog.Outcome
import junit.framework.TestCase

class AlloyRunLogTest : TestCase() {
    private val captured = capturedOutput()

    /** The recording holds three runs: `run show for 3`, `check noCycles for 3`, and a stopped `run big`. */
    private val showRun get() = captured.subList(0, 8)
    private val noCyclesRun get() = captured.subList(8, 16)
    private val bigRun get() = captured.subList(16, captured.size)

    private fun log(messages: List<AlloyExecutionMessage>) = AlloyRunLog().apply { messages.forEach(::accept) }

    fun testInstanceFound() {
        val log = log(showRun)
        assertEquals(Outcome.InstanceFound, log.outcome)
        assertFalse(log.unexpected)
        assertEquals(4L, log.timeMillis)
        assertEquals("XML: /tmp/alloy-tmp-user/tmp/526174/0.cnf.xml", log.link)
        assertTrue(log.completed)
        assertEquals("", log.completionText)
        assertEquals("Instance found · 4 ms", log.summary)
    }

    fun testLinesOfARun() {
        assertEquals(
            listOf(
                "Starting the solver...",
                "",
                "Executing \"Run show for 3\"",
                "   Solver=sat4j Bitwidth=4 MaxSeq=3 SkolemDepth=1 Symmetry=20 Mode=batch",
                "   Generating CNF...",
                "   103 vars. 12 primary vars. 149 clauses. 29ms.",
                "   Solving...",
                "   Instance found. Predicate is consistent. 4ms.",
            ),
            log(showRun).lines.map { it.text },
        )
    }

    fun testBoldSpans() {
        val lines = log(showRun).lines
        assertTrue(lines.single { it.text.startsWith("Executing") }.spans.single().bold)
        assertFalse(lines.first().spans.single().bold)
        assertEquals(
            listOf(AlloyRunLog.Span("   Instance found. Predicate is consistent. ", bold = true), AlloyRunLog.Span("4ms.")),
            lines.last().spans,
        )
    }

    fun testReplacedProgressLineIsGone() {
        assertTrue(log(showRun).lines.none { "Writing the XML file" in it.text })
    }

    fun testProgressFollowsTheAnalyzer() {
        val log = AlloyRunLog()
        showRun.take(2).forEach(log::accept)
        assertEquals("Executing \"Run show for 3\"", log.progress)
        log.accept(showRun[2])
        assertEquals("Generating CNF...", log.progress)
        log.accept(showRun[3])
        assertEquals("Solving...", log.progress)
        assertFalse(log.completed)
        assertEquals(AlloyRunLog.RUNNING, log.summary)
    }

    fun testCounterexampleFound() {
        val log = log(noCyclesRun)
        assertEquals(Outcome.CounterexampleFound, log.outcome)
        assertEquals("XML: /tmp/alloy-tmp-user/tmp/84888/1.cnf.xml", log.link)
        assertEquals("Counterexample found · 2 ms", log.summary)
        assertEquals("Executing \"Check noCycles for 3\"", log.lines[2].text)
    }

    fun testStopped() {
        val log = log(bigRun)
        assertEquals(Outcome.None, log.outcome)
        assertNull(log.link)
        assertNull(log.timeMillis)
        assertTrue(log.completed)
        assertEquals("Stopped", log.completionText)
        assertEquals("Stopped", log.summary)
        assertEquals("Solving...", log.progress)
        assertEquals("   Solving...", log.lines.last().text)
    }

    // Synthetic sequences, with the texts that the 6.2.0 server builds in solverCallbackMsgToAlloyMsg

    private fun progress(text: String) = AlloyExecutionMessage(AlloyMessageType.RunInProgress.ordinal, text)

    private fun result(text: String, link: String? = null, bold: Boolean = false, replaceLast: Boolean = false) =
        AlloyExecutionMessage(AlloyMessageType.RunResult.ordinal, text, link = link, bold = bold, replaceLast = replaceLast)

    private fun completed(text: String = "") = AlloyExecutionMessage(AlloyMessageType.RunCompleted.ordinal, text)

    private val start = listOf(
        progress("Starting the solver...\n\n"),
        progress("Executing \"Check acyclic for 3\"\n").copy(bold = true),
        progress("   Solver=sat4j Bitwidth=4 MaxSeq=3 SkolemDepth=1 Symmetry=20 Mode=batch\n   Generating CNF...\n"),
        progress("   212 vars. 15 primary vars. 322 clauses. 5ms.\n   Solving...\n"),
    )

    fun testUnsatWithoutCoreMinimization() {
        val log = log(
            start + listOf(
                result("   No counterexample found. Assertion may be valid. ", bold = true, replaceLast = true),
                result("3ms.\n\n"),
                completed(),
            ),
        )
        assertEquals(Outcome.NoCounterexample, log.outcome)
        assertNull(log.link)
        assertEquals("No counterexample found · 3 ms", log.summary)
        // replaceLast drops the "Solving..." line, but not the statistics printed by the same message
        assertEquals(
            listOf("   212 vars. 15 primary vars. 322 clauses. 5ms.", "   No counterexample found. Assertion may be valid. 3ms."),
            log.lines.takeLast(2).map { it.text },
        )
    }

    /** With core minimization: a "minimizing" progress line, then the result that replaces it, then the core. */
    private fun minimized(vararg core: AlloyExecutionMessage) = log(
        start + listOf(
            progress("   No counterexample found.  Assertion may be valid. 3ms.\n"),
            result(
                "   No counterexample found. Assertion may be valid. 3ms.\n   ",
                bold = true,
                replaceLast = true,
            ),
        ) + core + completed(),
    )

    fun testUnsatWithCore() {
        val log = minimized(
            result("core", link = "/tmp/alloy-tmp-user/tmp/1234/core.xml"),
            result(" contains 2 top-level formulas. 7ms.\n\n"),
        )
        assertEquals(Outcome.NoCounterexample, log.outcome)
        assertEquals("CORE: /tmp/alloy-tmp-user/tmp/1234/core.xml", log.link)
        assertEquals("No counterexample found · 3 ms", log.summary)
        assertEquals(
            listOf("   No counterexample found. Assertion may be valid. 3ms.", "   core contains 2 top-level formulas. 7ms."),
            log.lines.takeLast(2).map { it.text },
        )
    }

    fun testReducedCore() {
        val log = minimized(
            result("core", link = "/tmp/core.xml"),
            result(" reduced from 5 to 2 top-level formulas. 7ms.\n\n"),
        )
        assertEquals("CORE: /tmp/core.xml", log.link)
        assertEquals(3L, log.timeMillis)
    }

    fun testCoreLinkWithPrefixIsKept() {
        val log = minimized(result("core", link = "CORE: /tmp/core.xml"))
        assertEquals("CORE: /tmp/core.xml", log.link)
    }

    fun testNoUnsatCoreAvailable() {
        val log = log(
            start + listOf(
                result(
                    "   No instance found. Predicate may be inconsistent. 3ms.\n   No unsat core is available in this case. ",
                    bold = true,
                    replaceLast = true,
                ),
                result("1ms.\n\n"),
                completed(),
            ),
        )
        assertEquals(Outcome.NoInstance, log.outcome)
        assertNull(log.link)
        assertEquals("No instance found · 3 ms", log.summary)
        assertEquals("   No unsat core is available in this case. 1ms.", log.lines.last().text)
    }

    fun testInstanceContraryToExpectation() {
        val log = log(
            start + listOf(
                progress("   Writing the XML file..."),
                result(
                    "   Instance found. Predicate is consistent, contrary to expectation. ",
                    link = "XML: /tmp/0.cnf.xml",
                    bold = true,
                    replaceLast = true,
                ),
                result("4ms.\n\n"),
                completed(),
            ),
        )
        assertEquals(Outcome.InstanceFound, log.outcome)
        assertTrue(log.unexpected)
        assertEquals("Instance found, contrary to expectation · 4 ms", log.summary)
    }

    fun testAsExpectedIsNotUnexpected() {
        val log = log(
            start + listOf(
                result("   No instance found. Predicate may be inconsistent, as expected. ", bold = true, replaceLast = true),
                result("2ms.\n\n"),
                completed(),
            ),
        )
        assertFalse(log.unexpected)
        assertEquals("No instance found · 2 ms", log.summary)
    }

    fun testWarningsWithoutResult() {
        val warning = AlloyExecutionMessage(
            AlloyMessageType.Warning.ordinal,
            "Warning The join operation here always yields an empty set.\n",
            bold = true,
        )
        val log = log(listOf(progress("Starting the solver...\n\n"), warning, result("There were errors/warnings!"), completed()))
        assertEquals(Outcome.None, log.outcome)
        assertNull(log.link)
        assertTrue(log.completed)
        assertEquals(AlloyRunLog.FAILED, log.summary)
        val line = log.lines.single { it.text.startsWith("Warning") }
        assertTrue(line.spans.single().warning)
        assertEquals("There were errors/warnings!", log.lines.last().text)
    }

    fun testFailureCompletion() {
        val log = log(listOf(progress("Starting the solver...\n\n"), completed("failure")))
        assertEquals("failure", log.completionText)
        assertEquals(AlloyRunLog.FAILED, log.summary)
    }
}
