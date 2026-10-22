package com.serranofp.alloy.simplifications

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.serranofp.alloy.inspections.assertTypeChecks
import edu.mit.csail.sdg.alloy4.A4Reporter
import edu.mit.csail.sdg.alloy4.Err
import edu.mit.csail.sdg.ast.Expr
import edu.mit.csail.sdg.ast.ExprUnary
import edu.mit.csail.sdg.parser.CompUtil
import edu.mit.csail.sdg.translator.A4Options
import edu.mit.csail.sdg.translator.TranslateAlloyToKodkod
import kodkod.engine.satlab.SATFactory

/**
 * Checks with the analyzer that rewrites keep the meaning of a formula or expression. Formulas are built from
 * propositions, the `var lone sig`s [P], [Q] and [R] (`p` is `some P`), and expressions from the `var` sets
 * `A` and `B` and the `var` relations `f` and `g` over a signature `S`, and the function `h`, which is called
 * as `e.h`. Both forms must agree in every state of every trace of up to 4 steps.
 *
 * Each rule list is checked twice: on the formula alone, for the exact text of its rewrite, and in contexts
 * (`X ; p`, `(X).h`, `q and X`, `let y = A | X`, …), where every node that one of its rules rewrites is
 * rewritten in turn, and the whole predicate, not only the formula, must keep its meaning.
 */
class AlloyRewriteEquivalenceTest : BasePlatformTestCase() {
    private val header = "sig S { var f, g: set S }\nvar sig A, B in S {}\nvar lone sig P, Q, R {}\n" +
        "pred p { some P }\npred q { some Q }\npred r { some R }\nfun h[x: S -> S]: set S { S.x }\n"

    /** [formula] once [rules] rewrite it, in a predicate, or in a function returning [returns] for an expression. */
    private fun rewritten(formula: String, vararg rules: AlloyRewriteRule, returns: String? = null): String {
        val prefix = if (returns == null) "pred subject { " else "fun subject: $returns { "
        myFixture.configureByText("model.als", "$header$prefix$formula }\n")
        return myFixture.rewritten(*rules).removePrefix(header).removePrefix(prefix).removeSuffix(" }\n")
    }

    /** Whether the analyzer finds a state where [before] and [after] differ: as formulas, or as [expressions]. */
    private fun counterexample(before: String, after: String, expressions: Boolean = false): Boolean {
        val same = if (expressions) "($before) = ($after)" else "($before) iff ($after)"
        return satisfiable(header + "check { always ($same) } for 3 but 5 Int, 1..4 steps\n")
    }

