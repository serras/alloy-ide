package com.serranofp.alloy.simplifications

import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import edu.mit.csail.sdg.ast.ExprLet
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

    fun testPosition() {
        myFixture.configureByText("model.als", "sig A {}\nfact { some A implies not no A }\n")
        val seen = myFixture.onModel { source, model ->
            val seen = ArrayList<String>()
            val record = rule { node ->
                if (isNot(node)) seen += "$position"
                null
            }
            AlloyRewriteWalker(source, listOf(record)).walk(model.module)
            seen
        }
        assertEquals(listOf("${AlloyPosition(AlloyPrecedence.IMPLIES, bindAllowed = true, rightOpen = true)}"), seen)
    }

    fun testOperandWithoutTextIsLookedIntoAfterAMatch() {
        myFixture.configureByText("model.als", "sig A {}\nfact { let a = A, b = A | not not some b }\n")
        // Rewrites the first `A` to itself: the second `let`, which has no text of its own, is still looked into
        val first = rule { node -> (node as? ExprLet)?.takeIf { source(it) != null }?.let { replace(it.expr, slice(it.expr)) } }
        val dropNot = rule { node -> if (isNot(node)) replace(node, slice((node as ExprUnary).sub), node) else null }
        assertEquals(listOf("A", "not some b"), myFixture.rewrites(first, dropNot).map { it.text })
    }

    fun testWalkIsCancellable() {
        myFixture.configureByText("model.als", "sig A {}\nfact { not not some A }\n")
        val indicator = EmptyProgressIndicator()
        val matched = myFixture.onModel { source, model ->
            // With the ranges of the nodes known, as for the second inspection that walks a model, little else checks
            AlloyRewriteWalker(source, emptyList()).walk(model.module)
            // The first node cancels the walk: no other one is matched
            var matched = 0
            val cancel = rule { _ ->
                if (matched++ == 0) indicator.cancel()
                null
            }
            try {
                ProgressManager.getInstance().runProcess({ AlloyRewriteWalker(source, listOf(cancel)).walk(model.module) }, indicator)
            } catch (_: ProcessCanceledException) {
            }
            matched
        }
        assertEquals(1, matched)
    }
}
