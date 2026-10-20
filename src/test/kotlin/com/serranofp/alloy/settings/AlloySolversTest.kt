package com.serranofp.alloy.settings

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class AlloySolversTest : BasePlatformTestCase() {
    private val properties = listOf("alloy.native.exe.yices", "alloy.native.exe.plingeling")
    private val saved = properties.associateWith { System.getProperty(it) }

    override fun setUp() {
        super.setUp()
        properties.forEach(System::clearProperty)
    }

    override fun tearDown() {
        try {
            for ((property, value) in saved) {
                if (value == null) System.clearProperty(property) else System.setProperty(property, value)
            }
        } finally {
            super.tearDown()
        }
    }

    /** A stand-in for an external solver's executable; listing a solver does not run it. */
    private fun executable(name: String): String {
        val path = Files.writeString(Files.createTempDirectory("alloy-solvers").resolve(name), "#!/bin/sh\n")
        path.toFile().setExecutable(true)
        return path.toString()
    }

    fun testWithoutExecutablesOnlySat4j() {
        val ids = AlloySolvers.findSolvers { null }.map { it.id }
        assertEquals(listOf("sat4j", "sat4j.light", "sat4j.pmax"), ids.sorted())
    }

    fun testExternalSolverOnThePath() {
        val yices = executable("yices")
        val ids = AlloySolvers.findSolvers { if (it == "yices") yices else null }.map { it.id }
        assertTrue(ids.toString(), "yices.external" in ids)
        assertTrue(ids.containsAll(listOf("sat4j", "sat4j.light", "sat4j.pmax")))
        assertEquals("yices.external", AlloySolvers.factory("yices.external") { null }!!.id())
    }

    fun testNativeAndFileOnlySolversAreNeverListed() {
        val anything = executable("anything")
        val ids = AlloySolvers.findSolvers { anything }.map { it.id }
        // JNI libraries could crash the IDE; electrod.elo only writes its input to a file
        assertFalse(ids.toString(), ids.any { it in setOf("minisat", "minisat.prover", "glucose", "electrod.elo") })
    }

    fun testExecutableNotOnThePathIsNotTakenFromTheJar() {
        val ids = AlloySolvers.findSolvers { null }.map { it.id }
        assertFalse("lingeling.parallel" in ids)
        assertEquals("NO", System.getProperty("alloy.native.exe.plingeling"))
    }

    fun testAPropertySetByTheUserIsKept() {
        val yices = executable("yices")
        System.setProperty("alloy.native.exe.yices", yices)
        assertTrue("yices.external" in AlloySolvers.findSolvers { null }.map { it.id })
        assertEquals(yices, System.getProperty("alloy.native.exe.yices"))
    }

    fun testFailingDiscoveryFallsBackToSat4j() {
        val solvers = AlloySolvers().apply { discover = { error("could not load the solvers") } }
        assertEquals(AlloySolvers.FALLBACK, solvers.load().get(10, TimeUnit.SECONDS))
        assertEquals(setOf("sat4j"), solvers.ids())
    }

    fun testNoSolversFallsBackToSat4j() {
        val solvers = AlloySolvers().apply { discover = { emptyList() } }
        assertEquals(AlloySolvers.FALLBACK, solvers.load().get(10, TimeUnit.SECONDS))
    }

    fun testDiscoveryRunsOncePerSession() {
        var calls = 0
        val solvers = AlloySolvers().apply { discover = { calls++; AlloySolvers.findSolvers { null } } }
        assertNull(solvers.cached())
        assertEquals(setOf("sat4j", "sat4j.light", "sat4j.pmax"), solvers.ids())
        assertEquals(setOf("sat4j", "sat4j.light", "sat4j.pmax"), solvers.ids())
        assertEquals(3, solvers.cached()!!.size)
        assertEquals(1, calls)
    }
}
