package com.serranofp.alloy.run

import com.serranofp.alloy.run.AlloyRunLog.Line
import com.serranofp.alloy.run.AlloyRunLog.Span
import junit.framework.TestCase

class AlloyCommandResultTooltipTest : TestCase() {
    private fun result(details: CommandDetails, state: CommandState = CommandState.InstanceFound) =
        CommandResult(state, "Instance found · 4 ms", details, documentStamp = 0, settingsGeneration = 0)

    private fun line(vararg spans: Span) = Line(spans.toList())

    private fun html(details: CommandDetails, outdated: Outdated? = null) = AlloyCommandResultTooltip.html(result(details), outdated)

    fun testEscapesLogText() {
        assertEquals(
            "<html>Warning a &lt; b &amp;&amp; <b>c &gt; d</b></html>",
            html(CommandDetails(log = listOf(line(Span("Warning a < b && "), Span("c > d", bold = true))))),
        )
    }

    fun testOutdatedByAnEdit() {
        assertEquals(
            "<html><i>Outdated: the file changed after this analysis</i><br>Solving...</html>",
            html(CommandDetails(log = listOf(line(Span("Solving...")))), Outdated.FileChanged),
        )
    }

    fun testOutdatedByASettingsChange() {
        assertTrue(html(CommandDetails(), Outdated.SettingsChanged).startsWith("<html><i>Outdated: the analyzer settings changed after this analysis</i>"))
    }

    fun testSolverWarningComesFirst() {
        assertEquals(
            "<html><i>Outdated: the file changed after this analysis</i><br>" +
                "The solver minisat is not available, using sat4j instead<br>Starting the solver...</html>",
            html(
                CommandDetails(listOf("The solver minisat is not available, using sat4j instead"), listOf(line(Span("Starting the solver...")))),
                Outdated.FileChanged,
            ),
        )
    }

    fun testFailureDetails() {
        assertEquals(
            "<html>model.als has errors, see the problems reported in the editor</html>",
            html(CommandDetails(failure = "model.als has errors, see the problems reported in the editor")),
        )
    }

    fun testFailureAfterTheLog() {
        assertEquals(
            "<html>&nbsp;&nbsp;&nbsp;Solving...<br>The analysis ran out of stack space</html>",
            html(CommandDetails(log = listOf(line(Span("   Solving..."))), failure = "The analysis ran out of stack space")),
        )
    }

    fun testLongLogKeepsItsEnd() {
        val log = (1..100).map { line(Span("line $it")) }
        val lines = html(CommandDetails(log = log)).removePrefix("<html>").removeSuffix("</html>").split("<br>")
        assertEquals(AlloyCommandResultTooltip.MAX_LOG_LINES + 1, lines.size)
        assertEquals("…", lines.first())
        assertEquals("line 61", lines[1])
        assertEquals("line 100", lines.last())
    }
}
