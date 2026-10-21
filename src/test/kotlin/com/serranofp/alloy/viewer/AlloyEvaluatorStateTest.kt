package com.serranofp.alloy.viewer

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer

class AlloyEvaluatorStateTest : BasePlatformTestCase() {
    private inline fun <reified T : Any> roundTrip(state: T): T = XmlSerializer.deserialize(XmlSerializer.serialize(state), T::class.java)

    fun testWatchesOfTwoFilesAreSavedAndLoaded() {
        val ring = myFixture.addFileToProject("ring.als", "sig Node {}").virtualFile
        val graph = myFixture.addFileToProject("graph.als", "sig Node {}").virtualFile
        val saved = AlloyEvaluatorState()
        saved.setWatches(ring, listOf("Elected", "node_enabled[n]"))
        saved.setWatches(graph, listOf("#Node"))

        val loaded = AlloyEvaluatorState()
        loaded.loadState(roundTrip(saved.state))
        assertEquals(listOf("Elected", "node_enabled[n]"), loaded.watches(ring))
        assertEquals(listOf("#Node"), loaded.watches(graph))
    }

    fun testFileWithoutWatchesIsForgotten() {
        val ring = myFixture.addFileToProject("ring.als", "sig Node {}").virtualFile
        val state = AlloyEvaluatorState()
        state.setWatches(ring, listOf("Elected"))
        state.setWatches(ring, emptyList())
        assertEmpty(state.state.files)
        assertEmpty(state.watches(ring))
    }

    fun testMissingFilesArePrunedOnLoad() {
        val ring = myFixture.addFileToProject("ring.als", "sig Node {}").virtualFile
        val saved = AlloyEvaluatorState()
        saved.setWatches(ring, listOf("Elected"))
        saved.state.files += FileWatches().also {
            it.url = ring.url.replace("ring.als", "gone.als")
            it.expressions = mutableListOf("x")
        }
        val loaded = AlloyEvaluatorState()
        loaded.loadState(roundTrip(saved.state))
        assertEquals(listOf(ring.url), loaded.state.files.map { it.url })
    }

    fun testPanelVisibilityAndProportionAreSaved() {
        val state = AlloyInstanceViewerState().apply {
            evaluatorVisible = true
            evaluatorProportion = 0.4f
        }
        val viewer = AlloyInstanceViewer(project)
        viewer.loadState(roundTrip(state))
        assertTrue(viewer.evaluatorVisible)
        assertEquals(0.4f, viewer.evaluatorProportion)
        val defaults = AlloyInstanceViewer(project)
        assertFalse(defaults.evaluatorVisible)
        assertEquals(AlloyInstanceViewer.EVALUATOR_PROPORTION, defaults.evaluatorProportion)
    }
}
