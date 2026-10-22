package com.serranofp.alloy.settings

import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

class AlloySettingsConfigurableTest : BasePlatformTestCase() {
    private val settings = AlloySettings()
    private var changes = 0

    private val pages = mutableListOf<AlloySettingsConfigurable>()

    /** A page shown with [solvers] found, once discovery ended; its UI is disposed after the test. */
    private fun configurable(solvers: List<AlloySolver>): AlloySettingsConfigurable {
        val discovery = AlloySolvers().apply { discover = { solvers } }
        return AlloySettingsConfigurable(settings, discovery) { changes++ }.also {
            pages += it
            it.createComponent()
            discovery.load().get(10, TimeUnit.SECONDS)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        }
    }

    override fun tearDown() {
        try {
            pages.forEach { it.disposeUIResources() }
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private val present = listOf(AlloySolver("sat4j", "sat4j"), AlloySolver("sat4j.light", "sat4j.light"), AlloySolver("minisat", "MiniSat"))

    fun testShowsSavedValues() {
        val page = configurable(present)
        assertEquals("sat4j", page.solver.selectedItem)
        assertEquals(1, page.skolemDepth.selectedItem)
        assertTrue(page.preventOverflows.isSelected)
        assertEquals("Fast", page.coreMinimization.selectedItem)
        assertEquals("Top-level", page.coreGranularity.selectedItem)
        assertFalse(page.nonFatalWarnings.isSelected)
        assertFalse(page.recordKodkod.isSelected)
        assertFalse(page.inferPartialInstance.isSelected)
        assertTrue(page.checkVacuity.isSelected)
        assertFalse(page.isModified)
    }

    fun testRestrictedChoices() {
        val page = configurable(present)
        assertEquals(listOf(0, 1, 2, 3, 4), (0 until page.skolemDepth.itemCount).map { page.skolemDepth.getItemAt(it) })
        assertEquals(listOf("Slow", "Medium", "Fast"), (0 until page.coreMinimization.itemCount).map { page.coreMinimization.getItemAt(it) })
        assertEquals(4, page.coreGranularity.itemCount)
        assertEquals(listOf("sat4j", "sat4j.light", "minisat"), (0 until page.solver.itemCount).map { page.solver.getItemAt(it) })
        assertEquals("MiniSat", page.solverLabel("minisat"))
    }

    fun testEveryOptionRoundTrips() {
        val page = configurable(present)
        val edits: List<Pair<String, () -> Unit>> = listOf(
            "solver" to { page.solver.selectedItem = "minisat" },
            "skolem depth" to { page.skolemDepth.selectedItem = 3 },
            "prevent overflows" to { page.preventOverflows.isSelected = false },
            "core minimization" to { page.coreMinimization.selectedItem = "Slow" },
            "core granularity" to { page.coreGranularity.selectedItem = "Expand quantifiers" },
            "non-fatal warnings" to { page.nonFatalWarnings.isSelected = true },
            "record Kodkod" to { page.recordKodkod.isSelected = true },
            "infer partial instance" to { page.inferPartialInstance.isSelected = true },
            "check vacuity" to { page.checkVacuity.isSelected = false },
        )
        assertEquals(9, edits.size)
        for ((option, edit) in edits) {
            edit()
            assertTrue("$option is not detected as modified", page.isModified)
            page.reset()
            assertFalse("reset does not restore $option", page.isModified)
            edit()
            page.apply()
            assertFalse("apply does not save $option", page.isModified)
        }
        assertEquals(edits.size, changes)

        val saved = settings.state
        assertEquals("minisat", saved.solver)
        assertEquals(3, saved.skolemDepth)
        assertFalse(saved.preventOverflows)
        assertEquals(0, saved.coreMinimization)
        assertEquals(3, saved.coreGranularity)
        assertTrue(saved.nonFatalWarnings)
        assertTrue(saved.recordKodkod)
        assertTrue(saved.inferPartialInstance)
        assertFalse(saved.checkVacuity)

        // A fresh page shows what was saved
        val reopened = configurable(present)
        assertEquals("minisat", reopened.solver.selectedItem)
        assertEquals(3, reopened.skolemDepth.selectedItem)
        assertEquals("Expand quantifiers", reopened.coreGranularity.selectedItem)
        assertFalse(reopened.checkVacuity.isSelected)
        assertFalse(reopened.isModified)
    }

    /** Every option of the page is one of its nine fields, shown on the panel. */
    fun testShowsNineOptions() {
        val discovery = AlloySolvers().apply { discover = { present } }
        val page = AlloySettingsConfigurable(settings, discovery) { changes++ }.also { pages += it }
        val panel = page.createComponent()
        // So that the page is filled before it is disposed, rather than after the test
        discovery.load().get(10, TimeUnit.SECONDS)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        val options = listOf(
            page.solver, page.skolemDepth, page.preventOverflows, page.coreMinimization, page.coreGranularity,
            page.nonFatalWarnings, page.recordKodkod, page.inferPartialInstance, page.checkVacuity,
        )
        assertEquals(9, options.size)
        for (option in options) assertTrue("$option is not on the page", SwingUtilities.isDescendingFrom(option, panel))
        assertEquals("Check vacuity of passing checks", page.checkVacuity.text)
    }

    fun testApplyWithoutChangesDoesNothing() {
        val page = configurable(present)
        page.apply()
        assertEquals(0, changes)
    }

    fun testMissingSavedSolverIsShownAsUnavailable() {
        settings.state.solver = "glucose"
        val page = configurable(listOf(AlloySolver("sat4j", "sat4j")))
        assertEquals("glucose", page.solver.selectedItem)
        assertEquals("glucose (unavailable)", page.solverLabel("glucose"))
        assertEquals("sat4j", page.solverLabel("sat4j"))
        assertFalse(page.isModified)
    }

    fun testOutOfRangeSavedValuesAreClamped() {
        settings.state.coreMinimization = 7
        settings.state.coreGranularity = -1
        val page = configurable(present)
        assertEquals("Fast", page.coreMinimization.selectedItem)
        assertEquals("Top-level", page.coreGranularity.selectedItem)
    }

    /** What a hand-edited or older `alloy.xml` holds is shown as the analyzer gets it, clamped. */
    fun testOutOfRangeValuesOfASavedFile() {
        val xml = JDOMUtil.load(
            """<AlloySettingsState><option name="skolemDepth" value="9" /><option name="coreMinimization" value="7" />""" +
                """<option name="coreGranularity" value="-2" /></AlloySettingsState>""",
        )
        settings.loadState(XmlSerializer.deserialize(xml, AlloySettingsState::class.java))
        assertEquals(9, settings.state.skolemDepth)
        val page = configurable(present)
        assertEquals("Fast", page.coreMinimization.selectedItem)
        assertEquals("Top-level", page.coreGranularity.selectedItem)
        val options = settings.analyzerOptions { setOf("sat4j") }
        assertEquals(4, options.skolemDepth)
        assertEquals(2, options.coreMinimization)
        assertEquals(0, options.coreGranularity)
    }

    /** With discovery already ended, the list is filled as the page is created, and not again later. */
    fun testSolversFoundBeforeAreFilledOnce() {
        val discovery = AlloySolvers().apply { discover = { present } }
        discovery.load().get(10, TimeUnit.SECONDS)
        val page = AlloySettingsConfigurable(settings, discovery) { changes++ }.also { pages += it }
        page.createComponent()
        val model = page.solver.model
        assertEquals(listOf("sat4j", "sat4j.light", "minisat"), (0 until page.solver.itemCount).map { page.solver.getItemAt(it) })
        assertEquals("MiniSat", page.solverLabel("minisat"))
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertSame(model, page.solver.model)
        assertFalse(page.isModified)
    }

    fun testNoSavedSolver() {
        settings.state.solver = null
        val page = configurable(present)
        assertEquals(present.size, page.solver.itemCount)
        assertFalse((0 until page.solver.itemCount).any { page.solver.getItemAt(it) == null })
    }

    fun testDiscoveryEndingAfterThePageClosed() {
        val release = CountDownLatch(1)
        val discovery = AlloySolvers().apply { discover = { release.await(); present } }
        val page = AlloySettingsConfigurable(settings, discovery) { changes++ }
        try {
            page.createComponent()
            page.disposeUIResources()
        } finally {
            release.countDown()
        }
        discovery.load().get(10, TimeUnit.SECONDS)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals("sat4j (loading solvers...)", page.solverLabel("sat4j"))
    }

    fun testLoadingState() {
        val release = CountDownLatch(1)
        val discovery = AlloySolvers().apply { discover = { release.await(); present } }
        val page = AlloySettingsConfigurable(settings, discovery) { changes++ }
        try {
            page.createComponent()
            assertEquals("sat4j (loading solvers...)", page.solverLabel("sat4j"))
            assertFalse(page.isModified)
        } finally {
            release.countDown()
        }
        discovery.load().get(10, TimeUnit.SECONDS)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals("MiniSat", page.solverLabel("minisat"))
    }
}
