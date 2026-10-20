package com.serranofp.alloy.settings

import com.intellij.openapi.project.Project
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.serranofp.alloy.run.AlloyCommandRef
import com.serranofp.alloy.run.AlloyCommandResults
import com.serranofp.alloy.run.AlloyRunService
import com.serranofp.alloy.run.CommandState
import com.serranofp.alloy.run.newExecution
import java.util.concurrent.TimeUnit

class AlloySettingsApplierTest : BasePlatformTestCase() {
    private val restarted = mutableListOf<Project>()
    private val generation get() = AlloySettings.getInstance().generation

    fun testActiveRunIsInterruptedAndServerRestarted() {
        AlloyCommandResults.getInstance(project).clear()
        val file = myFixture.configureByText("model.als", "run show for 3\n").virtualFile
        val service = AlloyRunService.getInstance(project)
        val execution = newExecution(project, file)
        service.startRun(execution)

        AlloySettingsApplier.settingsChanged(project) { restarted += it }

        assertTrue(execution.isFinished())
        assertNull(service.activeExecution)
        val result = AlloyCommandResults.getInstance(project).get(file, AlloyCommandRef("show", 0))!!
        assertEquals(CommandState.Failed, result.state)
        assertEquals("Interrupted", result.summary)
        assertEquals("The analysis was interrupted by a settings change", result.details.failure)
        assertTrue(AlloyCommandResults.getInstance(project).isOutdated(result, myFixture.editor.document))
        assertEquals(listOf(project), restarted)
    }

    fun testServerRestartedWithoutActiveRun() {
        AlloySettingsApplier.settingsChanged(project) { restarted += it }
        assertEquals(listOf(project), restarted)
    }

    fun testGenerationIncreasesOnAChange() {
        val before = generation
        AlloySettingsApplier.settingsChanged(project) { restarted += it }
        assertTrue(generation > before)
    }

    /** The settings page only calls the applier when an option changed. */
    fun testGenerationUnchangedWhenNothingIsApplied() {
        val discovery = AlloySolvers().apply { discover = { listOf(AlloySolver("sat4j", "sat4j")) } }
        val page = AlloySettingsConfigurable(AlloySettings(), discovery) {
            AlloySettingsApplier.settingsChanged(project) { restarted += it }
        }
        page.createComponent()
        discovery.load().get(10, TimeUnit.SECONDS)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        val before = generation
        page.apply()
        assertEquals(before, generation)
        assertEmpty(restarted)

        page.skolemDepth.selectedItem = 3
        page.apply()
        assertTrue(generation > before)
        assertEquals(listOf(project), restarted)
        page.disposeUIResources()
    }

    /** The real restart goes through the platform; with no server running it does nothing. */
    fun testRealRestartWithoutServer() {
        AlloySettingsApplier.settingsChanged()
    }
}
