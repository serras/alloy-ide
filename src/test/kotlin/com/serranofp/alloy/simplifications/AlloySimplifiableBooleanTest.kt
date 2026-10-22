package com.serranofp.alloy.simplifications

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.SmartPointerManager
import com.serranofp.alloy.inspections.assertTypeChecks
import java.util.concurrent.TimeUnit

class AlloySimplifiableBooleanTest : AlloySimplificationTestCase() {
    override val inspection = AlloySimplifiableBooleanInspection()

    fun testDeMorganCollapse() {
        model("fact { not ((not a) or (not b)) }\n")
        assertEquals(listOf("not ((not a) or (not b))" to "Negated disjunction of negations"), problems())
        assertEquals("fact { a and b }\n", fix("not ((not", "Simplify to 'a and b'"))
        assertEmpty(problems())
    }

    fun testDeMorganWithAnyNumberOfOperands() {
        assertEquals(listOf("a or b or c", "a && b && c"), fixed("not (not a and not b and not c)", "!(!a || !b || !c)"))
    }

    fun testNegatedComparisons() {
        assertEquals(
            listOf("x != y", "x not in y", "x = y", "x in y", "i >= j", "i > j", "i =< j", "i < j"),
            fixed(
                "not (x = y)", "!(x in y)", "not (x != y)", "not (x not in y)",
                "not (i < j)", "not (i =< j)", "not (i > j)", "not (i >= j)",
                params = "[x, y: Node, i, j: Int]",
            ),
        )
    }

    fun testNegatedImplication() {
        assertEquals(listOf("p and not q", "some Node.inbox and no Node.outbox"), fixed("not (p implies q)", "not (some Node.inbox implies some Node.outbox)"))
    }

    fun testImplicationWithAnElse() {
        assertNotReported("not (p implies q else r)")
    }

    fun testQuadrupleNegation() {
        model("fact { not not not not some Node }\n")
        assertEquals(listOf("not not not not some Node"), reported())
        assertEquals("fact { not not some Node }\n", fix("not not not", "Remove double negation"))
        assertEquals(listOf("not not some Node"), reported())
        assertEquals("fact { some Node }\n", fix("not not", "Remove double negation"))
        assertEmpty(problems())
    }

    fun testParenthesesKept() {
        model("pred subject[x, y: Node] { x.succ = y implies not not (a or b) }\n")
        assertEquals(listOf("not not (a or b)"), reported())
        assertEquals("pred subject[x, y: Node] { x.succ = y implies (a or b) }\n", fix("not not", "Remove double negation"))
    }

    fun testParenthesesAddedWhereNeeded() {
        model("fact { c and not (not a and not b) }\n")
        assertEquals("fact { c and (a or b) }\n", fix("not (not", "Simplify to 'a or b'"))
    }

    fun testConverseImplications() {
        model("fact {\n  p implies q\n  q implies p\n}\n")
        assertEquals(listOf("p implies q\n  q implies p" to "Converse implications"), problems())
        assertEquals("fact {\n  p iff q\n}\n", fix("p implies q", "Replace with 'iff'"))
        assertEmpty(problems())
    }

    fun testConverseImplicationsInAConjunction() {
        model("fact { a and (p implies q) and (q implies p) }\n")
        assertEquals(listOf("(p implies q) and (q implies p)"), reported())
        assertEquals("fact { a and (p iff q) }\n", fix("(p implies", "Replace with 'iff'"))
    }

    fun testImplicationsThatAreNotConverse() {
        model("fact {\n  p implies q\n  q implies r\n}\nfact {\n  p implies q\n  r\n  q implies p\n}\n")
        assertEmpty(problems())
    }

    fun testOtherLinesOfTheBlockAreStillReported() {
        model("fact {\n  p implies q\n  q implies p\n  not not r\n}\n")
        assertEquals(listOf("p implies q\n  q implies p", "not not r"), reported())
    }

