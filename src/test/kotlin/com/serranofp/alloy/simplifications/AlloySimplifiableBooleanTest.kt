package com.serranofp.alloy.simplifications

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
        val fix = AlloyRewriteFix("not not q", "q", "Remove double negation")
        val descriptor = com.intellij.codeInspection.InspectionManager.getInstance(project).createProblemDescriptor(
            myFixture.file.children.last { it.text.startsWith("fact") },
            com.intellij.openapi.util.TextRange(7, 16), "Double negation", com.intellij.codeInspection.ProblemHighlightType.WEAK_WARNING, true,
        )
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) { fix.applyFix(project, descriptor) }
        assertEquals("fact { not not p }\n", myFixture.file.text.removePrefix(header))
    }
}
