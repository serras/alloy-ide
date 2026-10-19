package com.serranofp.alloy.settings

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.concurrent.TimeUnit

class AlloySolversTest : BasePlatformTestCase() {
    /** Output of `list-solvers` with the bundled 6.2.0 analyzer. */
    private val shimOutput = """[{"id":"sat4j","name":"sat4j"},{"id":"sat4j.light","name":"sat4j.light"},{"id":"sat4j.pmax","name":"sat4j.pmax"}]""" + "\n"

    fun testParsesShimOutput() {
        assertEquals(
            listOf(AlloySolver("sat4j", "sat4j"), AlloySolver("sat4j.light", "sat4j.light"), AlloySolver("sat4j.pmax", "sat4j.pmax")),
            AlloySolvers.parse(shimOutput),
        )
    }

    fun testUnexpectedOutputFallsBackToSat4j() {
        assertEquals(AlloySolvers.FALLBACK, AlloySolvers.parse("SLF4J: no binding\n"))
        assertEquals(AlloySolvers.FALLBACK, AlloySolvers.parse("[]"))
        assertEquals(AlloySolvers.FALLBACK, AlloySolvers.parse(""))
    }

    fun testFailingDiscoveryFallsBackToSat4j() {
        val solvers = AlloySolvers().apply { discover = { error("could not start java") } }
        assertEquals(AlloySolvers.FALLBACK, solvers.load().get(10, TimeUnit.SECONDS))
        assertEquals(setOf("sat4j"), solvers.ids())
    }

    fun testDiscoveryRunsOncePerSession() {
        var calls = 0
        val solvers = AlloySolvers().apply { discover = { calls++; AlloySolvers.parse(shimOutput) } }
        assertNull(solvers.cached())
        assertEquals(setOf("sat4j", "sat4j.light", "sat4j.pmax"), solvers.ids())
        assertEquals(setOf("sat4j", "sat4j.light", "sat4j.pmax"), solvers.ids())
        assertEquals(3, solvers.cached()!!.size)
        assertEquals(1, calls)
    }
}