    fun testTypeErrorReportsNothing() {
        model("fact { not not some Node }\nfact { some Bogus }\n")
        assertEmpty(problems())
        myFixture.editor.document.let { document ->
            WriteCommandAction.runWriteCommandAction(project) {
                document.replaceString(document.text.indexOf("fact { some Bogus }"), document.textLength, "")
            }
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals(listOf("not not some Node"), reported())
    }

    fun testOpenedModule() {
        val a = myFixture.addFileToProject("a.als", "module a\nsig A {}\nfact { not not some A }\n")
        myFixture.configureByText("b.als", "open a\nfact { some A }\n")
        assertEmpty(problems())
        myFixture.configureFromExistingVirtualFile(a.virtualFile)
        assertEquals(listOf("not not some A"), reported())
    }

    fun testNegationFromAMacroParameter() {
        model("let neg[x] { not x }\nfact { neg[not some Node] }\n")
        assertEmpty(problems())
    }

    fun testNegationSplitBetweenAFactAndAMacro() {
        model("let neg[x] { not x }\nfact { not neg[some Node] }\n")
        assertEmpty(problems())
    }

    fun testMacroDeclaration() {
        model("let twice[x] { not not x }\nfact { twice[some Node] }\n")
        assertEmpty(problems())
        model("let twice[x] { not not x }\n")
        assertEmpty(problems())
    }

    fun testSuppressForParagraph() {
        model("fact { not not p }\n\nfact { not not q }\n")
        assertEquals(listOf("not not p", "not not q"), reported())
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("not not p"))
        myFixture.launchAction(myFixture.availableIntentions.first { it.text == "Suppress for paragraph" })
        assertTrue(myFixture.editor.document.text.contains("\n// noinspection AlloySimplifiableBoolean\nfact { not not p }"))
        assertEquals(listOf("not not q"), reported())
    }

    /** A fix of `not not p` to `q`, built by hand for a problem reported on [original], applied to the model. */
    private fun applyHandBuiltFix(original: String) {
        val start = myFixture.file.text.indexOf("not not p")
        val range = SmartPointerManager.getInstance(project).createSmartPsiFileRangePointer(myFixture.file, TextRange(start, start + 9))
        val fix = AlloyRewriteFix(range, original, "q", "Remove double negation")
        val descriptor = InspectionManager.getInstance(project).createProblemDescriptor(
            myFixture.file.children.last { it.text.startsWith("fact") },
            TextRange(7, 16), "Double negation", ProblemHighlightType.WEAK_WARNING, true,
        )
        WriteCommandAction.runWriteCommandAction(project) { fix.applyFix(project, descriptor) }
    }

    fun testFixDoesNothingAfterAnEdit() {
        model("fact { not not p }\n")
        myFixture.doHighlighting()
        applyHandBuiltFix(original = "not not q")
        assertEquals("fact { not not p }\n", myFixture.file.text.removePrefix(header))
        // The same fix, for the text it was reported on, does apply
        applyHandBuiltFix(original = "not not p")
        assertEquals("fact { q }\n", myFixture.file.text.removePrefix(header))
    }

    /** The problems that [inspection] reports, as a batch run sees them. */
    private fun descriptors(): List<ProblemDescriptor> {
        val manager = InspectionManager.getInstance(project)
        return ApplicationManager.getApplication().executeOnPooledThread<List<ProblemDescriptor>> {
            ReadAction.computeBlocking<List<ProblemDescriptor>, Throwable> { inspection.checkFile(myFixture.file, manager, true)!!.toList() }
        }.get(60, TimeUnit.SECONDS)
    }

    private fun ProblemDescriptor.applySingleFix() {
        WriteCommandAction.runWriteCommandAction(project) { fixes!!.single().applyFix(project, this) }
    }

