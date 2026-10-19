package com.serranofp.alloy.settings

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer

class AlloySettingsTest : BasePlatformTestCase() {
    private val unavailable: () -> Collection<String> = { error("availability is not needed for sat4j") }

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
        }
        val settings = AlloySettings()
        settings.loadState(XmlSerializer.deserialize(XmlSerializer.serialize(state), AlloySettingsState::class.java))
        assertEquals(state, settings.state)
    }

    fun testDefaultPropertyMap() {
        assertEquals(
            mapOf(
                "SatSolver2" to "sat4j",
                "SkolemDepth3" to "1",
                "NoOverflow" to "y",
                "CoreMinimization" to "2",
                "CoreGranularity" to "0",
                "WarningNonfatal" to "n",
                "RecordKodkod" to "n",
                "InferPartialInstance" to "n",
            ),
            AlloySettings().analyzerPreferences(unavailable),
        )
    }

    fun testChangedPropertyMap() {
        val settings = AlloySettings()
        settings.state.apply {
            skolemDepth = 4
            preventOverflows = false
            coreMinimization = 1
            coreGranularity = 3
            nonFatalWarnings = true
            recordKodkod = true
            inferPartialInstance = true
        }
        assertEquals(
            mapOf(
                "SatSolver2" to "sat4j",
                "SkolemDepth3" to "4",
                "NoOverflow" to "n",
                "CoreMinimization" to "1",
                "CoreGranularity" to "3",
                "WarningNonfatal" to "y",
                "RecordKodkod" to "y",
                "InferPartialInstance" to "y",
            ),
            settings.analyzerPreferences(unavailable),
        )
    }

    fun testAvailableSolverIsUsed() {
        val settings = AlloySettings().apply { state.solver = "minisat" }
        assertEquals("minisat", settings.analyzerPreferences { setOf("sat4j", "minisat") }["SatSolver2"])
        assertNull(settings.solverFallbackWarning { setOf("sat4j", "minisat") })
    }

    fun testUnavailableSolverFallsBackToSat4j() {
        val settings = AlloySettings().apply { state.solver = "minisat" }
        assertEquals("sat4j", settings.analyzerPreferences { setOf("sat4j") }["SatSolver2"])
        assertEquals(
            "The solver minisat is not available, using sat4j instead\n",
            settings.solverFallbackWarning { setOf("sat4j") },
        )
    }

    fun testDefaultSolverNeedsNoDiscovery() {
        assertNull(AlloySettings().solverFallbackWarning(unavailable))
    }
}
