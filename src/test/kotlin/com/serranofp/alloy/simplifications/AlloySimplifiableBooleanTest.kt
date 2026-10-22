package com.serranofp.alloy.simplifications

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.TextRange
import com.intellij.psi.SmartPointerManager
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
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
                document.replaceString(document.text.indexOf("fact { some Bogus }"), document.textLength, "")
            }
        }
        com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
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

    fun testFixDoesNothingAfterAnEdit() {
        model("fact { not not p }\n")
        myFixture.doHighlighting()
        val start = myFixture.file.text.indexOf("not not p")
        val range = SmartPointerManager.getInstance(project).createSmartPsiFileRangePointer(myFixture.file, TextRange(start, start + 9))
        val fix = AlloyRewriteFix(range, "not not q", "q", "Remove double negation")
        val descriptor = InspectionManager.getInstance(project).createProblemDescriptor(
            myFixture.file.children.last { it.text.startsWith("fact") },
            TextRange(7, 16), "Double negation", ProblemHighlightType.WEAK_WARNING, true,
        )
        WriteCommandAction.runWriteCommandAction(project) { fix.applyFix(project, descriptor) }
        assertEquals("fact { not not p }\n", myFixture.file.text.removePrefix(header))
    }

    fun testFixesOfOneParagraphFollowTheEarlierOnes() {
        model("fact { not not p and not not q and not not a }\n")
        val manager = InspectionManager.getInstance(project)
        val descriptors = ApplicationManager.getApplication().executeOnPooledThread<List<ProblemDescriptor>> {
            ReadAction.computeBlocking<List<ProblemDescriptor>, Throwable> { inspection.checkFile(myFixture.file, manager, true)!!.toList() }
        }.get(60, TimeUnit.SECONDS)
        // As a batch fix may apply them: each after the ones before it, which moved its text
        for (descriptor in descriptors.sortedBy { it.textRangeInElement.startOffset }) {
            WriteCommandAction.runWriteCommandAction(project) { descriptor.fixes!!.single().applyFix(project, descriptor) }
        }
        assertEquals("fact { p and q and a }\n", myFixture.file.text.removePrefix(header))
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
