package com.serranofp.alloy.run

import com.serranofp.alloy.analysis.AlloyAnalysisEvent
import com.serranofp.alloy.analysis.AlloyAnalysisEvent.Executing
import com.serranofp.alloy.analysis.AlloyAnalysisEvent.Failed
import com.serranofp.alloy.analysis.AlloyAnalysisEvent.Minimized
import com.serranofp.alloy.analysis.AlloyAnalysisEvent.Minimizing
import com.serranofp.alloy.analysis.AlloyAnalysisEvent.Sat
import com.serranofp.alloy.analysis.AlloyAnalysisEvent.Solving
import com.serranofp.alloy.analysis.AlloyAnalysisEvent.Stopped
import com.serranofp.alloy.analysis.AlloyAnalysisEvent.Stopping
import com.serranofp.alloy.analysis.AlloyAnalysisEvent.Translating
import com.serranofp.alloy.analysis.AlloyAnalysisEvent.Unsat
import com.serranofp.alloy.analysis.AlloyAnalysisEvent.Warning
import com.serranofp.alloy.analysis.AlloyFailure
import com.serranofp.alloy.run.AlloyRunLog.Outcome
import edu.mit.csail.sdg.alloy4.A4Reporter
import edu.mit.csail.sdg.alloy4.ErrorWarning
import edu.mit.csail.sdg.alloy4.Pair
import edu.mit.csail.sdg.alloy4.Pos
import edu.mit.csail.sdg.ast.Command
import edu.mit.csail.sdg.parser.CompUtil
import edu.mit.csail.sdg.translator.A4Options
import edu.mit.csail.sdg.translator.A4Solution
import edu.mit.csail.sdg.translator.TranslateAlloyToKodkod
import junit.framework.TestCase
import kodkod.engine.satlab.SATFactory

/**
 * The events of analyses, rendered as the 6.2.0 language server rendered the same runs: the expected texts are
 * those of the server's output, recorded before it was removed.
 */
class AlloyRunLogTest : TestCase() {
    private fun log(events: List<AlloyAnalysisEvent>) = AlloyRunLog().apply { events.forEach(::accept) }

    private fun translating(at: Long, bitwidth: Int = 4, maxSeq: Int = 3) =
        Translating("sat4j", bitwidth, maxSeq, -1, -1, 1, 20, "batch", at)

    private fun start(command: String) = listOf(executing(command, 0), translating(0))

    /** `run show for 3`, as in the recording: 29 ms to translate, 4 ms to solve. */
    private val showRun = start("show") + listOf(Solving(0, 12, 103, 149, 29), Sat(SAT, null, 33))

    /** `check noCycles for 3`: 5 ms to translate, 2 ms to solve. */
    private val noCyclesRun = start("noCycles") + listOf(Solving(0, 15, 212, 322, 5), Sat(SAT, null, 7))

    /** `run big for 30 but 7 int`, stopped while solving. */
    private val bigRun = listOf(executing("big", 0), translating(0, bitwidth = 7, maxSeq = 30), Solving(0, 930, 153632, 244106, 175), Stopping(500), Stopped(510))

