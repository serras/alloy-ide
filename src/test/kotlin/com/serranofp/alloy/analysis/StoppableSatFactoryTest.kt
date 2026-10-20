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

    fun testStoppingDestroysTheExternalSolverProcess() {
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
        while (solverProcesses() == 0L) {
            assertTrue("the solver process did not start: ${analysis.end} ${synchronized(events) { events.toList() }}", System.currentTimeMillis() < deadline)
            Thread.sleep(10)
        }
        analysis.stop()
        assertTrue(analysis.await(10, TimeUnit.SECONDS))
        assertInstanceOf(analysis.end, AlloyAnalysisEvent.Stopped::class.java)
        assertEquals(0L, solverProcesses())
    }
}
