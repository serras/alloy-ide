package com.serranofp.alloy.settings

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer

class AlloySettingsTest : BasePlatformTestCase() {
    private val unavailable: () -> Collection<String> = { error("availability is not needed for sat4j") }

    // A solver other than sat4j is looked up with its executables: none is on the PATH, whatever the machine
    private val properties = SolverProperties()

    override fun setUp() {
        super.setUp()
        properties.allAbsent()
    }

    override fun tearDown() {
        try {
            properties.restore()
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testDefaultsAreUpstreamDefaults() {
        val state = AlloySettingsState()
        assertEquals("sat4j", state.solver)
        assertEquals(1, state.skolemDepth)
        assertTrue(state.preventOverflows)
        assertEquals("Fast", AlloySettingsState.CORE_MINIMIZATION_LABELS[state.coreMinimization])
        assertEquals("Top-level", AlloySettingsState.CORE_GRANULARITY_LABELS[state.coreGranularity])
        assertFalse(state.nonFatalWarnings)
        assertFalse(state.recordKodkod)
        assertFalse(state.inferPartialInstance)
        assertTrue(state.checkVacuity)
    }

    fun testSerializationRoundTrip() {
        val state = AlloySettingsState().apply {
            solver = "minisat"
            skolemDepth = 3
            preventOverflows = false
            coreMinimization = 0
            coreGranularity = 2
            nonFatalWarnings = true
            recordKodkod = true
            inferPartialInstance = true
            checkVacuity = false
        }
        val settings = AlloySettings()
        settings.loadState(XmlSerializer.deserialize(XmlSerializer.serialize(state), AlloySettingsState::class.java))
        assertEquals(state, settings.state)
    }

    fun testAvailableSolverIsUsed() {
        val settings = AlloySettings().apply { state.solver = "minisat" }
        assertEquals("minisat", settings.effectiveSolver { setOf("sat4j", "minisat") })
        assertNull(settings.solverFallbackWarning { setOf("sat4j", "minisat") })
    }

    fun testUnavailableSolverFallsBackToSat4j() {
        val settings = AlloySettings().apply { state.solver = "minisat" }
        assertEquals("sat4j", settings.effectiveSolver { setOf("sat4j") })
        assertEquals(
            "The solver minisat is not available, using sat4j instead\n",
            settings.solverFallbackWarning { setOf("sat4j") },
        )
    }

    fun testDefaultAnalyzerOptions() {
        val options = AlloySettings().analyzerOptions(unavailable)
        assertEquals("sat4j", options.solver.id())
        assertEquals(1, options.skolemDepth)
        assertTrue(options.noOverflow)
        assertEquals(2, options.coreMinimization)
        assertEquals(0, options.coreGranularity)
        assertFalse(options.recordKodkod)
        assertFalse(options.inferPartialInstance)
    }

    fun testChangedAnalyzerOptions() {
        val settings = AlloySettings()
        settings.state.apply {
            solver = "sat4j.light"
            skolemDepth = 4
            preventOverflows = false
            coreMinimization = 0
            coreGranularity = 3
            recordKodkod = true
            inferPartialInstance = true
        }
        val options = settings.analyzerOptions { setOf("sat4j", "sat4j.light") }
        assertEquals("sat4j.light", options.solver.id())
        assertEquals(4, options.skolemDepth)
        assertFalse(options.noOverflow)
        assertEquals(0, options.coreMinimization)
        assertEquals(3, options.coreGranularity)
        assertTrue(options.recordKodkod)
        assertTrue(options.inferPartialInstance)
    }

    fun testOutOfRangeValuesAreClamped() {
        val settings = AlloySettings()
        for ((saved, expected) in listOf(Triple(9, 7, 4) to Triple(4, 2, 3), Triple(-3, -1, -2) to Triple(0, 0, 0))) {
            settings.state.skolemDepth = saved.first
            settings.state.coreMinimization = saved.second
            settings.state.coreGranularity = saved.third
            val options = settings.analyzerOptions(unavailable)
            assertEquals("$saved", expected, Triple(options.skolemDepth, options.coreMinimization, options.coreGranularity))
        }
    }

    fun testNoSavedSolverIsSat4j() {
        val settings = AlloySettings().apply { state.solver = null }
        assertEquals("sat4j", settings.analyzerOptions(unavailable).solver.id())
        assertNull(settings.solverFallbackWarning(unavailable))
    }

    /** An external solver that is listed as available but whose executable is gone runs with sat4j. */
    fun testAvailableSolverWithoutItsExecutableUsesSat4j() {
        val settings = AlloySettings().apply { state.solver = "yices.external" }
        assertEquals("sat4j", settings.analyzerOptions { setOf("sat4j", "yices.external") }.solver.id())
    }

    fun testEachRunGetsItsOwnOptions() {
        val settings = AlloySettings()
        val first = settings.analyzerOptions(unavailable)
        settings.state.skolemDepth = 2
        assertEquals(1, first.skolemDepth)
        assertEquals(2, settings.analyzerOptions(unavailable).skolemDepth)
    }

    fun testUnavailableSolverOptionsUseSat4j() {
        val settings = AlloySettings().apply { state.solver = "minisat" }
        assertEquals("sat4j", settings.analyzerOptions { setOf("sat4j") }.solver.id())
    }

    fun testDefaultSolverNeedsNoDiscovery() {
        assertNull(AlloySettings().solverFallbackWarning(unavailable))
    }
}
