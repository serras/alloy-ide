package com.serranofp.alloy

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.stubs.StubTextInconsistencyException
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The models of the Practical Alloy book (https://github.com/practicalalloy/models), with the same folders: every
 * one parses, and the analyzer reports nothing but the problems some of them are there to show.
 */
class PracticalAlloyModelsTest : BasePlatformTestCase() {
    override fun getTestDataPath(): String = "src/test/testData"

    private data class Problem(val severity: HighlightSeverity, val text: String, val message: String)

    /** By path under `practicalalloy`; the message is the first non-blank line of the description. */
    private val expected = mapOf(
        "structural-topics/type-system/ambiguity-errors/filesystem.als" to listOf(
            Problem(HighlightSeverity.ERROR, "contents", "This name is ambiguous due to multiple matches:"),
        ),
        "structural-topics/type-system/arity-errors/filesystem.als" to listOf(
            Problem(
                HighlightSeverity.ERROR, "+",
                "+ can be used only between 2 expressions of the same arity, or between 2 integer expressions.",
            ),
        ),
        "structural-topics/type-system/irrelevance-warnings/filesystem.als" to listOf(
            Problem(HighlightSeverity.WARNING, ".", "The join operation here always yields an empty set."),
        ),
    )

    fun testStructuralModeling() = checkModels("structural-modeling")

    fun testStructuralTopics() = checkModels("structural-topics")

    fun testBehavioralModeling() = checkModels("behavioral-modeling")

    fun testBehavioralTopics() = checkModels("behavioral-topics")

    fun testProtocolDesign() = checkModels("protocol-design")

    private fun checkModels(folder: String) {
        val root = myFixture.copyDirectoryToProject("practicalalloy/$folder", folder)
        val models = mutableListOf<VirtualFile>()
        VfsUtilCore.iterateChildrenRecursively(root, null) { file ->
            if (file.extension == "als") models += file
            true
        }
        assertNotEmpty(models)
        // A moved or renamed model would leave its expected problems unchecked
        val paths = models.map { "$folder/${VfsUtilCore.getRelativePath(it, root)}" }
        assertEmpty("Expected problems of models that are not there", expected.keys.filter { it.startsWith("$folder/") } - paths.toSet())

        val failures = models.sortedBy { it.path }.mapNotNull { model ->
            val path = "$folder/${VfsUtilCore.getRelativePath(model, root)}"
            myFixture.configureFromExistingVirtualFile(model)
            // The stubs that the index keeps agree with the tree that the editor builds
            StubTextInconsistencyException.checkStubTextConsistency(myFixture.file)
            val parseErrors = PsiTreeUtil.findChildrenOfType(myFixture.file, PsiErrorElement::class.java)
            val problems = myFixture.doHighlighting(HighlightSeverity.WARNING).map { info ->
                Problem(
                    info.severity,
                    myFixture.file.text.substring(info.startOffset, info.endOffset),
                    info.description.lineSequence().first { it.isNotBlank() },
                )
            }
            when {
                parseErrors.isNotEmpty() ->
                    "$path does not parse: " + parseErrors.joinToString { "${it.errorDescription} at ${it.textOffset}" }
                problems != expected[path].orEmpty() ->
                    "$path has problems $problems, expected ${expected[path].orEmpty()}"
                else -> null
            }
        }
        assertEmpty(failures.joinToString("\n"), failures)
    }
}
