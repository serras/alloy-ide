package com.serranofp.alloy.analysis

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import edu.mit.csail.sdg.alloy4.A4Reporter
import edu.mit.csail.sdg.parser.CompUtil
import edu.mit.csail.sdg.translator.A4Options
import kodkod.engine.satlab.ExternalSolver
import kodkod.engine.satlab.SATFactory
import kodkod.engine.satlab.SATSolver
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class StoppableSatFactoryTest : BasePlatformTestCase() {
    fun testEveryBundledSolverHasAStoppableHandle() {
        val ids = SATFactory.getAllSolvers().filter { it.isPresent }.map { it.id() }
        assertEquals(listOf("sat4j", "sat4j.light", "sat4j.pmax"), ids.sorted())
        for (factory in SATFactory.getAllSolvers().filter { it.isPresent }) {
            assertNotNull(factory.id(), StoppableSatFactory.sat4jSolver(factory.instance()))
        }
    }

    fun testSameIdentityAsTheOriginal() {
        val stoppable = StoppableSatFactory(SATFactory.DEFAULT)
        assertEquals("sat4j", stoppable.id())
        assertEquals(SATFactory.DEFAULT, stoppable)
        assertTrue(stoppable.incremental())
        assertEquals("java", stoppable.type())
    }

    fun testSolvesLikeTheOriginal() {
        for (id in listOf("sat4j", "sat4j.light", "sat4j.pmax")) {
            val solver = StoppableSatFactory(SATFactory.get(id)).instance()
            solver.addVariables(1)
            solver.addClause(intArrayOf(1))
            assertTrue(id, solver.solve())
        }
    }

    /**
     * A stand-in external solver: a script that never answers, as a solver busy solving. It blocks in the
     * `read` builtin, so that the process to destroy is the script itself, with no child.
     */
    private class FakeExternal : SATFactory() {
        override fun id() = "fakesolver"
        override fun type() = "external"
        override fun getExecutables() = arrayOf("fakesolver")
        override fun createSolver(): SATSolver = ExternalSolver("fakesolver", null, true)
    }

    private fun solverProcesses() = ProcessHandle.current().descendants().filter {
        it.info().arguments().orElse(emptyArray()).firstOrNull()?.endsWith("/fakesolver") == true
    }.count()

    override fun tearDown() {
        try {
            System.clearProperty("alloy.native.exe.fakesolver")
        } finally {
            super.tearDown()
        }
    }

    /** An analysis whose solver is [FakeExternal], once its process runs. */
    private fun externalAnalysis(running: Long): AlloyAnalysis {
        val dir = Files.createTempDirectory("alloy-fake-solver")
        val executable = Files.writeString(dir.resolve("fakesolver"), "#!/bin/sh\nread -r answer\n")
        executable.toFile().setExecutable(true)
        // Where pardinus looks for an executable before the PATH
        System.setProperty("alloy.native.exe.fakesolver", executable.toString())
        val options = A4Options().apply { solver = FakeExternal() }
        val module = CompUtil.parseEverything_fromString(A4Reporter.NOP, "sig Node { succ: lone Node }\nrun { some succ and no iden & succ } for 3")
        val events = mutableListOf<AlloyAnalysisEvent>()
        val analysis = AlloyAnalysisEngine.getInstance().execute("external", testRootDisposable, module.allReachableSigs, module.allCommands.first(), options) {
            synchronized(events) { events += it }
        }
        val deadline = System.currentTimeMillis() + 30_000
        while (solverProcesses() < running) {
            assertTrue("the solver process did not start: ${analysis.end} ${synchronized(events) { events.toList() }}", System.currentTimeMillis() < deadline)
            Thread.sleep(10)
        }
        return analysis
    }

    fun testStoppingDestroysTheExternalSolverProcess() {
        val analysis = externalAnalysis(running = 1)
        analysis.stop()
        assertTrue(analysis.await(10, TimeUnit.SECONDS))
        assertInstanceOf(analysis.end, AlloyAnalysisEvent.Stopped::class.java)
        assertEquals(0L, solverProcesses())
    }

    fun testStoppingLeavesTheProcessesOfOtherAnalyses() {
        val first = externalAnalysis(running = 1)
        val second = externalAnalysis(running = 2)
        second.stop()
        assertTrue(second.await(10, TimeUnit.SECONDS))
        assertEquals(1L, solverProcesses())
        assertNull(first.end)
        first.stop()
        assertTrue(first.await(10, TimeUnit.SECONDS))
        assertEquals(0L, solverProcesses())
    }

    /** A factory that chooses another one for some options, as `doOptions` allows. */
    private class Choosing(private val chosen: SATFactory) : SATFactory() {
        override fun id() = "choosing"
        override fun type() = "java"
        override fun createSolver(): SATSolver = chosen.instance()
        override fun doOptions(options: kodkod.engine.config.ExtendedOptions): SATFactory = chosen
    }

    /** Starts a process of [script] for each solver, as pardinus does for solvers with no CNF file, such as Electrod. */
    private class Spawning(private val script: String) : SATFactory() {
        override fun id() = "spawning"
        override fun type() = "external"
        override fun getExecutables() = arrayOf("fakesolver")
        override fun createSolver(): SATSolver {
            ProcessBuilder(script).start()
            return DEFAULT.instance()
        }
    }

    fun testFactoriesChosenForOptionsStopWithTheOriginal() {
        val script = Files.writeString(Files.createTempDirectory("alloy-fake-solver").resolve("fakesolver"), "#!/bin/sh\nread -r answer\n")
        script.toFile().setExecutable(true)
        System.setProperty("alloy.native.exe.fakesolver", script.toString())
        // The original has no executables of its own: the one it chooses has
        val stoppable = StoppableSatFactory(Choosing(Spawning(script.toString())))
        val chosen = stoppable.doOptions(kodkod.engine.config.ExtendedOptions())
        assertNotSame(stoppable, chosen)
        chosen.instance()
        val deadline = System.currentTimeMillis() + 30_000
        while (solverProcesses() == 0L) {
            assertTrue("the solver process did not start", System.currentTimeMillis() < deadline)
            Thread.sleep(10)
        }
        stoppable.stop()
        while (solverProcesses() > 0L) {
            assertTrue("the solver process was not destroyed", System.currentTimeMillis() < deadline)
            Thread.sleep(10)
        }
    }
}
