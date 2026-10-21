package com.serranofp.alloy.simplifications

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class AlloyNegationTest : BasePlatformTestCase() {
    private val header = "sig A { r: set A }\none sig x, y extends A {}\npred p { some A }\nfun i: Int { #A }\nfun j: Int { #A.r }\n"

    /** A model with a predicate `subject<i>` for each of [formulas]. */
    private fun configure(formulas: Array<out String>) {
        myFixture.configureByText("model.als", header + formulas.withIndex().joinToString("") { (i, formula) -> "pred subject$i { $formula }\n" })
    }

    /** Whether [node] is the body of a `subject` predicate. */
    private fun AlloyRewriteContext.isSubject() = ancestors.isEmpty() && paragraph.text.startsWith("pred subject")

    /** Each formula of [formulas] as [negate] writes it. */
    private fun negated(vararg formulas: String): List<String> {
        configure(formulas)
        val negation = rule { node -> if (isSubject()) replace(node, negate(node)) else null }
        return myFixture.rewrites(negation).map { it.text }
    }

    /** Whether each formula of [formulas] counts as a negation. */
    private fun negations(vararg formulas: String): List<Boolean> {
        configure(formulas)
        return myFixture.onModel { source, model ->
            val found = ArrayList<Boolean>()
            val record = rule { node ->
                if (isSubject()) found += isNegation(node)
                null
            }
            AlloyRewriteWalker(source, listOf(record)).walk(model.module)
            found
        }
    }

    fun testNegationIsTakenOff() {
        assertEquals(listOf("p", "some A", "(x in y)"), negated("not p", "!some A", "not (x in y)"))
    }

    fun testComparisonsAreNegated() {
        assertEquals(
            listOf("x != y", "x = y", "x = y", "x not in y", "x in y", "x in y"),
            negated("x = y", "x != y", "x not = y", "x in y", "x not in y", "x !in y"),
        )
        assertEquals(
            listOf("i >= j", "i > j", "i =< j", "i < j", "i < j", "i =< j", "i > j", "i >= j"),
            negated("i < j", "i =< j", "i > j", "i >= j", "i !< j", "i not =< j", "i !> j", "i not >= j"),
        )
    }

    fun testMultiplicities() {
        assertEquals(listOf("no x.r", "some x.r", "not lone x.r"), negated("some x.r", "no x.r", "lone x.r"))
    }

    fun testPlainFormulas() {
        assertEquals(listOf("not p", "not (some A and no x.r)", "not all a: A | a in y"), negated("p", "some A and no x.r", "all a: A | a in y"))
    }

    fun testNegationsAreRecognized() {
        assertEquals(
            listOf(true, true, true, true, true, false, false, false, false),
            negations("not p", "x != y", "x not in y", "no A", "i !< j", "x = y", "some A", "i < j", "p"),
        )
    }
}
