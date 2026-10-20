package com.serranofp.alloy.semantics

import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.serranofp.alloy.AlloyIcons

class AlloyLibraryRootsProviderTest : BasePlatformTestCase() {
    fun testOneLibraryWithTheUtilModules() {
        val libraries = AdditionalLibraryRootsProvider.EP_NAME.extensionList
            .filterIsInstance<AlloyLibraryRootsProvider>().single()
            .getAdditionalProjectLibraries(project)
        val library = libraries.single()
        val root = library.sourceRoots.single()
        assertNotNull(root.findFileByRelativePath("util/ordering.als"))

        val presentation = library as ItemPresentation
        assertEquals("Alloy 6.2.0", presentation.presentableText)
        assertEquals(AlloyIcons.FILE, presentation.getIcon(false))
    }

    fun testLibraryModulesAreInTheLibrary() {
        val ordering = AlloyPositions.libraryFile("/\$alloy4\$/models/util/ordering.als")!!
        assertTrue(ProjectFileIndex.getInstance(project).isInLibrary(ordering))
    }
}
