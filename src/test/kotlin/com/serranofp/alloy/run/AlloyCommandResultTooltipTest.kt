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

    private fun vacuity(vacuity: Vacuity, vacuityLog: List<Line> = emptyList()) = AlloyCommandResultTooltip.html(
        CommandResult(
            CommandState.NoCounterexample, "No counterexample found · 2 ms",
            CommandDetails(log = listOf(line(Span("   No counterexample found. Assertion may be valid. ", bold = true), Span("2ms."))), vacuityLog = vacuityLog),
            documentStamp = 0, settingsGeneration = 0, vacuity = vacuity,
        ),
        null,
    )

    private val check = "<b>&nbsp;&nbsp;&nbsp;No counterexample found. Assertion may be valid. </b>2ms.<br><br><b>Vacuity check</b><br>"

    fun testNoVacuitySectionWithoutAVacuityCheck() {
        assertFalse(html(CommandDetails()).contains("Vacuity check"))
    }

    fun testVacuityCheckRunning() {
        assertEquals("<html>${check}Checking whether the check passed vacuously…</html>", vacuity(Vacuity.Checking))
    }

    fun testVacuousFacts() {
        assertEquals(
            "<html>${check}The facts have no instance within the scope, so the check passed vacuously.<br>" +
                "Solved the facts: no instance found · 3 ms, unsat core of 2 top-level formulas</html>",
            vacuity(Vacuity.VacuousFacts(3, 2)),
        )
        assertTrue(vacuity(Vacuity.VacuousFacts(3, 0, "this/A")).contains("Solved the facts and the premise, then the facts: no instance found · 3 ms</html>"))
    }

    fun testVacuousPremise() {
        assertEquals(
            "<html>${check}The assertion&#39;s premise this/fairness has no instance within the scope, so the check passed vacuously.<br>" +
                "Solved the facts and the premise: no instance found · 5 ms, unsat core of 4 top-level formulas; the facts alone have an instance</html>",
            vacuity(Vacuity.VacuousPremise("this/fairness", 5, 4)),
        )
    }

    fun testNotVacuous() {
        assertEquals(
            "<html>${check}The facts and the premise this/A have an instance, so the check did not pass vacuously.<br>" +
                "Solved the facts and the premise: instance found · 1 ms</html>",
            vacuity(Vacuity.NotVacuous(1, "this/A")),
        )
        assertTrue(vacuity(Vacuity.NotVacuous(1)).contains("The facts have an instance, so the check did not pass vacuously.<br>Solved the facts: instance found · 1 ms"))
    }

    fun testUnknownVacuitySaysWhy() {
        assertEquals("<html>${check}Vacuity was not determined. The vacuity check was stopped.</html>", vacuity(Vacuity.Unknown(AlloyCommandExecution.VACUITY_STOPPED)))
        assertTrue(vacuity(Vacuity.Unknown("The analysis ran out of memory")).contains("Vacuity was not determined. The analysis ran out of memory.</html>"))
    }

    fun testVacuityLogWithoutBlankLines() {
        val log = listOf(line(Span("Starting the solver...")), line(), line(Span("Executing \"Check a for 3\"", bold = true)), line(), line(Span("   Instance found. ", bold = true), Span("1ms.")))
        assertTrue(
            vacuity(Vacuity.NotVacuous(1), log).endsWith(
                "Solved the facts: instance found · 1 ms<br><b>Executing &quot;Check a for 3&quot;</b><br><b>&nbsp;&nbsp;&nbsp;Instance found. </b>1ms.</html>",
            ),
        )
    }
}