    fun testFixOfTextEditedSinceItWasReported() {
        model("fact { not not p }\nfact { not not q }\n")
        val (first, second) = descriptors()
        val document = myFixture.editor.document
        WriteCommandAction.runWriteCommandAction(project) {
            document.replaceString(document.text.indexOf("not not p"), document.text.indexOf("not not p") + 9, "not not r")
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
        first.applySingleFix()
        second.applySingleFix()
        // The edited text is left alone; the other fix still finds its text, which moved
        assertEquals("fact { not not r }\nfact { q }\n", myFixture.file.text.removePrefix(header))
    }

    fun testFixesOfOneParagraphFollowTheEarlierOnes() {
        model("fact { not not p and not not q and not not a }\n")
        // As a batch fix may apply them: each after the ones before it, which moved its text
        for (descriptor in descriptors().sortedBy { it.textRangeInElement.startOffset }) descriptor.applySingleFix()
        assertEquals("fact { p and q and a }\n", myFixture.file.text.removePrefix(header))
    }

    fun testFixAllInTheFile() {
        model("fact { not not p and not not q }\nfact { not (not a or not b) }\n")
        myFixture.doHighlighting()
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("not not p"))
        myFixture.launchAction(myFixture.findSingleIntention("Fix all 'Boolean formula can be simplified' problems in file"))
        assertEquals("fact { p and q }\nfact { a and b }\n", myFixture.file.text.removePrefix(header))
        myFixture.assertTypeChecks()
        assertEmpty(problems())
    }

    fun testLetBody() {
        model("pred subject { let x = Node | not not some x.inbox }\n")
        assertEquals(listOf("not not some x.inbox"), reported())
        assertEquals("pred subject { let x = Node | some x.inbox }\n", fix("not not", "Remove double negation"))
    }

    fun testBranchesOfAnImplicationWithAnElse() {
        model("pred subject { p implies not not q else not not r }\n")
        assertEquals(listOf("not not q", "not not r"), reported())
        assertEquals("pred subject { p implies q else not not r }\n", fix("not not q", "Remove double negation"))
        assertEquals("pred subject { p implies q else r }\n", fix("not not r", "Remove double negation"))
    }

    fun testComprehensionBody() {
        model("pred subject { some { n: Node | not not some n.inbox } }\n")
        assertEquals("pred subject { some { n: Node | some n.inbox } }\n", fix("not not", "Remove double negation"))
    }

    fun testSignatureFactWithItsImplicitThis() {
        model("sig A { f: set A } { not not some f }\n")
        assertEquals(listOf("not not some f"), reported())
        assertEquals("sig A { f: set A } { some f }\n", fix("not not", "Remove double negation"))
    }

    fun testAnonymousCommandBody() {
        model("run { not not p } for 3\n")
        assertEquals("run { p } for 3\n", fix("not not", "Remove double negation"))
    }

    fun testCommentBetweenConverseImplicationsKeepsThem() {
        // The fix would drop the comment
        model("fact {\n  p implies q\n  // keep\n  q implies p\n}\n")
        assertEmpty(problems())
    }

    fun testConverseImplicationsBeforeALetLine() {
        model("fact {\n  p implies q\n  q implies p\n  let x = P, y = Q | not not some x + y\n}\n")
        assertEquals(listOf("p implies q\n  q implies p", "not not some x + y"), reported())
    }

    /** The analyzer counts a tab as one column, and a character outside the BMP as two, as the document does. */
    fun testPositionsAfterTabsAndSupplementaryCharacters() {
        model("fact {\t-- 😀 😀\n\t/* 😀 */ not not p }\n")
        assertEquals(listOf("not not p"), reported())
        assertEquals("fact {\t-- 😀 😀\n\t/* 😀 */ p }\n", fix("not not", "Remove double negation"))
    }

    fun testFixPreview() {
        model("fact { some Node and not not p }\n")
        myFixture.doHighlighting()
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("not not p"))
        assertEquals("p", myFixture.getIntentionPreviewText(myFixture.findSingleIntention("Remove double negation")))
    }

    fun testShiftNeedsNoParentheses() {
        assertEquals(listOf("i != i << 1", "i > i >> 1"), fixed("not (i = i << 1)", "not (i =< i >> 1)", params = "[i: Int]"))
    }

    fun testRedundantParenthesesDropped() {
        // Where the replacement is a node in parentheses that its place does not need; operands keep theirs
        assertEquals(listOf("a or b", "a or b"), fixed("not (not (a or b))", "(not not (a or b))"))
    }
}