    /** Whether the single command of [model] has an instance, a counterexample for a `check`. */
    private fun satisfiable(model: String): Boolean {
        val module = CompUtil.parseEverything_fromString(A4Reporter.NOP, model)
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

    /** The body of a predicate, before and after one rewrite of a node in it, which [fixName] names. */
    private class Rewritten(val before: String, val after: String, val fixName: String) {
        override fun toString(): String = "'$fixName': { $before } became { $after }"
    }

    /**
     * Every rewrite that [rules] make of any written node of each of [bodies], each in a predicate of its own,
     * one rewrite at a time, as an intention can be invoked on any node. Also the bodies that have none.
     */
    private fun rewritesOfEveryNode(rules: List<AlloyRewriteRule>, bodies: List<String>): Pair<List<Rewritten>, Set<Int>> {
        myFixture.configureByText("model.als", header + bodies.withIndex().joinToString("") { (i, body) -> "pred subject$i { $body }\n" })
        myFixture.assertTypeChecks()
        val found = myFixture.onModel { source, model ->
            val found = LinkedHashMap<Pair<Int, String>, Rewritten>()
            val record = rule { node ->
                if (!written(node)) return@rule null
                for (rule in rules) {
                    val result = rewrite(rule, node) ?: continue
                    val decl = paragraph.text
                    val i = decl.removePrefix("pred subject").substringBefore(' ').toInt()
                    val start = paragraph.textRange.startOffset
                    val after = decl.replaceRange(result.range.startOffset - start, result.range.endOffset - start, result.text)
                        .substringAfter("{ ").removeSuffix(" }")
                    found.getOrPut(i to after) { Rewritten(bodies[i], after, result.fixName) }
                }
                null
            }
            AlloyRewriteWalker(source, listOf(record)).walk(model.module)
            found
        }
        return found.values.toList() to (bodies.indices.toSet() - found.keys.map { it.first }.toSet())
    }

    /**
     * The rewrites of [rewrites] that change the meaning of their predicate, or break the model ("type error",
     * with the analyzer's message), with why.
     */
    private fun broken(rewrites: List<Rewritten>): List<Pair<Rewritten, String>> {
        fun failure(some: List<Rewritten>): String? {
            val preds = some.withIndex().joinToString("") { (j, it) -> "pred old$j { ${it.before} }\npred new$j { ${it.after} }\n" }
            val same = some.indices.joinToString(" and ") { "(old$it iff new$it)" }
            return try {
                if (satisfiable("$header${preds}check { always ($same) } for 3 but 5 Int, 1..4 steps\n")) "meaning changed" else null
            } catch (e: Err) {
                "type error: ${e.msg}"
            }
        }
        // One check for all of them, and one for each if that fails
        if (rewrites.isEmpty() || failure(rewrites) == null) return emptyList()
        return rewrites.mapNotNull { rewrite -> failure(listOf(rewrite))?.let { rewrite to it } }
    }

    /**
     * Checks that every rewrite that [rules] make in each of [subjects], put in each of [contexts] (where `%s`
     * stands for it), keeps the model type-checking and its predicate meaning the same. In the contexts that put
     * a subject in parentheses, so that it is one node there, some rule must rewrite something: the rules are
     * not merely kept out of the way by the context, except in the bodies of [missed].
     */
    private fun assertEquivalentInContexts(
        rules: List<AlloyRewriteRule>,
        subjects: List<String>,
        contexts: List<String>,
        missed: Set<String> = emptySet(),
    ) {
        val bodies = contexts.flatMap { context -> subjects.map { context.replace("%s", it) } }
        val (rewrites, unchanged) = rewritesOfEveryNode(rules, bodies)
        val oneNode = contexts.filter { it == "%s" || "(%s)" in it }.flatMap { context -> subjects.map { context.replace("%s", it) } }.toSet()
        assertEquals("Nothing rewritten", missed, unchanged.map { bodies[it] }.filter { it in oneNode }.toSet())
        val broken = broken(rewrites)
        assertEmpty(broken.joinToString("\n") { (rewrite, why) -> "$rewrite: $why" }, broken)
    }

    private fun assertFormulasEquivalentInContexts(rules: List<AlloyRewriteRule>, cases: List<Pair<String, String>>, missed: Set<String> = emptySet()) =
        assertEquivalentInContexts(rules, cases.map { it.first }, FORMULA_CONTEXTS, missed)

    private fun isUnary(op: ExprUnary.Op): (Expr) -> Boolean = { it is ExprUnary && it.op == op }

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

    /** The positive controls of the contextual check: a wrong rule is caught, in every context, and a right one passes. */
    fun testContextualCheckFindsAWrongRule() {
        val bodies = FORMULA_CONTEXTS.map { it.replace("%s", "not before p") }
        val (rewrites, unchanged) = rewritesOfEveryNode(listOf(naiveBeforeDual), bodies)
        assertEmpty(unchanged)
        // After `p`, in the second state, both forms are false
        assertEquals(bodies - "p ; (not before p)", broken(rewrites).map { it.first.before })
        // Rewrites right for the formula alone, but not where it is written, without the parentheses they need
        val operand = Rewritten("r and (p or q)", "r and q or p", "Operand")
        val receiver = Rewritten("some (S->S + S->S).h", "some S->S.h", "Receiver")
        assertEquals(
            listOf(operand to "meaning changed", receiver to "type error"),
            broken(listOf(operand, receiver)).map { (rewrite, why) -> rewrite to why.substringBefore(':') },
        )
        assertEmpty(broken(listOf(Rewritten("p or q", "q or p", "Alone"), Rewritten("some (S->S + S->S).h", "some (S->S).h", "Alone"))))
        assertEquivalentInContexts(listOf(doubleNegation), listOf("not not (p and after q)", "not not some A"), FORMULA_CONTEXTS)
    }

    fun testBooleanSimplifications() {
        assertEquivalentRewrites(AlloyBooleanRules.ALL, *BOOLEAN.toTypedArray())
    }

    /** The analyzer reads `X ; p` as `X and after p`, which puts `after p` between the converse implications and the end. */
    fun testBooleanSimplificationsInContexts() =
        assertFormulasEquivalentInContexts(AlloyBooleanRules.ALL, BOOLEAN, missed = setOf("((p implies q) and (q implies p)) ; p"))

    fun testCardinalitySimplifications() {
        assertEquivalentRewrites(AlloyCardinalityRules.ALL, *CARDINALITY.toTypedArray())
    }

    fun testCardinalitySimplificationsInContexts() = assertFormulasEquivalentInContexts(AlloyCardinalityRules.ALL, CARDINALITY)

    fun testTemporalSimplifications() {
        assertEquivalentRewrites(AlloyTemporalRules.ALL, *TEMPORAL.toTypedArray())
    }

    fun testTemporalSimplificationsInContexts() = assertFormulasEquivalentInContexts(AlloyTemporalRules.ALL, TEMPORAL)

    fun testQuantifierSimplifications() {
        assertEquivalentRewrites(AlloyQuantifierRules.ALL, *QUANTIFIER.toTypedArray())
    }

    fun testQuantifierSimplificationsInContexts() = assertFormulasEquivalentInContexts(AlloyQuantifierRules.ALL, QUANTIFIER)

    fun testRelationalSimplifications() {
        assertEquivalentRewrites(AlloyRelationalRules.ALL, *RELATIONS.toTypedArray(), returns = "univ -> univ")
        assertEquivalentRewrites(AlloyRelationalRules.ALL, *SETS.toTypedArray(), returns = "set univ")
        assertEquivalentRewrites(AlloyRelationalRules.ALL, "A in B and B in A" to "A = B")
    }

    fun testRelationalSimplificationsInContexts() {
        assertEquivalentInContexts(AlloyRelationalRules.ALL, RELATIONS.map { it.first }, RELATION_CONTEXTS)
        assertEquivalentInContexts(AlloyRelationalRules.ALL, SETS.map { it.first }, SET_CONTEXTS)
        assertEquivalentInContexts(AlloyRelationalRules.ALL, listOf("A in B and B in A"), FORMULA_CONTEXTS, missed = setOf("(A in B and B in A) ; p"))
    }

    fun testLogicalEquivalences() {
        assertEquivalentRewrites(AlloyEquivalenceRules.DE_MORGAN_RULES, *DE_MORGAN.toTypedArray())
        assertEquivalentRewrites(AlloyEquivalenceRules.OR_TO_IMPLIES, *OR_TO_IMPLIES.toTypedArray())
        assertEquivalentRewrites(AlloyEquivalenceRules.IMPLIES_TO_OR, *IMPLIES_TO_OR.toTypedArray())
    }

    fun testLogicalEquivalencesInContexts() {
        assertFormulasEquivalentInContexts(AlloyEquivalenceRules.DE_MORGAN_RULES, DE_MORGAN, missed = flattened("not p and not q") + flattened("no A or A != B", and = false))
        assertFormulasEquivalentInContexts(AlloyEquivalenceRules.OR_TO_IMPLIES, OR_TO_IMPLIES)
        assertFormulasEquivalentInContexts(AlloyEquivalenceRules.IMPLIES_TO_OR, IMPLIES_TO_OR)
    }

    fun testQuantifierNegations() {
        assertEquivalentRewrites(AlloyEquivalenceRules.QUANTIFIER_NEGATION, *QUANTIFIER_NEGATION.toTypedArray())
    }

    fun testQuantifierNegationsInContexts() = assertFormulasEquivalentInContexts(AlloyEquivalenceRules.QUANTIFIER_NEGATION, QUANTIFIER_NEGATION)

    fun testTemporalNegations() {
        assertEquivalentRewrites(AlloyEquivalenceRules.TEMPORAL_NEGATION, *TEMPORAL_NEGATION.toTypedArray())
        // `before` is left out on purpose: its naive dual differs in the first state
        assertTrue(counterexample("not before p", "before not p"))
    }

    fun testTemporalNegationsInContexts() = assertFormulasEquivalentInContexts(AlloyEquivalenceRules.TEMPORAL_NEGATION, TEMPORAL_NEGATION)

    fun testTemporalDistributions() {
        assertEquivalentRewrites(AlloyEquivalenceRules.TEMPORAL_DISTRIBUTION, *TEMPORAL_DISTRIBUTION.toTypedArray())
        // The ones not offered do differ
        assertTrue(counterexample("always (p or q)", "always p or always q"))
        assertTrue(counterexample("eventually (p and q)", "eventually p and eventually q"))
    }

    fun testTemporalDistributionsInContexts() =
        assertFormulasEquivalentInContexts(AlloyEquivalenceRules.TEMPORAL_DISTRIBUTION, TEMPORAL_DISTRIBUTION, missed = flattened("always p and always q") + flattened("once p or once q", and = false))

    fun testTransposeJoin() {
        assertEquivalentRewrites(AlloyEquivalenceRules.TRANSPOSE_JOIN, *TRANSPOSE_JOIN.toTypedArray(), returns = "set univ")
    }

    fun testTransposeJoinInContexts() = assertEquivalentInContexts(AlloyEquivalenceRules.TRANSPOSE_JOIN, TRANSPOSE_JOIN.map { it.first }, SET_CONTEXTS)

    private companion object {
        /**
         * The bodies where the analyzer flattens [subject], an `and` (or an `or`), into the list around it, so
         * that its parentheses leave no node for the rules to match: `;` is an `and` of an `after`, and block
         * lines are an `and` too.
         */
        fun flattened(subject: String, and: Boolean = true): Set<String> =
            if (and) setOf("($subject) ; p", "q and ($subject)", "($subject) and q", "($subject)\n  q") else setOf("($subject) or q")

        /**
         * Where a formula is put, `%s`: in parentheses, so that it stays one node whatever its operators, then
         * as written, where it may also group with what is around it.
         */
        val FORMULA_CONTEXTS = listOf(
            "%s", "(%s) ; p", "p ; (%s)", "q and (%s)", "(%s) and q", "(%s) or q", "(%s) implies r", "q implies (%s)",
            "q implies (%s) else r", "q implies r else (%s)", "let y = A | (%s)", "let y = A | (%s) ; p", "not (%s)",
            "always (%s)", "some z: S | (%s)", "(%s)\n  q",
            "%s ; p", "q and %s", "%s implies r", "let y = A | %s", "%s\n  q",
        )

        /** Where an expression that is a binary relation is put. */
        val RELATION_CONTEXTS = listOf(
            "some %s", "some (%s).h", "some S.(%s)", "some (%s)[S]", "some (%s) & g", "some g - (%s)", "some ~(%s)",
            "some (%s).h ; p", "some g + %s",
        )

        /** Where an expression that is a set is put. */
        val SET_CONTEXTS = listOf(
            "some %s", "some (%s).f", "some f.(%s)", "some (%s) - B", "some B - (%s)", "some (%s) & B", "#(%s) > 1",
            "some %s.f", "some B - %s",
        )

        val BOOLEAN = listOf(
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

        val CARDINALITY = listOf(
            "not some A" to "no A", "not no A" to "some A",
            "#A = 0" to "no A", "0 = #A" to "no A", "#A < 1" to "no A", "#A =< 0" to "no A",
            "#A > 0" to "some A", "#A >= 1" to "some A", "#A != 0" to "some A",
            "#A = 1" to "one A", "#A =< 1" to "lone A", "#A < 2" to "lone A",
            "A = none" to "no A", "A != none" to "some A",
        )

        val TEMPORAL = listOf(
            "always always p" to "always p", "eventually eventually p" to "eventually p",
            "historically historically p" to "historically p", "once once p" to "once p",
            "eventually always eventually p" to "always eventually p", "always eventually always p" to "eventually always p",
        )

        val QUANTIFIER = listOf(
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

        val RELATIONS = listOf("~~f" to "f", "f + f" to "f", "^f + iden" to "*f", "iden + ^f" to "*f")

        val SETS = listOf("A & A" to "A")

        val DE_MORGAN = listOf(
            "not (p or after q)" to "not p and not after q",
            "not (A = B and some A)" to "A != B or no A",
            "not p and not q" to "not (p or q)",
            "no A or A != B" to "not (some A and A = B)",
        )

        val OR_TO_IMPLIES = listOf("not p or q" to "p implies q", "no A or some B or r" to "some A implies (some B or r)")

        val IMPLIES_TO_OR = listOf("p implies q" to "not p or q", "some A implies no B" to "no A or no B")

        val QUANTIFIER_NEGATION = listOf(
            "not (all s: S | s in A)" to "some s: S | s not in A",
            "not (some s: S | some s.f)" to "all s: S | no s.f",
            "all s: S | s not in A" to "not (some s: S | s in A)",
            "some s: S | no s.f" to "not (all s: S | some s.f)",
        )

        val TEMPORAL_NEGATION = listOf(
            "not always p" to "eventually not p", "not eventually p" to "always not p", "not after p" to "after not p",
            "not historically p" to "once not p", "not once p" to "historically not p",
            "not (p until q)" to "not p releases not q", "not (p releases q)" to "not p until not q",
            "not (p since q)" to "not p triggered not q", "not (p triggered q)" to "not p since not q",
            "eventually not p" to "not always p", "historically no A" to "not once some A", "not p since not q" to "not (p triggered q)",
        )

        val TEMPORAL_DISTRIBUTION = listOf(
            "always (p and q)" to "always p and always q", "eventually (p or q)" to "eventually p or eventually q",
            "historically (p and q and r)" to "historically p and historically q and historically r", "once (p or q)" to "once p or once q",
            "always p and always q" to "always (p and q)", "once p or once q" to "once (p or q)",
        )

        val TRANSPOSE_JOIN = listOf("A.~f" to "f.A", "f.A" to "A.~f")
    }
}
