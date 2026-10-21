package com.serranofp.alloy

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.serranofp.alloy.inspections.AlloyExactParameterScopeInspection
import com.serranofp.alloy.inspections.AlloyFactOnlyAtStartInspection
import com.serranofp.alloy.inspections.AlloyIntLiteralRangeInspection
import com.serranofp.alloy.inspections.AlloyMissingFrameConditionInspection
import com.serranofp.alloy.inspections.AlloySeqBoundTooSmallInspection
import com.serranofp.alloy.inspections.AlloyStaticPrimeInspection
import com.serranofp.alloy.inspections.AlloyUnscopedMutableSignatureInspection

/**
 * The Alloy inspections over the behavioral models of the Practical Alloy book (see [PracticalAlloyModelsTest]):
 * the weak warnings they report are the mistakes that the book shows on purpose, and the facts they report as
 * information are its initial states.
 */
class PracticalAlloyInspectionsTest : BasePlatformTestCase() {
    override fun getTestDataPath(): String = "src/test/testData"

    private val inspections = listOf(
        AlloyUnscopedMutableSignatureInspection(),
        AlloyExactParameterScopeInspection(),
        AlloySeqBoundTooSmallInspection(),
        AlloyMissingFrameConditionInspection(),
        AlloyFactOnlyAtStartInspection(),
        AlloyStaticPrimeInspection(),
        AlloyIntLiteralRangeInspection(),
    )

    private data class Problem(val inspection: String, val text: String, val message: String)

    private val messageScope = Problem(
        "AlloyUnscopedMutableSignature", "for 3 but exactly 3 Node",
        "Message gets the command's default scope of 3, although the command sets the scope of Node, " +
            "and Message is used by the mutable field inbox",
    )

    private val shortInbox = Problem("AlloySeqBoundTooSmall", "2 seq", "Sequences in inbox hold at most 2 elements, fewer than the 3 Node atoms")

    /**
     * By path under `practicalalloy`. No final model of a chapter misses a frame condition: the only one reported
     * is in the draft of `initiate` before its effect on `outbox` is written. No model has a fact that constrains
     * only the start of the trace, a static prime, or an integer literal out of range.
     */
    private val expected = mapOf(
        // bad_all_initiate, the book's example of a scope that hides behaviors
        "protocol-design/explicit-messages-as-signatures/leaderelection.als" to listOf(messageScope),
        "protocol-design/instance_19/leaderelection.als" to listOf(messageScope),
        "protocol-design/instance_08/leaderelection.als" to listOf(
            Problem("AlloyMissingFrameCondition", "initiate_no_effect", "initiate_no_effect does not constrain the next value of outbox"),
        ),
        // Bounded queues that are too short, in a check and in the instance that shows its counterexample
        "protocol-design/refining-the-network-model/leaderelection.als" to listOf(shortInbox),
        "protocol-design/instance_21/leaderelection.als" to listOf(shortInbox, shortInbox),
    )

    /**
     * The facts reported, at information level and so without highlighting, as constraining only the first state:
     * exactly the models' initial states, `fact init`, in every model that has one.
     */
    private fun initialStateFacts(text: String): List<String> = if (Regex("""\bfact\s+init\b""").containsMatchIn(text)) listOf("fact init") else emptyList()

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(*inspections.toTypedArray())
    }

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
        val failures = models.sortedBy { it.path }.mapNotNull { model ->
            val path = "$folder/${VfsUtilCore.getRelativePath(model, root)}"
            myFixture.configureFromExistingVirtualFile(model)
            val highlights = myFixture.doHighlighting().filter { it.inspectionToolId in ids }.sortedBy { it.startOffset }
            fun problems(severity: HighlightSeverity) = highlights.filter { it.severity == severity }
                .map { Problem(it.inspectionToolId!!, myFixture.file.text.substring(it.startOffset, it.endOffset), it.description) }
            val problems = problems(HighlightSeverity.WEAK_WARNING)
            val facts = problems(HighlightSeverity.INFORMATION).filter { it.inspection == "AlloyFactOnlyAtStart" }.map { it.text }
            listOfNotNull(
                if (problems != expected[path].orEmpty()) "$path has problems $problems, expected ${expected[path].orEmpty()}" else null,
                initialStateFacts(myFixture.file.text).let { if (facts != it) "$path has first-state facts $facts, expected $it" else null },
            ).joinToString("\n").ifEmpty { null }
        }
        assertEmpty(failures.joinToString("\n"), failures)
    }
}
