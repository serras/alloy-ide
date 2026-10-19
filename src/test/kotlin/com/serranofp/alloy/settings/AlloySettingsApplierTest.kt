package com.serranofp.alloy.settings

import com.intellij.openapi.project.Project
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.serranofp.alloy.run.AlloyRunProcessHandler
import com.serranofp.alloy.run.AlloyRunService
import com.serranofp.alloy.run.RecordingSink

class AlloySettingsApplierTest : BasePlatformTestCase() {
    private val restarted = mutableListOf<Project>()

    fun testActiveRunIsInterruptedAndServerRestarted() {
        val service = AlloyRunService.getInstance(project)
        val sink = RecordingSink()
        val run = AlloyRunProcessHandler(service, sink).also { it.startNotify() }
        service.startRun(run)

        AlloySettingsApplier.settingsChanged(project) { restarted += it }

        assertTrue(run.isProcessTerminated)
        assertEquals("The analysis was interrupted by a settings change\n", sink.text)
        assertNull(service.activeRun)
        assertEquals(listOf(project), restarted)
    }

    fun testServerRestartedWithoutActiveRun() {
        AlloySettingsApplier.settingsChanged(project) { restarted += it }
        assertEquals(listOf(project), restarted)
    }

    /** The real restart goes through the platform; with no server running it does nothing. */
    fun testRealRestartWithoutServer() {
        AlloySettingsApplier.settingsChanged()
    }
}
