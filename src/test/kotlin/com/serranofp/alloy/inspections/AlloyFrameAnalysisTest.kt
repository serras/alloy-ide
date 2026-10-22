package com.serranofp.alloy.inspections

import com.serranofp.alloy.PracticalAlloyCorpus
import edu.mit.csail.sdg.alloy4.A4Reporter
import edu.mit.csail.sdg.ast.Expr
import edu.mit.csail.sdg.ast.ExprUnary
import edu.mit.csail.sdg.ast.Func
import edu.mit.csail.sdg.ast.Sig
import edu.mit.csail.sdg.ast.VisitQuery
import edu.mit.csail.sdg.parser.CompModule
import edu.mit.csail.sdg.parser.CompUtil
import junit.framework.TestCase

class AlloyFrameAnalysisTest : TestCase() {
    private val analysis = AlloyFrameAnalysis()

    private fun fixture(path: String): CompModule =
        CompUtil.parseEverything_fromFile(A4Reporter.NOP, null, "${PracticalAlloyCorpus.TEST_DATA}/practicalalloy/$path")

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

    /** The analyzer's convention that [AlloyFrameAnalysis.isSequence] relies on: the `after` of `;` is at its one column. */
    fun testSequenceAfterIsAtTheColumnOfTheSemicolon() {
        val text = "sig N { var f: set N }\npred e { f' = f }\npred scenario { e ; e }\npred next { after e }"
        val module = model(text)
        fun after(pred: String): ExprUnary {
            val afters = mutableListOf<ExprUnary>()
            module.pred(pred).body.accept(object : VisitQuery<Unit>() {
                override fun visit(x: ExprUnary): Unit? {
                    if (x.op == ExprUnary.Op.AFTER) afters += x
                    return super.visit(x)
                }
            })
            return afters.single()
        }
        val sequence = after("scenario").pos
        val line = text.lines()[2]
        assertEquals(listOf(3, line.indexOf(';') + 1, 3, line.indexOf(';') + 1), listOf(sequence.y, sequence.x, sequence.y2, sequence.x2))
        assertTrue(AlloyFrameAnalysis.isSequence(after("scenario")))
        assertFalse(AlloyFrameAnalysis.isSequence(after("next")))
    }

    /**
     * The meta model stands for every relation, even where its `value` fields are not `var`: they are only as
     * mutable as their owner signature, and no signature here is `var`.
     */
    fun testFrameOverTheMetaModelWithOnlyVarFields() {
        val module = model(
            """
            sig Token {}
            sig File { var shared: set Token, var owner: lone Token }
            pred share[t: Token] {
              shared' = shared + File->t
              all v: var$ - File${'$'}shared | v.value = v.value'
            }
            pred stutter { all v: var$ | v.value = v.value' }
            """.trimIndent(),
        )
        assertTrue(analysis.isEvent(module.pred("stutter")))
        assertTrue(analysis.isEvent(module.pred("share")))
        val constrained = analysis.summary(module.pred("share")).constrains
        assertTrue(constrained.any { it is Sig.Field && it.sig.isMeta != null })
        assertTrue(constrained.none { it is Sig.Field && it.sig.isMeta != null && it.isVariable != null })
    }

    fun testFunctionsAreNotEvents() {
        val module = model("sig N { var f: set N }\nfun later: set N { N.f' }")
        assertFalse(analysis.isEvent(module.allFunc.single { it.label == "this/later" }))
    }

    fun testTemporalOperators() {
        val module = model(
            """
            sig N { var f: set N }
            pred init { no f }
            pred guard { historically some f }
            pred trigger { some f triggered no f }
            pred step { f' = f }
            pred calls { init and guard }
            pred scenario { init ; init }
            """.trimIndent(),
        )
        assertFalse(analysis.summary(module.pred("init")).temporal)
        assertTrue(analysis.summary(module.pred("guard")).temporal)
        assertTrue(analysis.summary(module.pred("trigger")).temporal)
        assertTrue(analysis.summary(module.pred("step")).temporal)
        // Through the call to `guard`
        assertTrue(analysis.summary(module.pred("calls")).temporal)
        assertTrue(analysis.summary(module.pred("scenario")).temporal)
        // Past operators are guards, not effects
        assertFalse(analysis.isEvent(module.pred("guard")))
    }
}