    fun testInstanceFound() {
        val log = log(showRun)
        assertEquals(Outcome.InstanceFound, log.outcome)
        assertEquals(4L, log.timeMillis)
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

    /** The tooltip that the 6.2.0 language server's output for the same run gave, recorded before it was removed. */
    fun testSameTooltipAsTheLanguageServer() {
        assertEquals(
            "<html>Starting the solver...<br><br>" +
                "<b>Executing &quot;Run show for 3&quot;</b><br>" +
                "&nbsp;&nbsp;&nbsp;Solver=sat4j Bitwidth=4 MaxSeq=3 SkolemDepth=1 Symmetry=20 Mode=batch<br>" +
                "&nbsp;&nbsp;&nbsp;Generating CNF...<br>" +
                "&nbsp;&nbsp;&nbsp;103 vars. 12 primary vars. 149 clauses. 29ms.<br>" +
                "&nbsp;&nbsp;&nbsp;Solving...<br>" +
                "<b>&nbsp;&nbsp;&nbsp;Instance found. Predicate is consistent. </b>4ms.</html>",
            AlloyCommandResultTooltip.html(result(log(showRun)), null),
        )
    }

    private fun result(log: AlloyRunLog) =
        CommandResult(CommandState.InstanceFound, log.summary, CommandDetails(log = log.lines), documentStamp = 0, settingsGeneration = 0)

    fun testProgressFollowsTheAnalyzer() {
        val log = AlloyRunLog()
        showRun.take(1).forEach(log::accept)
        assertEquals("Executing \"Run show for 3\"", log.progress)
        log.accept(showRun[1])
        assertEquals("Generating CNF...", log.progress)
        log.accept(showRun[2])
        assertEquals("Solving...", log.progress)
        assertEquals(AlloyRunLog.RUNNING, log.summary)
    }

    fun testCounterexampleFound() {
        val log = log(noCyclesRun)
        assertEquals(Outcome.CounterexampleFound, log.outcome)
        assertEquals("Counterexample found · 2 ms", log.summary)
        assertEquals("Executing \"Check noCycles for 3\"", log.lines[2].text)
        assertEquals("   Counterexample found. Assertion is invalid. 2ms.", log.lines.last().text)
    }

    fun testStopped() {
        val log = log(bigRun)
        assertEquals(Outcome.None, log.outcome)
        assertNull(log.timeMillis)
        assertEquals("Stopped before completion", log.summary)
        assertEquals("Solving...", log.progress)
        assertEquals("Executing \"Run big for 30 but 7 int\"", log.lines[2].text)
        assertEquals("   Solver=sat4j Bitwidth=7 MaxSeq=30 SkolemDepth=1 Symmetry=20 Mode=batch", log.lines[3].text)
        assertEquals("   Solving...", log.lines.last().text)
    }

    /** `check acyclic for 3`: 5 ms to translate, then the result 3 ms after the solver starts. */
    private val acyclic = start("acyclic") + Solving(0, 15, 212, 322, 5)

    fun testUnsatWithoutCoreMinimization() {
        val log = log(acyclic + Unsat(UNSAT, NO_CORE, 8))
        assertEquals(Outcome.NoCounterexample, log.outcome)
        assertEquals("No counterexample found · 3 ms", log.summary)
        // The result replaces the "Solving..." line, but not the statistics before it
        assertEquals(
            listOf("   212 vars. 15 primary vars. 322 clauses. 5ms.", "   No counterexample found. Assertion may be valid. 3ms."),
            log.lines.takeLast(2).map { it.text },
        )
    }

    /** With core minimization: the result 3 ms after solving starts, then 7 ms of minimizing. */
    private fun minimized(core: Pair<Set<Pos>, Set<Pos>>, before: Int, after: Int) =
        log(acyclic + listOf(Minimizing(5, 8), Minimized(before, after, 14), Unsat(UNSAT, core, 15)))

    fun testUnsatWithCore() {
        val log = minimized(CORE, 2, 2)
        assertEquals(Outcome.NoCounterexample, log.outcome)
        assertEquals("No counterexample found · 3 ms", log.summary)
        assertEquals(
            listOf("   No counterexample found. Assertion may be valid. 3ms.", "   core contains 2 top-level formulas. 7ms."),
            log.lines.takeLast(2).map { it.text },
        )
    }

    fun testReducedCore() {
        val log = minimized(CORE, 5, 2)
        assertEquals("   core reduced from 5 to 2 top-level formulas. 7ms.", log.lines.last().text)
        assertEquals(3L, log.timeMillis)
    }

    fun testMinimizingIsAProgressLine() {
        val log = log(acyclic + Minimizing(5, 8))
        assertEquals("Minimizing the unsat core of 5 entries...", log.progress)
        assertEquals(
            listOf("   No counterexample found. Assertion may be valid. 3ms.", "   Minimizing the unsat core of 5 entries..."),
            log.lines.takeLast(2).map { it.text },
        )
        assertEquals(AlloyRunLog.RUNNING, log.summary)
    }

    fun testNoUnsatCoreAvailable() {
        val log = log(start("p", run = true) + listOf(Solving(0, 15, 212, 322, 5), Minimizing(5, 8), Minimized(5, 5, 8), Unsat(UNSAT, NO_CORE, 9)))
        assertEquals(Outcome.NoInstance, log.outcome)
        assertEquals("No instance found · 3 ms", log.summary)
        assertEquals(
            listOf("   No instance found. Predicate may be inconsistent. 3ms.", "   No unsat core is available in this case. 1ms."),
            log.lines.takeLast(2).map { it.text },
        )
    }

    fun testInstanceContraryToExpectation() {
        val log = log(start("p", run = true, expects = 0) + listOf(Solving(0, 15, 212, 322, 5), Sat(SAT, null, 9)))
        assertEquals(Outcome.InstanceFound, log.outcome)
        assertEquals("Instance found, contrary to expectation · 4 ms", log.summary)
        assertEquals("   Instance found. Predicate is consistent, contrary to expectation. 4ms.", log.lines.last().text)
    }

    fun testAsExpectedIsNotUnexpected() {
        val log = log(start("p", run = true, expects = 0) + listOf(Solving(0, 15, 212, 322, 5), Unsat(UNSAT, NO_CORE, 7)))
        assertEquals("No instance found, as expected · 2 ms", log.summary)
        assertEquals("   No instance found. Predicate may be inconsistent, as expected. 2ms.", log.lines.last().text)
    }

    fun testInstanceAsExpected() {
        val log = log(start("p", run = true, expects = 1) + listOf(Solving(0, 15, 212, 322, 5), Sat(SAT, null, 9)))
        assertEquals("Instance found, as expected · 4 ms", log.summary)
    }

    fun testNoInstanceContraryToExpectation() {
        val log = log(start("p", run = true, expects = 1) + listOf(Solving(0, 15, 212, 322, 5), Unsat(UNSAT, NO_CORE, 7)))
        assertEquals("No instance found, contrary to expectation · 2 ms", log.summary)
    }

    fun testNoCounterexampleAsExpected() {
        val log = log(start("a", run = false, expects = 0) + listOf(Solving(0, 15, 212, 322, 5), Unsat(UNSAT, NO_CORE, 7)))
        assertEquals("No counterexample found, as expected · 2 ms", log.summary)
    }

    fun testCounterexampleContraryToExpectation() {
        val log = log(start("a", run = false, expects = 0) + listOf(Solving(0, 15, 212, 322, 5), Sat(SAT, null, 9)))
        assertEquals("Counterexample found, contrary to expectation · 4 ms", log.summary)
    }

    fun testCounterexampleAsExpected() {
        val log = log(start("a", run = false, expects = 1) + listOf(Solving(0, 15, 212, 322, 5), Sat(SAT, null, 9)))
        assertEquals("Counterexample found, as expected · 4 ms", log.summary)
    }

    fun testNoCounterexampleContraryToExpectation() {
        val log = log(start("a", run = false, expects = 1) + listOf(Solving(0, 15, 212, 322, 5), Unsat(UNSAT, NO_CORE, 7)))
        assertEquals("No counterexample found, contrary to expectation · 2 ms", log.summary)
    }

    fun testWithoutExpectTheSummaryHasNoExpectation() {
        assertEquals("Instance found · 4 ms", log(start("p", run = true) + listOf(Solving(0, 15, 212, 322, 5), Sat(SAT, null, 9))).summary)
        assertEquals("No counterexample found · 2 ms", log(start("a", run = false) + listOf(Solving(0, 15, 212, 322, 5), Unsat(UNSAT, NO_CORE, 7))).summary)
    }

    fun testTemporalSolvingKeepsOneProgressLine() {
        val temporal = listOf(
            executing("p", 0, run = true),
            Translating("sat4j", 4, 3, 1, 10, 1, 20, "batch", 0),
            Solving(1, 10, 100, 200, 20),
            Solving(2, 12, 150, 300, 40),
            Solving(3, 14, 200, 400, 60),
            Sat(SAT, null, 65),
        )
        val lines = log(temporal).lines.map { it.text }
        assertEquals("   Solver=sat4j Steps=1..10 Bitwidth=4 MaxSeq=3 SkolemDepth=1 Symmetry=20 Mode=batch", lines[3])
        assertEquals(1, lines.count { it == "   Solving..." })
        assertEquals("   1..3 steps. 450 vars. 36 primary vars. 900 clauses. 60ms.", lines[lines.size - 3])
        assertEquals("   Instance found. Predicate is consistent. 5ms.", lines.last())
    }

    fun testWarnings() {
        val warning = ErrorWarning(Pos("m.als", 1, 1), "The join operation here always yields an empty set.")
        val log = log(listOf(Warning(warning, 0)) + showRun)
        val line = log.lines.single { it.text.startsWith("Warning") }
        assertEquals("Warning The join operation here always yields an empty set.", line.text)
        assertTrue(line.spans.single().warning)
        assertTrue(line.spans.single().bold)
        assertEquals(Outcome.InstanceFound, log.outcome)
    }

    fun testFailure() {
        val log = log(start("show") + Failed(AlloyFailure.StackExhausted, 10))
        assertEquals(AlloyRunLog.FAILED, log.summary)
    }

    private fun executing(name: String, at: Long, run: Boolean = false, expects: Int = -1): Executing =
        Executing(command(name, run, expects), at)

    private fun start(name: String, run: Boolean, expects: Int = -1) = listOf(executing(name, 0, run, expects), translating(0))

    private fun command(name: String, run: Boolean, expects: Int): Command {
        val keyword = if (run || name in listOf("show", "big")) "run" else "check"
        val scope = if (name == "big") "for 30 but 7 Int" else "for 3"
        val expect = if (expects >= 0) " expect $expects" else ""
        val body = if (keyword == "run") "pred $name {}" else "assert $name {}"
        return CompUtil.parseEverything_fromString(A4Reporter.NOP, "sig Node {}\n$body\n$keyword $name $scope$expect").allCommands.single()
    }

    companion object {
        private fun solve(model: String): A4Solution {
            val module = CompUtil.parseEverything_fromString(A4Reporter.NOP, model)
            val options = A4Options().apply { solver = SATFactory.DEFAULT }
            return TranslateAlloyToKodkod.execute_command(A4Reporter.NOP, module.allReachableSigs, module.allCommands.single(), options)
        }

        private val SAT: A4Solution by lazy { solve("sig A {}\nrun { some A } for 1") }
        private val UNSAT: A4Solution by lazy { solve("sig A {}\nrun { some A and no A } for 1") }

        private val NO_CORE = Pair<Set<Pos>, Set<Pos>>(emptySet(), emptySet())
        private val CORE = Pair<Set<Pos>, Set<Pos>>(setOf(Pos("m.als", 1, 1), Pos("m.als", 1, 2)), emptySet())
    }

    private fun holding(expects: Int = -1) = log(start("a", run = false, expects = expects) + listOf(Solving(0, 15, 212, 322, 5), Unsat(UNSAT, NO_CORE, 7)))

    fun testSummaryWhileCheckingVacuity() {
        assertEquals("No counterexample found · 2 ms, checking vacuity…", holding().summary(Vacuity.Checking))
    }

    fun testSummaryOfAVacuousPass() {
        assertEquals("No counterexample found (vacuous) · 2 ms", holding().summary(Vacuity.VacuousFacts(3, 0)))
        assertEquals("No counterexample found (vacuous) · 2 ms", holding().summary(Vacuity.VacuousPremise("this/A", 3, 2)))
        assertEquals("No counterexample found, as expected (vacuous) · 2 ms", holding(expects = 0).summary(Vacuity.VacuousFacts(3, 0)))
    }

    fun testSummaryOfANonVacuousOrUnknownPassIsUnchanged() {
        assertEquals("No counterexample found · 2 ms", holding().summary(Vacuity.NotVacuous(3)))
        assertEquals("No counterexample found · 2 ms", holding().summary(Vacuity.Unknown(AlloyCommandExecution.VACUITY_STOPPED)))
        assertEquals("No counterexample found · 2 ms", holding().summary(null))
        assertEquals(holding().summary, holding().summary(null))
    }

    /** Should the analyzer translate again, the first counts afterwards replace no line of the earlier block. */
    fun testASecondTranslationStartsANewBlock() {
        val log = log(start("show") + listOf(Solving(0, 12, 103, 149, 29), translating(30), Solving(0, 12, 103, 149, 40)))
        assertEquals(2, log.lines.count { it.text.startsWith("   Solver=") })
        assertEquals(2, log.lines.count { it.text == "   Solving..." })
    }

    fun testExpectReadBeforeTheAnalysis() {
        val log = AlloyRunLog()
        log.expect(1)
        assertEquals(1, log.expects)
        log.accept(executing("show", 0, run = true))
        assertEquals(-1, log.expects)
    }

    fun testCoreSizeOfAResultWithoutInstance() {
        assertEquals(0, holding().coreSize)
        assertEquals(2, log(start("a", run = false) + listOf(Solving(0, 15, 212, 322, 5), Unsat(UNSAT, CORE, 7))).coreSize)
        assertNull(log(showRun).coreSize)
    }
}
