package com.serranofp.alloy.simplifications

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import edu.mit.csail.sdg.ast.Expr
import edu.mit.csail.sdg.ast.ExprList
import edu.mit.csail.sdg.ast.ExprUnary
import edu.mit.csail.sdg.ast.VisitQuery

class AlloySourceExprTest : BasePlatformTestCase() {
    /** How the nodes that the walker offers to rules, and that [select] picks, read: their text, and whether they are parenthesized and written. */
    private fun offered(select: (Expr) -> Boolean): List<String> = myFixture.onModel { source, model ->
        val seen = ArrayList<String>()
        val record = rule { node ->
            if (select(node)) {
                val text = source(node)
                seen += "${text?.text}${if (text?.parenthesized == true) " (parenthesized)" else ""}${if (written(node)) "" else " (not written)"}"
            }
            null
        }
        AlloyRewriteWalker(source, listOf(record)).walk(model.module)
        seen
    }

    /** The nodes of the model's facts that [select] picks, written in any paragraph of the file. */
    private fun writtenAnywhere(select: (Expr) -> Boolean): List<String> = myFixture.onModel { source, model ->
        val found = ArrayList<String>()
        for (fact in model.module.allFacts) {
            fact.b.accept(object : VisitQuery<Unit>() {
                override fun visit(x: ExprUnary): Unit? {
                    if (select(x) && source.inFile(x.pos)) {
                        val paragraph = source.paragraph(source.range(x.pos))
                        if (paragraph != null && source.isWritten(x, paragraph)) found += source.source(x)!!.text
                    }
                    return super.visit(x)
                }
            })
        }
        found
    }

    private val not: (Expr) -> Boolean = { it is ExprUnary && it.op == ExprUnary.Op.NOT }

    fun testDoubleParenthesesWiden() {
        myFixture.configureByText("model.als", "sig A {}\nfact { ((not some A)) }\n")
        assertEquals(listOf("((not some A)) (parenthesized)"), offered(not))
    }

    fun testOperandsKeepTheirOwnParentheses() {
        myFixture.configureByText("model.als", "sig A {}\nfact { (some A) and (no A) }\n")
        assertEquals(listOf("(some A) and (no A)"), offered { it is ExprList })
        assertEquals(
            listOf("(some A) (parenthesized)", "(no A) (parenthesized)"),
            offered { it is ExprUnary && (it.op == ExprUnary.Op.SOME || it.op == ExprUnary.Op.NO) },
        )
    }

    fun testClosingBracketsAreIncluded() {
        myFixture.configureByText("model.als", "sig A {}\npred p[a: A] { some a }\nfact { not (all a: A | p[a]) }\n")
        assertEquals(listOf("not (all a: A | p[a])"), offered(not))
    }

    fun testWrittenNegations() {
        myFixture.configureByText("model.als", "sig A {}\nfact { not some A and !no A }\n")
        assertEquals(listOf("not some A", "!no A"), offered(not))
    }

    fun testMacroExpansionIsNotWritten() {
        myFixture.configureByText("model.als", "sig A {}\nlet twice[x] { not not x }\nfact { twice[some A] }\n")
        assertEmpty(offered(not))
        // Not even in the macro's own paragraph
        assertEmpty(writtenAnywhere(not))
    }

    fun testMacroParameterArgumentIsNotWritten() {
        myFixture.configureByText("model.als", "sig A {}\nlet neg[x] { not x }\nfact { neg[not some A] }\n")
        // The argument is reached through the expansion only
        assertEmpty(offered(not))
    }

    fun testSequenceIsNotWritten() {
        myFixture.configureByText("model.als", "var sig A {}\nfact { some A ; no A }\n")
        assertEquals(
            listOf("some A ; no A (not written)", "; no A (not written)"),
            offered { it is ExprList || (it is ExprUnary && it.op == ExprUnary.Op.AFTER) },
        )
    }

    fun testOpenedModuleIsNotWritten() {
        myFixture.addFileToProject("a.als", "module a\nsig A {}\nfact { not some A }\n")
        myFixture.configureByText("b.als", "open a\nfact { no A }\n")
        assertEmpty(offered(not))
        assertEmpty(writtenAnywhere(not))
    }
}
