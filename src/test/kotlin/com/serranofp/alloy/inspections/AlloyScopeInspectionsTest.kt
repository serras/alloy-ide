package com.serranofp.alloy.inspections

import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

class AlloyScopeInspectionsTest : BasePlatformTestCase() {
    private data class Problem(val severity: HighlightSeverity, val text: String, val message: String)

    override fun setUp() {
        super.setUp()
        TemplateManagerImpl.setTemplateTesting(testRootDisposable)
    }

    private fun fixture(path: String): String = File("src/test/testData/practicalalloy/protocol-design/$path/leaderelection.als").readText()

    private fun configure(inspection: LocalInspectionTool, text: String) {
        myFixture.enableInspections(inspection)
        myFixture.configureByText("leaderelection.als", text)
    }

    private fun problems(inspection: LocalInspectionTool): List<Problem> =
        myFixture.doHighlighting().filter { it.inspectionToolId == inspection.shortName }.sortedBy { it.startOffset }.map {
            Problem(it.severity, myFixture.file.text.substring(it.startOffset, it.endOffset), it.description)
        }

    /** Applies [fix] at [scope], the last occurrence in the file, and returns the number selected for editing, if any. */
    private fun apply(scope: String, fix: String): String? {
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.lastIndexOf(scope))
        myFixture.launchAction(myFixture.findSingleIntention(fix))
        val template = TemplateManagerImpl.getTemplateState(myFixture.editor) ?: return null
        val selected = template.currentVariableRange?.substring(myFixture.editor.document.text)
        template.gotoEnd(false)
        return selected
    }

    fun testUnscopedMutableSignature() {
        val inspection = AlloyUnscopedMutableSignatureInspection()
        configure(inspection, fixture("explicit-messages-as-signatures"))
        assertEquals(
            listOf(
                Problem(
                    HighlightSeverity.WEAK_WARNING, "for 3 but exactly 3 Node",
                    "Message gets the command's default scope of 3, although the command sets the scope of Node, " +
                        "and Message is used by the mutable field inbox",
                ),
            ),
            problems(inspection),
        )
    }

    fun testScopeOnASubsignatureScopesItsParent() {
        val inspection = AlloyUnscopedMutableSignatureInspection()
        configure(inspection, fixture("explicit-messages-as-signatures") + "\nrun candidates {} for 3 but exactly 3 Node, 4 CandidateMsg\n")
        assertEquals(listOf("for 3 but exactly 3 Node"), problems(inspection).map { it.text })
    }

    fun testMutableSignatureAndChains() {
        val inspection = AlloyUnscopedMutableSignatureInspection()
        configure(inspection, "sig Node {}\nsig Id {}\nvar sig Active in Id {}\npred p {}\nrun p for 2 Node => check {} for 3 but 2 Node")
        assertEquals(
            listOf(
                "Id gets the command's default scope of 3, although the command sets the scope of Node, " +
                    "and Id is used by the mutable signature Active",
            ),
            problems(inspection).map { it.message },
        )
        assertEquals(listOf("for 3 but 2 Node"), problems(inspection).map { it.text })
    }

    fun testAddScope() {
        val inspection = AlloyUnscopedMutableSignatureInspection()
        configure(inspection, fixture("explicit-messages-as-signatures"))
        assertEquals("3", apply("for 3 but exactly 3 Node", "Add a scope for Message"))
        assertTrue(myFixture.editor.document.text.contains("} for 3 but exactly 3 Node, 3 Message expect 0"))
        assertEmpty(problems(inspection))
    }

    fun testAddScopeAfterTheOverallScope() {
        val inspection = AlloyUnscopedMutableSignatureInspection()
        configure(inspection, "sig Node { var inbox: set Message }\nsig Message {}\nrun {} for 3 but 2 Node, 2 steps")
        apply("for 3", "Add a scope for Message")
        assertEquals("sig Node { var inbox: set Message }\nsig Message {}\nrun {} for 3 but 2 Node, 2 steps, 3 Message", myFixture.editor.document.text)
    }

    private val ordered = fixture("verifying-the-expected-properties") + "\ncheck at_most_one_leader for 3 but 2 Id\n"

    fun testExactParameterScope() {
        val inspection = AlloyExactParameterScopeInspection()
        configure(inspection, ordered)
        val consequence = "so configurations with fewer Id atoms are not analyzed"
        assertEquals(
            listOf(
                Problem(HighlightSeverity.INFORMATION, "for 4 but 20 steps", "util/ordering makes the scope of Id exactly 4, the overall scope, $consequence"),
                Problem(HighlightSeverity.INFORMATION, "for 4 but 1.. steps", "util/ordering makes the scope of Id exactly 4, the overall scope, $consequence"),
                Problem(HighlightSeverity.WEAK_WARNING, "2 Id", "util/ordering makes the scope of Id exactly 2, $consequence"),
            ),
            problems(inspection),
        )
    }

    fun testMakeWrittenScopeExact() {
        val inspection = AlloyExactParameterScopeInspection()
        configure(inspection, ordered)
        assertNull(apply("2 Id", "Write the scope of Id as exact"))
        assertTrue(myFixture.editor.document.text.endsWith("check at_most_one_leader for 3 but exactly 2 Id\n"))
        assertEquals(2, problems(inspection).size)
    }

    fun testMakeOverallScopeExact() {
        val inspection = AlloyExactParameterScopeInspection()
        configure(inspection, ordered)
        assertEquals("4", apply("for 4 but 20 steps", "Write the scope of Id as exact"))
        assertTrue(myFixture.editor.document.text.contains("check at_most_one_leader for 4 but exactly 4 Id, 20 steps expect 0"))
        assertEquals(listOf("for 4 but 1.. steps", "2 Id"), problems(inspection).map { it.text })
    }

    fun testSeqBoundTooSmall() {
        val inspection = AlloySeqBoundTooSmallInspection()
        configure(inspection, fixture("refining-the-network-model"))
        assertEquals(
            listOf(Problem(HighlightSeverity.WEAK_WARNING, "2 seq", "Sequences in inbox hold at most 2 elements, fewer than the 3 Node atoms")),
            problems(inspection),
        )
    }

    fun testRaiseSeqBound() {
        val inspection = AlloySeqBoundTooSmallInspection()
        configure(inspection, fixture("refining-the-network-model"))
        assertEquals("3", apply("2 seq", "Raise the seq bound to 3"))
        assertTrue(myFixture.editor.document.text.contains("check at_least_one_leader_fair for 3 but 3 seq expect 1"))
        assertEmpty(problems(inspection))
    }

    fun testSeqBoundWithoutWrittenSeq() {
        val inspection = AlloySeqBoundTooSmallInspection()
        configure(inspection, "sig Node { var inbox: seq Node }\nrun {} for 3 but 4 Node")
        assertEquals(listOf("for 3 but 4 Node"), problems(inspection).map { it.text })
        apply("for 3", "Raise the seq bound to 4")
        assertEquals("sig Node { var inbox: seq Node }\nrun {} for 3 but 4 Node, 4 seq", myFixture.editor.document.text)
    }

    fun testStaticSeqFieldIsNotReported() {
        val inspection = AlloySeqBoundTooSmallInspection()
        configure(inspection, "sig Node { path: seq Node }\nrun {} for 3 but 2 seq")
        assertEmpty(problems(inspection))
    }
}
