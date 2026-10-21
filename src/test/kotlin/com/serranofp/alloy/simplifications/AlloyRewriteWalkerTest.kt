package com.serranofp.alloy.simplifications

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import edu.mit.csail.sdg.ast.ExprUnary

class AlloyRewriteWalkerTest : BasePlatformTestCase() {
    private val isNot: (edu.mit.csail.sdg.ast.Expr) -> Boolean = { it is ExprUnary && it.op == ExprUnary.Op.NOT }

    fun testEveryWrittenNegationIsVisitedOnce() {
        myFixture.configureByText(
            "model.als",
            """
            sig A { f: set A } { not some f }
            fact { not some A }
            pred p { !no A }
            fun g: set A { { x: A | not x in A } }
            assert a { not lone A }
            let neg[x] { not x }
            fact { neg[some A] }
            run { not one A } for 3
            check { not (some A and no A) } for 3
            """.trimIndent() + "\n",
        )
        val visited = myFixture.onModel { source, model ->
            val seen = ArrayList<String>()
            val count = rule { node ->
                if (isNot(node) && written(node)) seen += source(node)!!.text
                null
            }
            AlloyRewriteWalker(source, listOf(count)).walk(model.module)
            seen
        }
        assertSameElements(
            visited,
            "not some f", "not some A", "!no A", "not x in A", "not lone A", "not one A", "not (some A and no A)",
        )
        assertEquals(visited.size, visited.toSet().size)
    }

    fun testNestedMatchesYieldTheOutermost() {
        myFixture.configureByText("model.als", "sig A {}\nfact { not not not some A }\nfact { some A and not no A }\n")
        val dropNot = rule { node -> if (isNot(node)) replace(node, slice((node as ExprUnary).sub), node) else null }
        val results = myFixture.rewrites(dropNot)
        assertEquals(listOf("not not not some A" to "not not some A", "not no A" to "no A"), results.map { myFixture.file.text.substring(it.range.startOffset, it.range.endOffset) to it.text })
    }

    fun testAncestorsAndPosition() {
        myFixture.configureByText("model.als", "sig A {}\nfact { some A implies not no A }\n")
        val seen = myFixture.onModel { source, model ->
            val seen = ArrayList<String>()
            val record = rule { node ->
                if (isNot(node)) seen += "${parent?.javaClass?.simpleName} $position"
                null
            }
            AlloyRewriteWalker(source, listOf(record)).walk(model.module)
            seen
        }
        assertEquals(listOf("ExprBinary ${AlloyPosition(AlloyPrecedence.IMPLIES, bindAllowed = true, rightOpen = true)}"), seen)
    }
}
