package com.serranofp.alloy.simplifications

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import edu.mit.csail.sdg.alloy4.A4Reporter
import edu.mit.csail.sdg.ast.ExprUnary
import edu.mit.csail.sdg.parser.CompUtil
import edu.mit.csail.sdg.translator.A4Options
import edu.mit.csail.sdg.translator.TranslateAlloyToKodkod
import kodkod.engine.satlab.SATFactory

/**
 * Checks with the analyzer that rewrites keep the meaning of a formula or expression. Formulas are built from
 * propositions, the `var lone sig`s [P], [Q] and [R] (`p` is `some P`), and expressions from the `var` sets
 * `A` and `B` and the `var` relations `f` and `g` over a signature `S`. Both forms must agree in every state
 * of every trace of up to 4 steps.
 */
class AlloyRewriteEquivalenceTest : BasePlatformTestCase() {
    private val header = "sig S { var f, g: set S }\nvar sig A, B in S {}\nvar lone sig P, Q, R {}\n" +
        "pred p { some P }\npred q { some Q }\npred r { some R }\n"

    /** [formula] once [rules] rewrite it, in a predicate, or in a function returning [returns] for an expression. */
    private fun rewritten(formula: String, vararg rules: AlloyRewriteRule, returns: String? = null): String {
        val prefix = if (returns == null) "pred subject { " else "fun subject: $returns { "
        myFixture.configureByText("model.als", "$header$prefix$formula }\n")
        return myFixture.rewritten(*rules).removePrefix(header).removePrefix(prefix).removeSuffix(" }\n")
    }

    /** Whether the analyzer finds a state where [before] and [after] differ: as formulas, or as [expressions]. */
    private fun counterexample(before: String, after: String, expressions: Boolean = false): Boolean {
        val same = if (expressions) "($before) = ($after)" else "($before) iff ($after)"
        val module = CompUtil.parseEverything_fromString(A4Reporter.NOP, header + "check { always ($same) } for 3 but 5 Int, 1..4 steps\n")
        val options = A4Options().apply { solver = SATFactory.get("sat4j") }
        return TranslateAlloyToKodkod.execute_command(A4Reporter.NOP, module.allReachableSigs, module.allCommands.single(), options).satisfiable()
    }

    /** Checks that [rules] rewrite [formula] into [expected], and that both mean the same. */
    private fun assertEquivalentRewrite(formula: String, expected: String, vararg rules: AlloyRewriteRule, returns: String? = null) {
        val after = rewritten(formula, *rules, returns = returns)
        assertEquals(expected, after)
        assertFalse("$formula and $after differ", counterexample(formula, after, expressions = returns != null))
    }

    /** Checks each rewrite of [cases], from a formula to the expected one, with [rules]. */
    private fun assertEquivalentRewrites(rules: List<AlloyRewriteRule>, vararg cases: Pair<String, String>, returns: String? = null) {
        for ((formula, expected) in cases) assertEquivalentRewrite(formula, expected, *rules.toTypedArray(), returns = returns)
    }

    private fun isUnary(op: ExprUnary.Op): (edu.mit.csail.sdg.ast.Expr) -> Boolean = { it is ExprUnary && it.op == op }

    /** `not not p` → `p` */
    private val doubleNegation = rule { node ->
        val inner = (node as? ExprUnary)?.takeIf(isUnary(ExprUnary.Op.NOT))?.let { strip(it.sub) } as? ExprUnary
        if (inner != null && isUnary(ExprUnary.Op.NOT)(inner)) replace(node, slice(inner.sub), node, inner) else null
    }

    /** `not before p` → `before not p`, which is wrong in the first state */
    private val naiveBeforeDual = rule { node ->
        val before = (node as? ExprUnary)?.takeIf(isUnary(ExprUnary.Op.NOT))?.let { strip(it.sub) } as? ExprUnary
        if (before == null || !isUnary(ExprUnary.Op.BEFORE)(before)) return@rule null
        replace(node, slice(before.sub)?.let { AlloyPiece.Op(AlloyOperator.BEFORE, AlloyPiece.Op(AlloyOperator.NOT, it)) }, node, before)
    }

    /** `~~r` → `r` */
    private val doubleTranspose = rule { node ->
        val inner = (node as? ExprUnary)?.takeIf(isUnary(ExprUnary.Op.TRANSPOSE))?.let { strip(it.sub) } as? ExprUnary
        if (inner != null && isUnary(ExprUnary.Op.TRANSPOSE)(inner)) replace(node, slice(inner.sub), node, inner) else null
    }

    fun testCorrectRewriteIsEquivalent() {
        assertEquivalentRewrite("not not (p and after q)", "p and after q", doubleNegation)
    }

    fun testCorrectExpressionRewriteIsEquivalent() {
        assertEquivalentRewrite("some ~~f' & g", "some f' & g", doubleTranspose)
        assertFalse(counterexample("~~f' & g", "f' & g", expressions = true))
    }

    fun testNaiveBeforeDualHasACounterexample() {
        assertEquals("before not p", rewritten("not before p", naiveBeforeDual))
        assertTrue(counterexample("not before p", "before not p"))
    }

