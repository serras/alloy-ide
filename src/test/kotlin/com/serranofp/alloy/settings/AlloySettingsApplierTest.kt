package com.serranofp.alloy.settings

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.serranofp.alloy.run.AlloyCommandRef
import com.serranofp.alloy.run.AlloyCommandResults
import com.serranofp.alloy.run.AlloyRunService
import com.serranofp.alloy.run.CommandState
import com.serranofp.alloy.run.Outdated
import com.serranofp.alloy.run.analysisEvents
import com.serranofp.alloy.run.newExecution
import java.util.concurrent.TimeUnit

class AlloySettingsApplierTest : BasePlatformTestCase() {
    private val generation get() = AlloySettings.getInstance().generation

    fun testRunningAnalysisContinuesAndIsOutdatedWhenItEnds() {
        val results = AlloyCommandResults.getInstance(project)
        results.clear()
        val file = myFixture.configureByText("model.als", "pred show {}\nrun show for 3\n").virtualFile
        val service = AlloyRunService.getInstance(project)
        val execution = newExecution(project, file)
        service.startRun(execution)

        AlloySettingsApplier.settingsChanged()

        assertFalse(execution.isFinished())
        assertSame(execution, service.activeExecution)
        analysisEvents("sig A {}\npred show { some A }\nrun show for 3").forEach(execution::accept)
        val result = results.get(file, AlloyCommandRef("show", 0))!!
        assertEquals(CommandState.InstanceFound, result.state)
        assertEquals(Outdated.SettingsChanged, results.outdated(result, myFixture.editor.document))
    }

    fun testGenerationIncreasesOnAChange() {
        val before = generation
        AlloySettingsApplier.settingsChanged()
        assertTrue(generation > before)
    }

    /** The settings page only calls the applier when an option changed. */
    fun testGenerationUnchangedWhenNothingIsApplied() {
        val discovery = AlloySolvers().apply { discover = { listOf(AlloySolver("sat4j", "sat4j")) } }
        val page = AlloySettingsConfigurable(AlloySettings(), discovery, AlloySettingsApplier::settingsChanged)
        page.createComponent()
        discovery.load().get(10, TimeUnit.SECONDS)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        val before = generation
        page.apply()
        assertEquals(before, generation)

        page.skolemDepth.selectedItem = 3
        page.apply()
        assertTrue(generation > before)
        page.disposeUIResources()
    }
}
