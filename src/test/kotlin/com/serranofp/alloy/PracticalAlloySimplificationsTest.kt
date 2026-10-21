package com.serranofp.alloy

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.serranofp.alloy.simplifications.AlloyBooleanRules
import com.serranofp.alloy.simplifications.AlloyCardinalityRules
import com.serranofp.alloy.simplifications.AlloyQuantifierRules
import com.serranofp.alloy.simplifications.AlloyRelationalRules
import com.serranofp.alloy.simplifications.AlloyRewriteWalker
import com.serranofp.alloy.simplifications.AlloySimplifiableBooleanInspection
import com.serranofp.alloy.simplifications.AlloySimplifiableCardinalityInspection
import com.serranofp.alloy.simplifications.AlloySimplifiableQuantifierInspection
import com.serranofp.alloy.simplifications.AlloySimplifiableRelationalInspection
import com.serranofp.alloy.simplifications.AlloySimplifiableTemporalInspection
import com.serranofp.alloy.simplifications.AlloyTemporalRules
import com.serranofp.alloy.simplifications.onModelIfAny
import edu.mit.csail.sdg.alloy4.A4Reporter
import edu.mit.csail.sdg.alloy4.Util
import edu.mit.csail.sdg.parser.CompUtil
import java.io.File

/**
 * The simplification inspections over every model of the Practical Alloy book (see [PracticalAlloyModelsTest]):
 * each weak warning they report is listed and reviewed, and applying any of their fixes keeps the model
 * type-checking.
 */
class PracticalAlloySimplificationsTest : BasePlatformTestCase() {
    override fun getTestDataPath(): String = "src/test/testData"

    private val inspections = listOf(
        AlloySimplifiableBooleanInspection(),
        AlloySimplifiableCardinalityInspection(),
        AlloySimplifiableTemporalInspection(),
        AlloySimplifiableQuantifierInspection(),
        AlloySimplifiableRelationalInspection(),
    )

    private val rules = listOf(AlloyBooleanRules.ALL, AlloyCardinalityRules.ALL, AlloyTemporalRules.ALL, AlloyQuantifierRules.ALL, AlloyRelationalRules.ALL)

    private data class Problem(val inspection: String, val text: String, val message: String)

    private fun none(set: String) = Problem("AlloySimplifiableCardinality", set, "Comparison with 'none'")

    private val someDir = Problem("AlloySimplifiableQuantifier", "some d : Dir | d in Entry.object", "Quantified membership")

    /**
     * By path under `practicalalloy`. The runs that spell out an instance write `File = none` for an empty
     * signature, aligned with the lines around it, where `no File` would do.
     */
    private val expected = mapOf(
        // The style the chapter improves on, with `disj`
        "structural-modeling/a-question-of-style/filesystem.als" to listOf(
            Problem("AlloySimplifiableQuantifier", "all x, y : Dir | x != y implies no (x.entries & y.entries)", "Distinct variables"),
        ),
        "structural-modeling/instance_04_05/filesystem.als" to listOf(none("File = none"), none("File  = none")),
        "structural-modeling/instance_06/filesystem.als" to listOf(none("File    = none"), none("Name    = none")),
        "structural-modeling/instance_10/filesystem.als" to listOf(none("File    = none")),
        "structural-topics/commands/instance_01/filesystem.als" to listOf(none("File    = none")),
        // `some Dir & Entry.object`, which the book writes with a variable to show skolemization
        "structural-topics/model-finding/instance_01_02/filesystem.als" to listOf(someDir, none("File    = none")),
        "structural-topics/model-finding/skolemization/filesystem.als" to listOf(someDir),
        // `entries = none->none` is about a relation, and is not reported
        "structural-topics/subset-signatures/instance_03/filesystem.als" to listOf(
            none("File    = none"), none("Entry   = none"), none("Name    = none"), none("Tagged  = none"),
        ),
    )

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(*inspections.toTypedArray())
    }

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

        val ids = inspections.map { it.shortName }.toSet()
        val failures = models.sortedBy { it.path }.flatMap { model ->
            val relative = VfsUtilCore.getRelativePath(model, root)!!
            val path = "$folder/$relative"
            myFixture.configureFromExistingVirtualFile(model)
            val problems = myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING)
                .filter { it.inspectionToolId in ids }
                .sortedBy { it.startOffset }
                .map { Problem(it.inspectionToolId!!, myFixture.file.text.substring(it.startOffset, it.endOffset), it.description) }
            val unexpected = if (problems != expected[path].orEmpty()) listOf("\"$path\" to listOf(${problems.joinToString { it.code() }}),") else emptyList()
            unexpected + brokenFixes(path, File("$testDataPath/practicalalloy/$path"))
        }
        assertEmpty(failures.joinToString("\n"), failures)
    }

    /**
     * The fixes of the current model that, applied one at a time on a copy of its text, leave it failing to
     * type-check. Models that do not type-check, as the book shows some, have no fixes.
     */
    private fun brokenFixes(path: String, file: File): List<String> {
        val text = myFixture.file.text
        val results = myFixture.onModelIfAny { source, model -> rules.flatMap { AlloyRewriteWalker(source, it).walk(model.module) } }
            ?: return emptyList()
        val filename = Util.canon(file.absolutePath)
        return results.mapNotNull { result ->
            val fixed = text.replaceRange(result.range.startOffset, result.range.endOffset, result.text)
            val error = runCatching { CompUtil.parseEverything_fromFile(A4Reporter.NOP, hashMapOf(filename to fixed), filename) }.exceptionOrNull()
            error?.let { "$path: '${result.fixName}' gives an error: ${it.message}" }
        }
    }

    private fun Problem.code(): String = "Problem(\"$inspection\", ${quote(text)}, ${quote(message)})"

    private fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}