    fun testBooleanSimplifications() {
        assertEquivalentRewrites(
            AlloyBooleanRules.ALL,
            "not not (p and q)" to "p and q",
            "not (not p or not after q)" to "p and after q",
            "not (not p and not q and not r)" to "p or q or r",
            "not (p implies after q)" to "p and not after q",
            "not (some A implies some B)" to "some A and no B",
            "not (A in B)" to "A not in B",
            "not (A = B)" to "A != B",
            "not (A != B)" to "A = B",
            "not (#A < #B)" to "#A >= #B",
            "not (#A =< #B)" to "#A > #B",
            "(p implies q) and (q implies p)" to "p iff q",
        )
    }

    fun testCardinalitySimplifications() {
        assertEquivalentRewrites(
            AlloyCardinalityRules.ALL,
            "not some A" to "no A", "not no A" to "some A",
            "#A = 0" to "no A", "0 = #A" to "no A", "#A < 1" to "no A", "#A =< 0" to "no A",
            "#A > 0" to "some A", "#A >= 1" to "some A", "#A != 0" to "some A",
            "#A = 1" to "one A", "#A =< 1" to "lone A", "#A < 2" to "lone A",
            "A = none" to "no A", "A != none" to "some A",
        )
    }

    fun testTemporalSimplifications() {
        assertEquivalentRewrites(
            AlloyTemporalRules.ALL,
            "always always p" to "always p", "eventually eventually p" to "eventually p",
            "historically historically p" to "historically p", "once once p" to "once p",
            "eventually always eventually p" to "always eventually p", "always eventually always p" to "eventually always p",
        )
    }

    fun testQuantifierSimplifications() {
        assertEquivalentRewrites(
            AlloyQuantifierRules.ALL,
            "not (some s: S | s in A)" to "no s: S | s in A",
            "not (no s: S | s in A)" to "some s: S | s in A",
            "not (all s: S | not s in A)" to "some s: S | s in A",
            "all s: S | not s in A" to "no s: S | s in A",
            "all s: A | s in B" to "A in B",
            "some s: A | s in B" to "some A & B",
            "no s: A | s in B" to "no A & B",
            "all x, y: A | x != y implies x.f != y.f" to "all disj x, y: A | x.f != y.f",
            "some x, y: A | x != y and x.f = y.f" to "some disj x, y: A | x.f = y.f",
        )
    }

    fun testRelationalSimplifications() {
        assertEquivalentRewrites(AlloyRelationalRules.ALL, "~~f" to "f", "f + f" to "f", "^f + iden" to "*f", "iden + ^f" to "*f", returns = "univ -> univ")
        assertEquivalentRewrites(AlloyRelationalRules.ALL, "A & A" to "A", returns = "set univ")
        assertEquivalentRewrites(AlloyRelationalRules.ALL, "A in B and B in A" to "A = B")
    }

    fun testLogicalEquivalences() {
        assertEquivalentRewrites(
            AlloyEquivalenceRules.DE_MORGAN_RULES,
            "not (p or after q)" to "not p and not after q",
            "not (A = B and some A)" to "A != B or no A",
            "not p and not q" to "not (p or q)",
            "no A or A != B" to "not (some A and A = B)",
        )
        assertEquivalentRewrites(AlloyEquivalenceRules.OR_TO_IMPLIES, "not p or q" to "p implies q", "no A or some B or r" to "some A implies (some B or r)")
        assertEquivalentRewrites(AlloyEquivalenceRules.IMPLIES_TO_OR, "p implies q" to "not p or q", "some A implies no B" to "no A or no B")
    }

    fun testQuantifierNegations() {
        assertEquivalentRewrites(
            AlloyEquivalenceRules.QUANTIFIER_NEGATION,
            "not (all s: S | s in A)" to "some s: S | s not in A",
            "not (some s: S | some s.f)" to "all s: S | no s.f",
            "all s: S | s not in A" to "not (some s: S | s in A)",
            "some s: S | no s.f" to "not (all s: S | some s.f)",
        )
    }

    fun testTemporalNegations() {
        assertEquivalentRewrites(
            AlloyEquivalenceRules.TEMPORAL_NEGATION,
            "not always p" to "eventually not p", "not eventually p" to "always not p", "not after p" to "after not p",
            "not historically p" to "once not p", "not once p" to "historically not p",
            "not (p until q)" to "not p releases not q", "not (p releases q)" to "not p until not q",
            "not (p since q)" to "not p triggered not q", "not (p triggered q)" to "not p since not q",
            "eventually not p" to "not always p", "historically no A" to "not once some A", "not p since not q" to "not (p triggered q)",
        )
        // `before` is left out on purpose: its naive dual differs in the first state
        assertTrue(counterexample("not before p", "before not p"))
    }

    fun testTemporalDistributions() {
        assertEquivalentRewrites(
            AlloyEquivalenceRules.TEMPORAL_DISTRIBUTION,
            "always (p and q)" to "always p and always q", "eventually (p or q)" to "eventually p or eventually q",
            "historically (p and q and r)" to "historically p and historically q and historically r", "once (p or q)" to "once p or once q",
            "always p and always q" to "always (p and q)", "once p or once q" to "once (p or q)",
        )
        // The ones not offered do differ
        assertTrue(counterexample("always (p or q)", "always p or always q"))
        assertTrue(counterexample("eventually (p and q)", "eventually p and eventually q"))
    }

    fun testTransposeJoin() {
        assertEquivalentRewrites(AlloyEquivalenceRules.TRANSPOSE_JOIN, "A.~f" to "f.A", "f.A" to "A.~f", returns = "set univ")
    }
}
