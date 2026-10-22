package com.serranofp.alloy.run

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import com.serranofp.alloy.analysis.AlloyAnalysisEngine
import com.serranofp.alloy.analysis.AlloyAnalysisEvent
import edu.mit.csail.sdg.translator.A4Options
import edu.mit.csail.sdg.translator.TranslateAlloyToKodkod
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AlloyRunLaunchTest : BasePlatformTestCase() {
    private val service get() = AlloyRunService.getInstance(project)
    private val results get() = AlloyCommandResults.getInstance(project)
    private val show = AlloyCommandRef("show", 0)
    private val noCycles = AlloyCommandRef("noCycles", 1)

    override fun setUp() {
        super.setUp()
        results.clear()
        myFixture.configureByText("model.als", "sig Node { succ: lone Node }\npred show { some succ }\nrun show for 3\ncheck noCycles { no iden & succ } for 3\n")
    }

    override fun tearDown() {
        try {
            service.activeExecution?.terminate()
        } finally {
            super.tearDown()
        }
    }

    private val file get() = myFixture.file.virtualFile

    private fun executed(command: AlloyCommandRef = show) =
        results.get(file, command)!!.details.log.map { it.text }.firstOrNull { it.startsWith("Executing") }

    fun testLaunchStoresRunningThenTheResult() {
        assertTrue(service.launch(file, show, myFixture.editor))
        waitUntilIdle(project)

        val result = results.get(file, show)!!
        assertEquals(CommandState.InstanceFound, result.state)
        assertTrue(result.summary, result.summary.startsWith("Instance found · "))
        assertFalse(results.isOutdated(result, myFixture.editor.document))
        assertEquals("Executing \"Run show for 3\"", executed())
    }

    fun testCounterexample() {
        assertTrue(service.launch(file, noCycles, myFixture.editor))
        waitUntilIdle(project)
        assertEquals(CommandState.CounterexampleFound, results.get(file, noCycles)!!.state)
    }

    fun testUnsavedEditIsAnalyzed() {
        val document = myFixture.editor.document
        WriteCommandAction.runWriteCommandAction(project) {
            document.replaceString(document.text.indexOf("for 3"), document.text.indexOf("for 3") + 5, "for 4")
        }
        assertTrue(FileDocumentManager.getInstance().isFileModified(file))
        assertTrue(service.launch(file, show, myFixture.editor))
        waitUntilIdle(project)
        assertEquals("Executing \"Run show for 4\"", executed())
    }

    fun testUnsavedEditOfAnOpenedModuleIsAnalyzed() {
        val library = myFixture.addFileToProject("lib.als", "module lib\nsig Item {}")
        myFixture.configureByText("main.als", "open lib\npred show { some Item }\nrun show for 3\n")
        val main = myFixture.file.virtualFile
        val document = FileDocumentManager.getInstance().getDocument(library.virtualFile)!!
        WriteCommandAction.runWriteCommandAction(project) { document.setText("module lib\nsig Item {}\nfact { no Item }") }

        assertTrue(service.launch(main, show, myFixture.editor))
        waitUntilIdle(project)
        assertEquals(CommandState.NoInstance, results.get(main, show)!!.state)
    }

    fun testEditOfAnOpenedModuleOutdatesTheResult() {
        val library = myFixture.addFileToProject("lib.als", "module lib\nsig Item {}")
        myFixture.configureByText("main.als", "open lib\npred show { some Item }\nrun show for 3\n")
        val main = myFixture.file.virtualFile
        assertTrue(service.launch(main, show, myFixture.editor))
        waitUntilIdle(project)
        val result = results.get(main, show)!!
        assertEquals(setOf(library.virtualFile), result.modules)
        assertNull(results.outdated(result, myFixture.editor.document))

        val document = FileDocumentManager.getInstance().getDocument(library.virtualFile)!!
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(0, "// edit\n") }
        assertEquals(Outdated.FileChanged, results.outdated(result, myFixture.editor.document))
    }

    fun testCancellationDuringTheLaunchFreesTheProject() {
        val execution = newExecution(project, file, show)
        service.startRun(execution)
        val launcher = AlloyCommandLauncher(project, options = { throw ProcessCanceledException() })
        val running = ApplicationManager.getApplication().executeOnPooledThread<Throwable?> {
            runCatching { AlloyCommandTask(project, execution, launcher, testRootDisposable).run(EmptyProgressIndicator()) }.exceptionOrNull()
        }
        assertInstanceOf(running.get(10, TimeUnit.SECONDS), ProcessCanceledException::class.java)
        assertNull(service.activeExecution)
        assertEquals(CommandState.Stopped, results.get(file, show)!!.state)
    }

    fun testSecondLaunchWhileBusyLeavesTheStoreUnchanged() {
        val first = newExecution(project, file, show)
        service.startRun(first)
        val before = results.results(file)

        assertFalse(service.launch(file, noCycles, myFixture.editor))
        assertSame(first, service.activeExecution)
        assertEquals(before, results.results(file))
        assertNull(results.get(file, noCycles))
    }

    /** Runs a slow `run show` as the background task, on [indicator]; returns once it is solving. */
    private fun startSlowTask(indicator: ProgressIndicator): Pair<AlloyCommandExecution, java.util.concurrent.Future<*>> {
        myFixture.configureByText("model.als", PIGEONHOLE)
        val execution = newExecution(project, myFixture.file.virtualFile, show)
        service.startRun(execution)
        val task = AlloyCommandTask(project, execution, AlloyCommandLauncher(project, options = ::slowOptions), testRootDisposable)
        val running = ApplicationManager.getApplication().executeOnPooledThread { task.run(indicator) }
        waitUntil("the analysis did not start solving") { execution.log.progress == "Solving..." }
        return execution to running
    }

    fun testCancellingTheIndicatorStopsTheAnalysis() {
        val indicator = EmptyProgressIndicator()
        val (execution, running) = startSlowTask(indicator)
        indicator.cancel()
        running.get(10, TimeUnit.SECONDS)
        UIUtil.dispatchAllInvocationEvents()

        assertTrue(execution.isFinished())
        assertEquals(CommandState.Stopped, results.get(myFixture.file.virtualFile, show)!!.state)
        assertNull(service.activeExecution)
    }

    fun testEditDuringARunOutdatesItsResult() {
        val indicator = EmptyProgressIndicator()
        val (execution, running) = startSlowTask(indicator)
        myFixture.type("x")
        execution.stop()
        running.get(10, TimeUnit.SECONDS)
        val result = results.get(myFixture.file.virtualFile, show)!!
        assertEquals(Outdated.FileChanged, results.outdated(result, myFixture.editor.document))
    }

    fun testIndicatorFollowsTheAnalyzer() {
        val indicator = ProgressIndicatorBase()
        val (execution, running) = startSlowTask(indicator)
        waitUntil("the indicator did not follow") { indicator.text2 == "Solving..." }
        execution.stop()
        running.get(10, TimeUnit.SECONDS)
    }

    fun testTaskIsNamedAfterTheCommand() {
        val execution = newExecution(project, file, show)
        assertEquals("Alloy: Run show", AlloyCommandTask(project, execution, AlloyCommandLauncher(project), testRootDisposable).title)
    }

    private val check = AlloyCommandRef("a", 0)

    /** Launches the command [ref] of [model] from the gutter and waits for its whole execution. */
    private fun launched(model: String, ref: AlloyCommandRef = check, vacuity: Boolean = true): CommandResult {
        myFixture.configureByText("model.als", model)
        assertTrue(service.launch(file, ref, myFixture.editor, AlloyCommandLauncher(project, checkVacuity = { vacuity })))
        waitUntilIdle(project)
        return results.get(file, ref)!!
    }

    private fun executedQueries(result: CommandResult) = result.details.vacuityLog.count { it.text.startsWith("Executing") }

    fun testContradictoryFactsAreVacuousFacts() {
        val result = launched("sig Node {}\nfact { some Node }\nfact { no Node }\nassert a { all n: Node | some n.~iden implies no n }\ncheck a for 3")
        assertEquals(CommandState.NoCounterexample, result.state)
        assertInstanceOf(result.vacuity, Vacuity.VacuousFacts::class.java)
        assertEquals(Verdict.Vacuous, verdict(result))
        assertTrue(result.summary, result.summary.startsWith("No counterexample found (vacuous) · "))
        // The facts and the premise, then the facts alone
        assertEquals(2, executedQueries(result))
    }

    fun testContradictoryFactsWithoutPremise() {
        val result = launched("sig Node {}\nfact { some Node }\nfact { no Node }\nassert a { some Node or no Node }\ncheck a for 3")
        assertInstanceOf(result.vacuity, Vacuity.VacuousFacts::class.java)
        assertEquals(1, executedQueries(result))
    }

    /**
     * Practical Alloy's leader election on a single-node ring, where the effects of `process` contradict each
     * other: the only traces stutter, so no trace is fair, and liveness holds vacuously.
     */
    fun testSingleNodeRingIsVacuousPremise() {
        val ring = """
            sig Node { succ: one Node, var inbox: set Node }
            var sig Elected in Node {}
            fact ring { all n: Node | Node in n.^succ }
            fact init { no Elected and all n: Node | n.inbox = n }
            pred process[n, i: Node] {
              i in n.inbox
              n.inbox' = n.inbox - i
              n.succ.inbox' = n.succ.inbox + i
              all m: Node - n - n.succ | m.inbox' = m.inbox
              n = i implies Elected' = Elected + n else Elected' = Elected
            }
            pred stutter { inbox' = inbox and Elected' = Elected }
            fact traces { always (stutter or some n, i: Node | process[n, i]) }
            pred fairness { all n, i: Node | (eventually always i in n.inbox) implies (always eventually process[n, i]) }
            check liveness { fairness implies eventually some Elected } for exactly 1 Node
        """.trimIndent()
        val result = launched(ring, AlloyCommandRef("liveness", 0))
        assertEquals(CommandState.NoCounterexample, result.state)
        val vacuity = result.vacuity as Vacuity.VacuousPremise
        assertTrue(vacuity.premiseText, vacuity.premiseText.contains("fairness"))
        assertEquals(2, executedQueries(result))
    }

    fun testHoldingCheckIsNotVacuous() {
        val model = "sig Node { succ: lone Node }\nfact acyclic { no n: Node | n in n.^succ }\nassert noCycles { no iden & succ }\ncheck noCycles for 3"
        val result = launched(model, AlloyCommandRef("noCycles", 0))
        assertEquals(CommandState.NoCounterexample, result.state)
        assertInstanceOf(result.vacuity, Vacuity.NotVacuous::class.java)
        assertEquals(1, executedQueries(result))
        assertEquals(Verdict.Run, verdict(result))
    }

    fun testHoldingPremiseIsNotVacuousAfterOneQuery() {
        val model = "sig Node { succ: lone Node }\nfact acyclic { no n: Node | n in n.^succ }\n" +
            "assert a { all n: Node | some n.succ implies n.succ != n }\ncheck a for 3 expect 0"
        val result = launched(model)
        assertInstanceOf(result.vacuity, Vacuity.NotVacuous::class.java)
        assertEquals(1, executedQueries(result))
        assertEquals(Verdict.Passed, verdict(result))
    }

    fun testSettingOffStartsNoVacuityCheck() {
        val model = "sig Node {}\nfact { some Node }\nfact { no Node }\nassert a { no Node }\ncheck a for 3"
        val result = launched(model, vacuity = false)
        assertEquals(CommandState.NoCounterexample, result.state)
        assertNull(result.vacuity)
        assertEmpty(result.details.vacuityLog)
    }

    fun testCounterexampleStartsNoVacuityCheck() {
        val result = launched("sig Node { succ: lone Node }\nassert a { no succ }\ncheck a for 3")
        assertEquals(CommandState.CounterexampleFound, result.state)
        assertNull(result.vacuity)
    }

    fun testRunWithoutInstanceStartsNoVacuityCheck() {
        val result = launched("sig Node {}\npred a { some Node and no Node }\nrun a for 3")
        assertEquals(CommandState.NoInstance, result.state)
        assertNull(result.vacuity)
    }

    /**
     * Executes the check of [model] as a launch does, with a follow-up that waits for [gate] before solving
     * its query, or [fails] instead.
     */
    private fun executeWithGatedFollowUp(model: String, gate: CountDownLatch, fails: Boolean = false): AlloyCommandExecution {
        myFixture.configureByText("model.als", model)
        val execution = newExecution(project, file, check)
        assertTrue(service.startRun(execution))
        val engine = AlloyAnalysisEngine.getInstance()
        execution.begin(
            { engine.execute("a", testRootDisposable, mapOf("/model.als" to model), "/model.als", 0, A4Options(), execution::accept) },
        ) { sigs, query, listener ->
            engine.start("a (vacuity)", testRootDisposable, A4Options(), listener) { reporter, options ->
                if (fails) throw OutOfMemoryError("test")
                gate.await()
                TranslateAlloyToKodkod.execute_command(reporter, sigs, query, options)
            }
        }
        if (!fails) waitUntil("the vacuity check did not start") { results.get(file, check)?.vacuity == Vacuity.Checking }
        return execution
    }

    private val holding = "sig Node { succ: lone Node }\nfact acyclic { no n: Node | n in n.^succ }\nassert a { no iden & succ }\ncheck a for 3"

    fun testCheckResultIsShownDuringTheVacuityCheck() {
        val gate = CountDownLatch(1)
        val execution = executeWithGatedFollowUp(holding, gate)
        val during = results.get(file, check)!!
        assertEquals(CommandState.NoCounterexample, during.state)
        assertTrue(during.summary, during.summary.startsWith("No counterexample found · "))
        assertFalse(execution.isFinished())
        assertSame(execution, service.activeExecution)
        assertEquals("Checking vacuity…", execution.progress)
        assertTrue(during.summary, during.summary.endsWith(", checking vacuity…"))

        gate.countDown()
        assertTrue(execution.waitFor(30_000))
        assertInstanceOf(results.get(file, check)!!.vacuity, Vacuity.NotVacuous::class.java)
        assertNull(service.activeExecution)
        assertFalse(results.get(file, check)!!.summary.contains("vacuity"))
    }

    fun testLaunchDuringTheVacuityCheckIsRefused() {
        val gate = CountDownLatch(1)
        val execution = executeWithGatedFollowUp(holding, gate)
        try {
            val before = results.results(file)
            assertFalse(service.launch(file, AlloyCommandRef(null, 5), myFixture.editor))
            assertEquals(before, results.results(file))
        } finally {
            gate.countDown()
            execution.waitFor(30_000)
        }
    }

    fun testStoppingTheVacuityCheckKeepsTheCheckResult() {
        val execution = executeWithGatedFollowUp(holding, CountDownLatch(1))
        val summary = results.get(file, check)!!.summary.removeSuffix(", checking vacuity…")
        execution.stop()
        assertTrue(execution.waitFor(30_000))
        val result = results.get(file, check)!!
        assertEquals(CommandState.NoCounterexample, result.state)
        assertEquals(summary, result.summary)
        assertTrue(summary, summary.matches(Regex("No counterexample found · \\d+ ms")))
        assertEquals(Vacuity.Unknown(AlloyCommandExecution.VACUITY_STOPPED), result.vacuity)
        assertEquals(Verdict.Run, verdict(result))
        assertNull(service.activeExecution)
    }

    /**
     * The check ends without counterexample between the stop reading its analysis and stopping it, so that
     * stopping that analysis does nothing: the vacuity check must not start in its place.
     */
    fun testStopRacingTheEndOfTheCheckStartsNoVacuityCheck() {
        myFixture.configureByText("model.als", holding)
        val execution = newExecution(project, file, check)
        assertTrue(service.startRun(execution))
        val engine = AlloyAnalysisEngine.getInstance()
        val events = CopyOnWriteArrayList<AlloyAnalysisEvent>()
        val ended = engine.execute("a", testRootDisposable, mapOf("/model.als" to holding), "/model.als", 0, A4Options()) { events += it }
        assertTrue(ended.await(30, TimeUnit.SECONDS))
        val gate = CountDownLatch(1)
        try {
            execution.begin({ ended }) { sigs, query, listener ->
                engine.start("a (vacuity)", testRootDisposable, A4Options(), listener) { reporter, options ->
                    gate.await()
                    TranslateAlloyToKodkod.execute_command(reporter, sigs, query, options)
                }
            }
            execution.stop()
            events.forEach(execution::accept)
            assertTrue(execution.isFinished())
            val result = results.get(file, check)!!
            assertEquals(CommandState.NoCounterexample, result.state)
            assertNull(result.vacuity)
            assertNull(service.activeExecution)
        } finally {
            gate.countDown()
        }
    }

    fun testTerminatingTheVacuityCheckKeepsTheCheckResult() {
        val execution = executeWithGatedFollowUp(holding, CountDownLatch(1))
        execution.terminate()
        val result = results.get(file, check)!!
        assertEquals(CommandState.NoCounterexample, result.state)
        assertEquals(Vacuity.Unknown(AlloyCommandExecution.VACUITY_STOPPED), result.vacuity)
        assertNull(service.activeExecution)
    }

    fun testFailingVacuityCheckKeepsTheCheckResult() {
        val execution = executeWithGatedFollowUp(holding, CountDownLatch(1), fails = true)
        assertTrue(execution.waitFor(30_000))
        val result = results.get(file, check)!!
        assertEquals(CommandState.NoCounterexample, result.state)
        assertTrue(result.summary, result.summary.startsWith("No counterexample found · "))
        assertEquals(Vacuity.Unknown("The analysis ran out of memory"), result.vacuity)
        assertNull(result.details.failure)
    }

    fun testEditDuringTheVacuityCheckOutdatesItsConclusion() {
        val gate = CountDownLatch(1)
        val execution = executeWithGatedFollowUp(holding, gate)
        myFixture.type("x")
        gate.countDown()
        assertTrue(execution.waitFor(30_000))
        val result = results.get(file, check)!!
        assertInstanceOf(result.vacuity, Vacuity.NotVacuous::class.java)
        assertEquals(Outdated.FileChanged, results.outdated(result, myFixture.editor.document))
    }
}
