package com.serranofp.alloy

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.serranofp.alloy.semantics.AlloyPositions

/**
 * The library modules bundled in the analyzer's JAR, under `models/util`: every one parses, and the analyzer
 * reports no problems in any of them.
 */
class BundledModulesTest : BasePlatformTestCase() {
    fun testBundledModules() {
        val root = AlloyPositions.libraryRoot()
        assertNotNull("The analyzer's JAR has no models/ directory", root)
        val modules = mutableListOf<VirtualFile>()
        VfsUtilCore.iterateChildrenRecursively(root!!, null) { file ->
            if (file.extension == "als") modules += file
            true
        }
        assertNotEmpty(modules)

        // Copied with the same folders, so that the modules open one another as they do from the JAR
        val copies = modules.associateWith { module ->
            myFixture.addFileToProject(VfsUtilCore.getRelativePath(module, root)!!, VfsUtilCore.loadText(module))
        }

        val failures = modules.sortedBy { it.path }.mapNotNull { module ->
            val path = VfsUtilCore.getRelativePath(module, root)!!
            myFixture.configureFromExistingVirtualFile(copies.getValue(module).virtualFile)
            val parseErrors = PsiTreeUtil.findChildrenOfType(myFixture.file, PsiErrorElement::class.java)
            val problems = myFixture.doHighlighting(HighlightSeverity.WARNING).map { info ->
                "${info.severity}: '${myFixture.file.text.substring(info.startOffset, info.endOffset)}' " +
                    info.description.lineSequence().first { it.isNotBlank() }
            }
            when {
                parseErrors.isNotEmpty() ->
                    "$path does not parse: " + parseErrors.joinToString { "${it.errorDescription} at ${it.textOffset}" }
                problems.isNotEmpty() -> "$path has problems $problems"
                else -> null
            }
        }
        assertEmpty(failures.joinToString("\n"), failures)
    }
}
