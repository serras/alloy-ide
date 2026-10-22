package com.serranofp.alloy.inspections

import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.lang.annotation.HighlightSeverity
import com.serranofp.alloy.PracticalAlloyCorpus
import java.io.File

class AlloyScopeInspectionsTest : AlloyInspectionTestCase() {
    private fun fixture(path: String): String = File("$testDataPath/practicalalloy/protocol-design/$path/leaderelection.als").readText()

    override fun getTestDataPath(): String = PracticalAlloyCorpus.TEST_DATA

    private fun configure(inspection: LocalInspectionTool, text: String) {
        myFixture.enableInspections(inspection)
        myFixture.configureByText("leaderelection.als", text)
    }

    /** Applies [fix] at [scope], the last occurrence in the file, and returns the number selected for editing, if any. */
    private fun apply(scope: String, fix: String): String? = launch(myFixture.file.text.lastIndexOf(scope), fix)

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
            detailedProblems(inspection),
        )
    }

    fun testScopeOnASubsignatureScopesItsParent() {
        val inspection = AlloyUnscopedMutableSignatureInspection()
        configure(inspection, fixture("explicit-messages-as-signatures") + "\nrun candidates {} for 3 but exactly 3 Node, 4 CandidateMsg\n")
        assertEquals(listOf("for 3 but exactly 3 Node"), detailedProblems(inspection).map { it.text })
    }

    fun testMutableSignatureAndChains() {
        val inspection = AlloyUnscopedMutableSignatureInspection()
        configure(inspection, "sig Node {}\nsig Id {}\nvar sig Active in Id {}\npred p {}\nrun p for 2 Node => check {} for 3 but 2 Node")
        assertEquals(
            listOf(
                "Id gets the command's default scope of 3, although the command sets the scope of Node, " +
                    "and Id is used by the mutable signature Active",
            ),
            detailedProblems(inspection).map { it.message },
        )
        assertEquals(listOf("for 3 but 2 Node"), detailedProblems(inspection).map { it.text })
    }

    fun testAddScope() {
        val inspection = AlloyUnscopedMutableSignatureInspection()
        configure(inspection, fixture("explicit-messages-as-signatures"))
        assertEquals("3", apply("for 3 but exactly 3 Node", "Add a scope for Message"))
        assertTrue(myFixture.editor.document.text.contains("} for 3 but exactly 3 Node, 3 Message expect 0"))
        assertEmpty(detailedProblems(inspection))
    }

    fun testAddScopeAfterTheOverallScope() {
        val inspection = AlloyUnscopedMutableSignatureInspection()
        configure(inspection, "sig Node { var inbox: set Message }\nsig Message {}\nrun {} for 3 but 2 Node, 2 steps")
        apply("for 3", "Add a scope for Message")
        assertEquals("sig Node { var inbox: set Message }\nsig Message {}\nrun {} for 3 but 2 Node, 2 steps, 3 Message", myFixture.editor.document.text)
    }

    /** `A` must have its four `one` subsignatures, so its scope is raised above the overall scope of 3. */
    fun testRaisedScopeIsNotTheDefaultScope() {
        val inspection = AlloyUnscopedMutableSignatureInspection()
        configure(inspection, "sig A {}\none sig B, C, D, E extends A {}\nsig N { var m: set A }\nsig Other {}\nrun {} for 3 but 2 Other")
        assertEmpty(detailedProblems(inspection))
    }

    fun testExactParameterOfOneOfSeveralModules() {
        val inspection = AlloyExactParameterScopeInspection()
        configure(inspection, "open util/graph[State]\nopen util/ordering[State]\nsig State {}\nrun {} for 3 but 2 State")
        assertEquals(
            listOf("util/ordering makes the scope of State exactly 2, so configurations with fewer State atoms are not analyzed"),
            detailedProblems(inspection).map { it.message },
        )
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
            detailedProblems(inspection),
        )
    }

    fun testMakeWrittenScopeExact() {
        val inspection = AlloyExactParameterScopeInspection()
        configure(inspection, ordered)
        assertNull(apply("2 Id", "Write the scope of Id as exact"))
        assertTrue(myFixture.editor.document.text.endsWith("check at_most_one_leader for 3 but exactly 2 Id\n"))
        assertEquals(2, detailedProblems(inspection).size)
    }

    fun testMakeOverallScopeExact() {
        val inspection = AlloyExactParameterScopeInspection()
        configure(inspection, ordered)
        assertEquals("4", apply("for 4 but 20 steps", "Write the scope of Id as exact"))
        assertTrue(myFixture.editor.document.text.contains("check at_most_one_leader for 4 but exactly 4 Id, 20 steps expect 0"))
        assertEquals(listOf("for 4 but 1.. steps", "2 Id"), detailedProblems(inspection).map { it.text })
    }

    fun testSeqBoundTooSmall() {
        val inspection = AlloySeqBoundTooSmallInspection()
        configure(inspection, fixture("refining-the-network-model"))
        assertEquals(
            listOf(Problem(HighlightSeverity.WEAK_WARNING, "2 seq", "Sequences in inbox hold at most 2 elements, fewer than the 3 Node atoms")),
            detailedProblems(inspection),
        )
    }

    fun testRaiseSeqBound() {
        val inspection = AlloySeqBoundTooSmallInspection()
        configure(inspection, fixture("refining-the-network-model"))
        assertEquals("3", apply("2 seq", "Raise the seq bound to 3"))
        assertTrue(myFixture.editor.document.text.contains("check at_least_one_leader_fair for 3 but 3 seq expect 1"))
        assertEmpty(detailedProblems(inspection))
    }

    fun testSeqBoundWithoutWrittenSeq() {
        val inspection = AlloySeqBoundTooSmallInspection()
        configure(inspection, "sig Node { var inbox: seq Node }\nrun {} for 3 but 4 Node")
        assertEquals(listOf("for 3 but 4 Node"), detailedProblems(inspection).map { it.text })
        apply("for 3", "Raise the seq bound to 4")
        assertEquals("sig Node { var inbox: seq Node }\nrun {} for 3 but 4 Node, 4 seq", myFixture.editor.document.text)
    }

    fun testStaticSeqFieldIsNotReported() {
        val inspection = AlloySeqBoundTooSmallInspection()
        configure(inspection, "sig Node { path: seq Node }\nrun {} for 3 but 2 seq")
        assertEmpty(detailedProblems(inspection))
    }

    fun testAddScopePreview() {
        val inspection = AlloyUnscopedMutableSignatureInspection()
        val model = "sig Node { var inbox: set Message }\nsig Message {}\nrun {} for 3 but 2 Node"
        configure(inspection, model)
        myFixture.doHighlighting()
        myFixture.editor.caretModel.moveToOffset(model.indexOf("for 3"))
        val preview = myFixture.getIntentionPreviewText(myFixture.findSingleIntention("Add a scope for Message"))
        assertEquals("sig Node { var inbox: set Message }\nsig Message {}\nrun {} for 3 but 2 Node, 3 Message", preview)
        // No template is started by the preview, and the file is unchanged
        assertNull(TemplateManagerImpl.getTemplateState(myFixture.editor))
        assertEquals(model, myFixture.editor.document.text)
    }
}
