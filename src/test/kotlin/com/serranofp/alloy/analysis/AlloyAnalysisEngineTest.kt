package com.serranofp.alloy.analysis

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.serranofp.alloy.analysis.AlloyAnalysis.State
import com.serranofp.alloy.analysis.AlloyAnalysisEvent.End
import com.serranofp.alloy.analysis.AlloyAnalysisEvent.Executing
import com.serranofp.alloy.analysis.AlloyAnalysisEvent.Failed
import com.serranofp.alloy.analysis.AlloyAnalysisEvent.Sat
import com.serranofp.alloy.analysis.AlloyAnalysisEvent.Solving
import com.serranofp.alloy.analysis.AlloyAnalysisEvent.Stopped
import com.serranofp.alloy.analysis.AlloyAnalysisEvent.Stopping
import com.serranofp.alloy.analysis.AlloyAnalysisEvent.Translating
import com.serranofp.alloy.analysis.AlloyAnalysisEvent.Unsat
import com.serranofp.alloy.analysis.AlloyAnalysisEvent.Warning
import edu.mit.csail.sdg.alloy4.A4Reporter
import edu.mit.csail.sdg.alloy4.ErrorFatal
import edu.mit.csail.sdg.parser.CompUtil
import edu.mit.csail.sdg.translator.A4Options
import edu.mit.csail.sdg.translator.TranslateAlloyToKodkod
import kodkod.engine.satlab.SATFactory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class AlloyAnalysisEngineTest : BasePlatformTestCase() {
    private val engine get() = AlloyAnalysisEngine.getInstance()

    private val events = CopyOnWriteArrayList<AlloyAnalysisEvent>()

    private fun options() = A4Options().apply { solver = SATFactory.DEFAULT }

    /** Executes the first command of [model]. */
    private fun execute(model: String, options: A4Options = options(), parent: com.intellij.openapi.Disposable = testRootDisposable): AlloyAnalysis {
        val module = CompUtil.parseEverything_fromString(A4Reporter.NOP, model)
        return engine.execute("test", parent, module.allReachableSigs, module.allCommands.first(), options) { events += it }
    }

    private fun AlloyAnalysis.finish(): End {
        assertTrue("the analysis did not end", await(60, TimeUnit.SECONDS))
        assertEquals(State.Done, state)
        return end!!
    }

    private inline fun <reified T : AlloyAnalysisEvent> waitFor() {
        val deadline = System.currentTimeMillis() + 30_000
        while (events.none { it is T }) {
            assertTrue("no ${T::class.simpleName} event", System.currentTimeMillis() < deadline)
            Thread.sleep(10)
        }
    }

    fun testRunCompletesWithAnInstance() {
        val end = execute("sig Node { succ: lone Node }\nrun show { some succ } for 3").finish()
        assertInstanceOf(end, Sat::class.java)
        assertTrue((end as Sat).solution.satisfiable())

        assertEquals("Run show for 3", (events.first() as Executing).command.toString())
        val translating = events.filterIsInstance<Translating>().single()
        assertEquals("sat4j", translating.solver)
        assertEquals(4, translating.bitwidth)
        assertTrue(events.filterIsInstance<Solving>().single().clauses > 0)
        assertSame(end, events.last())
    }

    fun testParsedModuleIsKeptWithTheSolution() {
        val model = "sig Node { succ: lone Node }\npred show { some succ }\nrun show for 3"
        val end = engine.execute("test", testRootDisposable, mapOf("/m.als" to model), "/m.als", 0, options()) { events += it }.finish()
        assertInstanceOf(end, Sat::class.java)
        val module = (end as Sat).module!!
        assertTrue(module.allFunc.any { it.label == "this/show" })
    }

    fun testGivenSigsHaveNoModule() {
        val end = execute("sig Node {}\nrun { some Node } for 3").finish()
        assertNull((end as Sat).module)
        assertNull((events.first() as Executing).module)
    }

    fun testExecutingCarriesTheParsedModule() {
        val model = "sig Node { succ: lone Node }\nsig Leaf extends Node {}\nfact acyclic { no n: Node | n in n.^succ }\n" +
            "assert noLoops { no n: Node | n = n.succ }\ncheck noLoops for 3"
        val end = engine.execute("test", testRootDisposable, mapOf("/m.als" to model), "/m.als", 0, options()) { events += it }.finish()
        assertInstanceOf(end, Unsat::class.java)
        val executing = events.filterIsInstance<Executing>().single()
        val module = executing.module!!
        assertEquals(listOf("this/Node", "this/Leaf"), module.allReachableSigs.filter { !it.builtin }.map { it.label })
        // The facts of the file, as a separate parse of it gives them
        val parsed = CompUtil.parseEverything_fromString(A4Reporter.NOP, model)
        assertEquals(parsed.allReachableFacts.toString(), module.allReachableFacts.toString())
        assertTrue(module.allReachableFacts.toString(), module.allReachableFacts.toString().contains("^"))
        assertSame(module.allCommands.single(), executing.command)
    }

    fun testValidCheckCompletesWithoutCounterexample() {
        val end = execute("sig Node { succ: lone Node }\ncheck functional { all n: Node | lone n.succ } for 3").finish()
        assertInstanceOf(end, Unsat::class.java)
        assertFalse((end as Unsat).solution.satisfiable())
        assertTrue(end.core.a.isEmpty() && end.core.b.isEmpty())
    }

    fun testWarningsAreEvents() {
        // The analyzer reports warnings while it type-checks, so the model is parsed in the analysis
        val analysis = engine.start("test", testRootDisposable, options(), { events += it }) { reporter, options ->
            val module = CompUtil.parseEverything_fromString(reporter, "sig A {}\nrun { some A & none } for 3")
            TranslateAlloyToKodkod.execute_command(reporter, module.allReachableSigs, module.allCommands.first(), options)
        }
        val end = analysis.finish()
        assertInstanceOf(end, Unsat::class.java)
        assertTrue(events.filterIsInstance<Warning>().single().warning.msg.contains("irrelevant"))
    }

    fun testStopDuringSolving() {
        val analysis = execute(PIGEONHOLE, options().apply { symmetry = 0 })
        waitFor<Solving>()
        Thread.sleep(200)
        val requested = System.currentTimeMillis()
        analysis.stop()
        val end = analysis.finish()
        assertInstanceOf(end, Stopped::class.java)
        assertTrue("stopping took ${end.timeMillis - requested} ms", end.timeMillis - requested < 2_000)
        assertEquals(listOf(Stopping::class.java, Stopped::class.java), events.takeLast(2).map { it.javaClass })
    }

    fun testStopDuringTranslationIsStoppingFirst() {
        val analysis = execute(HEAVY_TRANSLATION)
        Thread.sleep(300)
        assertEquals(State.Running, analysis.state)
        analysis.stop()
        // The translation does not reach a checkpoint for seconds
        assertEquals(State.Stopping, analysis.state)
        assertInstanceOf(events.last(), Stopping::class.java)
        assertNull(analysis.end)

        assertInstanceOf(analysis.finish(), Stopped::class.java)
        assertEquals(1, events.count { it is Stopping })
        assertNoneSolved()
    }

    fun testStopJustAfterTheResultKeepsIt() {
        val module = CompUtil.parseEverything_fromString(A4Reporter.NOP, "sig Node {}\nrun { some Node } for 3")
        val solved = java.util.concurrent.CountDownLatch(1)
        val stopped = java.util.concurrent.atomic.AtomicBoolean()
        val analysis = engine.start("test", testRootDisposable, options(), { events += it }) { reporter, options ->
            val solution = TranslateAlloyToKodkod.execute_command(A4Reporter.NOP, module.allReachableSigs, module.allCommands.first(), options)
            solved.countDown()
            // Stop interrupts this thread, so no blocking wait
            while (!stopped.get()) Thread.onSpinWait()
            Thread.interrupted()
            // As the analyzer reports the solver's answer, once the stop is requested
            reporter.resultSAT(null, 0, solution)
            solution
        }
        assertTrue(solved.await(30, TimeUnit.SECONDS))
        analysis.stop()
        stopped.set(true)
        assertInstanceOf(analysis.finish(), Sat::class.java)
    }

    fun testStopAfterTheEndDoesNothing() {
        val analysis = execute("sig A {}\nrun {} for 1")
        val end = analysis.finish()
        analysis.stop()
        assertSame(end, analysis.end)
        assertEquals(0, events.count { it is Stopping })
    }

    fun testStackOverflowIsStackExhausted() {
        fun deep(n: Int): Int = deep(n + 1) + 1
        val analysis = engine.start("test", testRootDisposable, options(), { events += it }) { _, _ -> deep(0); null }
        val end = analysis.finish() as Failed
        assertEquals(AlloyFailure.StackExhausted, end.failure)
        assertEquals("The analysis ran out of stack space", end.failure.message)
    }

    fun testWrappedOutOfMemoryIsMemoryExhausted() {
        // As execute_command reports it
        val analysis = engine.start("test", testRootDisposable, options(), { events += it }) { _, _ ->
            throw ErrorFatal("Unknown exception occurred: java.lang.OutOfMemoryError", OutOfMemoryError("Java heap space"))
        }
        assertEquals(AlloyFailure.MemoryExhausted, (analysis.finish() as Failed).failure)
    }

    fun testExceptionIsReportedWithItsMessage() {
        val analysis = engine.start("test", testRootDisposable, options(), { events += it }) { _, _ ->
            throw ErrorFatal("Unknown exception occurred: java.lang.IllegalStateException: solver exploded", IllegalStateException("solver exploded"))
        }
        val failure = (analysis.finish() as Failed).failure
        assertInstanceOf(failure, AlloyFailure.UnexpectedException::class.java)
        assertEquals("solver exploded", failure.message)
    }

    fun testAnalyzerErrorIsReportedWithItsMessage() {
        val end = execute("sig A { r: set A }\nrun { all s: set A | s in A.r } for 3").finish() as Failed
        assertInstanceOf(end.failure, AlloyFailure.AnalyzerError::class.java)
        assertTrue(end.failure.message, end.failure.message.contains("higher-order"))
    }

    fun testDisposingTheParentStopsTheAnalysis() {
        val project = Disposer.newDisposable("project")
        Disposer.register(testRootDisposable, project)
        val analysis = execute(PIGEONHOLE, options().apply { symmetry = 0 }, parent = project)
        waitFor<Solving>()
        Disposer.dispose(project)
        assertInstanceOf(analysis.finish(), Stopped::class.java)
    }

    fun testParentAlreadyDisposed() {
        val project = Disposer.newDisposable("project")
        Disposer.dispose(project)
        val analysis = execute("sig A {}\nrun {} for 1", parent = project)
        assertInstanceOf(analysis.finish(), Stopped::class.java)
        assertNoneSolved()
    }

    fun testAnalysisRunsOnItsOwnThread() {
        var name: String? = null
        engine.start("own thread", testRootDisposable, options(), { events += it }) { _, _ -> name = Thread.currentThread().name; null }.finish()
        assertEquals("Alloy analysis: own thread", name)
    }

    private fun assertNoneSolved() = assertTrue(events.none { it is Sat || it is Unsat })

    companion object {
        /** More than 20 s of solving without symmetry breaking. */
        const val PIGEONHOLE = "sig H {}\nsig P { h: one H }\nfact { all disj a, b: P | a.h != b.h }\nrun php { #P = 11 and #H = 10 } for 11"

        /** Several seconds of translation, without any analyzer callback. */
        const val HEAVY_TRANSLATION = "sig A { r: A -> A }\nrun big { all a, b, c, d: A | a->b->c in r implies (b->c->d in r or c->d->a in r) } for 32"
    }
}
