package com.serranofp.alloy.inspections

import com.intellij.codeHighlighting.HighlightDisplayLevel
import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.runInInitMode

class AlloyInspectionRegistrationTest : BasePlatformTestCase() {
    private val inspections = mapOf(
        "AlloyUnscopedMutableSignature" to "Signature in mutable state left at the default scope",
        "AlloyExactParameterScope" to "Exact scope forced by a module parameter",
        "AlloySeqBoundTooSmall" to "Sequence bound smaller than its elements' scope",
        "AlloyMissingFrameCondition" to "Missing frame condition",
    )

    fun testInspectionsAreListedInTheAlloyGroup() {
        // A new profile as the settings page shows it: tests do not initialize the tools of their own profiles
        val profile = runInInitMode { InspectionProfileImpl("Alloy test").apply { initInspectionTools(project) } }
        val alloy = profile.getInspectionTools(null).filter { it.groupDisplayName == "Alloy" }.associateBy { it.shortName }
        assertEquals(inspections.keys, alloy.keys)
        for ((id, name) in inspections) {
            val tool = alloy.getValue(id)
            assertEquals(name, tool.displayName)
            assertTrue("$id is not enabled by default", tool.isEnabledByDefault)
            assertEquals(HighlightDisplayLevel.WEAK_WARNING, tool.defaultLevel)
            val key = HighlightDisplayKey.find(id)!!
            assertTrue("$id is not enabled in the profile", profile.isToolEnabled(key))
            assertEquals(HighlightDisplayLevel.WEAK_WARNING, profile.getErrorLevel(key, null))
            assertNotNull("$id has no description file", javaClass.classLoader.getResource("inspectionDescriptions/$id.html"))
            assertFalse("$id has no description", tool.loadDescription().isNullOrBlank())
        }
    }
}
