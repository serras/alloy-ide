package com.serranofp.alloy.settings

import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AlloySolversTest : BasePlatformTestCase() {
    private val properties = SolverProperties()
    private val temporaryDirectories = mutableListOf<Path>()

    override fun setUp() {
        super.setUp()
        properties.clear()
    }

    override fun tearDown() {
        try {
            properties.restore()
            temporaryDirectories.forEach { FileUtil.delete(it) }
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** A stand-in for an external solver's executable; listing a solver does not run it. */
    private fun executable(name: String): String {
        val directory = Files.createTempDirectory("alloy-solvers").also { temporaryDirectories.add(it) }
        val path = Files.writeString(directory.resolve(name), "#!/bin/sh\n")
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
        // Every executable now points at the stand-in, until the properties are restored
        assertTrue(properties.names.all { System.getProperty(it) == anything })
    }

    /** Discovery looks only where it is told, and records every executable, found or not. */
    fun testEveryExecutableIsRecorded() {
        val asked = mutableListOf<String>()
        AlloySolvers.findSolvers { asked += it; null }
        assertEquals(properties.names.map { it.removePrefix(SolverProperties.PREFIX) }.sorted(), asked.sorted())
        assertTrue(properties.names.all { System.getProperty(it) == SolverProperties.ABSENT })
        // Once recorded, they are not looked for again
        AlloySolvers.findSolvers { fail("$it looked for again"); null }
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

    fun testInterruptedWaitKeepsTheInterrupt() {
        val release = CountDownLatch(1)
        val solvers = AlloySolvers().apply { discover = { release.await(); emptyList() } }
        try {
            Thread.currentThread().interrupt()
            assertEquals(setOf("sat4j"), solvers.ids())
            assertTrue("the interrupt was swallowed", Thread.interrupted())
        } finally {
            release.countDown()
        }
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
