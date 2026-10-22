package com.serranofp.alloy

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.serranofp.alloy.PracticalAlloyCorpus.Problem
import com.serranofp.alloy.inspections.AlloyExactParameterScopeInspection
import com.serranofp.alloy.inspections.AlloyFactOnlyAtStartInspection
import com.serranofp.alloy.inspections.AlloyIntLiteralRangeInspection
import com.serranofp.alloy.inspections.AlloyMissingFrameConditionInspection
import com.serranofp.alloy.inspections.AlloySeqBoundTooSmallInspection
import com.serranofp.alloy.inspections.AlloyStaticPrimeInspection
import com.serranofp.alloy.inspections.AlloyUnscopedMutableSignatureInspection
import java.util.concurrent.TimeUnit

/**
 * The Alloy inspections over the behavioral models of the Practical Alloy book (see [PracticalAlloyModelsTest]):
 * the weak warnings they report are the mistakes that the book shows on purpose, and the facts they report as
 * information are its initial states. Every quick-fix of every problem, applied on its own, keeps the model
 * type-checking.
 */
class PracticalAlloyInspectionsTest : BasePlatformTestCase() {
    override fun getTestDataPath(): String = PracticalAlloyCorpus.TEST_DATA

    private val inspections = listOf(
        AlloyUnscopedMutableSignatureInspection(),
        AlloyExactParameterScopeInspection(),
        AlloySeqBoundTooSmallInspection(),
        AlloyMissingFrameConditionInspection(),
        AlloyFactOnlyAtStartInspection(),
        AlloyStaticPrimeInspection(),
        AlloyIntLiteralRangeInspection(),
    )

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

    private val orderedIds = listOf("for 4 but 20 steps", "for 4 but 1.. steps").map {
        Problem(
            "AlloyExactParameterScope", it,
            "util/ordering makes the scope of Id exactly 4, the overall scope, so configurations with fewer Id atoms are not analyzed",
        )
    }

    /**
     * The problems reported at information level, other than the initial states (see [initialStateFacts]), by
     * path under `practicalalloy`: the checks of the leader election that order the identifiers, whose overall
     * scope is the exact scope of `Id`.
     */
    private val expectedInformation = mapOf(
        "protocol-design/instance_12/leaderelection.als" to orderedIds,
        "protocol-design/verifying-the-expected-properties/leaderelection.als" to orderedIds,
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
        val models = PracticalAlloyCorpus.models(myFixture, folder)
        assertNotEmpty(models)
        val ids = inspections.map { it.shortName }.toSet()
        var fixes = 0
        val failures = models.flatMap { (path, model) ->
            myFixture.configureFromExistingVirtualFile(model)
            val problems = PracticalAlloyCorpus.problems(myFixture, ids, HighlightSeverity.WEAK_WARNING)
            val information = PracticalAlloyCorpus.problems(myFixture, ids, HighlightSeverity.INFORMATION)
            val facts = information.filter { it.inspection == "AlloyFactOnlyAtStart" }.map { it.text }
            val others = information.filter { it.inspection != "AlloyFactOnlyAtStart" }
            val broken = brokenFixes(path)
            fixes += broken.first
            listOfNotNull(
                if (problems != expected[path].orEmpty()) "$path has problems $problems, expected ${expected[path].orEmpty()}" else null,
                initialStateFacts(myFixture.file.text).let { if (facts != it) "$path has first-state facts $facts, expected $it" else null },
                if (others != expectedInformation[path].orEmpty()) "\"$path\" to listOf(${others.joinToString { it.code() }})," else null,
            ) + broken.second
        }
        // The fixes do apply to the book's models: the check is not vacuous
        assertTrue("Only $fixes fixes in $folder", fixes > 0)
        assertEmpty(failures.joinToString("\n"), failures)
    }

    /** The problems that [inspection] reports in the fixture's file, as the editor sees them, with their fixes. */
    private fun descriptors(inspection: LocalInspectionTool): List<ProblemDescriptor> {
        val manager = InspectionManager.getInstance(project)
        return ApplicationManager.getApplication().executeOnPooledThread<List<ProblemDescriptor>> {
            ReadAction.computeBlocking<List<ProblemDescriptor>, Throwable> { inspection.checkFile(myFixture.file, manager, true)?.toList().orEmpty() }
        }.get(60, TimeUnit.SECONDS)
    }

    private fun setText(text: String) {
        val document = myFixture.editor.document
        WriteCommandAction.runWriteCommandAction(project) {
            document.setText(text)
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
    }

    /**
     * How many quick-fixes the problems of the current model have, and those that, applied on their own to its
     * text, leave it failing to type-check. The text is restored after each one.
     */
    private fun brokenFixes(path: String): Pair<Int, List<String>> {
        val text = myFixture.file.text
        var count = 0
        val broken = ArrayList<String>()
        for (inspection in inspections) {
            val problems = descriptors(inspection)
            for ((i, problem) in problems.withIndex()) {
                for (k in problem.fixes.orEmpty().indices) {
                    count++
                    // Each fix is applied to a descriptor of the original text
                    if (myFixture.file.text != text) setText(text)
                    val descriptor = descriptors(inspection)[i]
                    val fix = descriptor.fixes!![k]
                    WriteCommandAction.runWriteCommandAction(project) { fix.applyFix(project, descriptor) }
                    PsiDocumentManager.getInstance(project).commitAllDocuments()
                    val fixed = myFixture.file.text
                    if (fixed == text) broken += "$path: '${fix.name}' of '${problem.descriptionTemplate}' changes nothing"
                    PracticalAlloyCorpus.parse(path, fixed).exceptionOrNull()?.let { broken += "$path: '${fix.name}' gives an error: ${it.message}" }
                }
            }
        }
        setText(text)
        return count to broken
    }
}
