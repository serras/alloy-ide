package com.serranofp.alloy.inspections

import edu.mit.csail.sdg.alloy4.A4Reporter
import edu.mit.csail.sdg.ast.Expr
import edu.mit.csail.sdg.ast.Func
import edu.mit.csail.sdg.ast.Sig
import edu.mit.csail.sdg.parser.CompModule
import edu.mit.csail.sdg.parser.CompUtil
import junit.framework.TestCase

class AlloyFrameAnalysisTest : TestCase() {
    private val analysis = AlloyFrameAnalysis()

    private fun fixture(path: String): CompModule =
        CompUtil.parseEverything_fromFile(A4Reporter.NOP, null, "src/test/testData/practicalalloy/$path")

    private fun model(text: String): CompModule = CompUtil.parseEverything_fromString(A4Reporter.NOP, text)

    private fun CompModule.pred(name: String): Func = allFunc.single { it.label == "this/$name" }

    private fun name(relation: Expr): String = if (relation is Sig.Field) relation.label else (relation as Sig).label.removePrefix("this/")

    private fun constrained(module: CompModule, pred: String): Set<String> =
        analysis.summary(module.pred(pred)).constrains.map(::name).toSet()

    private val messages = "protocol-design/explicit-messages-as-signatures/leaderelection.als"

    fun testEventsGuardsAndProperties() {
        val module = fixture(messages)
        assertTrue(analysis.isEvent(module.pred("initiate")))
        assertFalse(analysis.isEvent(module.pred("initiate_enabled")))
        assertFalse(analysis.isEvent(module.pred("fairness")))
    }

    fun testEveryBranchOfAConditionalEffectCounts() {
        val module = fixture(messages)
        assertTrue(analysis.isEvent(module.pred("processCandidate")))
        assertEquals(setOf("inbox"), constrained(module, "processCandidate"))
    }

    fun testFrameMacros() {
        val module = fixture("behavioral-topics/macros/a-macro-for-frame-conditions/filesharing.als")
        assertEquals(setOf("uploaded", "trashed", "shared"), constrained(module, "upload"))
        assertEquals(setOf("uploaded", "trashed", "shared"), constrained(module, "stutter"))
    }

    fun testHelperPredicates() {
        val module = model(
            """
            sig N { var f, g, h: set N }
            pred keep_f { f' = f }
            pred keep_g[n: N] { n.g' = n.g }
            pred e { keep_f and (all n: N | keep_g[n]) and some h }
            pred later { after (some h) }
            """.trimIndent(),
        )
        assertEquals(setOf("f", "g"), constrained(module, "e"))
        assertTrue(analysis.isEvent(module.pred("e")))
        // Everything under `after` is about the next state
        assertEquals(setOf("h"), constrained(module, "later"))
        assertTrue(analysis.isEvent(module.pred("later")))
    }

    fun testRecursiveCallsTerminate() {
        val module = model("sig N { var f, g: set N }\npred a { f' = f and b }\npred b { g' = g and a }")
        assertEquals(setOf("f", "g"), constrained(module, "a"))
        assertEquals(setOf("f", "g"), constrained(module, "b"))
    }

    fun testSequencesAreScenarios() {
        val module = model(
            """
            sig N { var f: set N }
            pred e { f' = f }
            pred scenario { e ; e ; some f }
            pred next { after e }
            """.trimIndent(),
        )
        // As the analyzer desugars it, `e ; e` is `e and after e`
        assertEquals("AND[this/e, after AND[this/e, after some (this/N <: f)]]", module.pred("scenario").body.toString())
        assertFalse(analysis.isEvent(module.pred("scenario")))
        assertTrue(analysis.isEvent(module.pred("next")))
    }

    fun testFunctionsAreNotEvents() {
        val module = model("sig N { var f: set N }\nfun later: set N { N.f' }")
        assertFalse(analysis.isEvent(module.allFunc.single { it.label == "this/later" }))
    }
}
