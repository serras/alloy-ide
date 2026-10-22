package com.serranofp.alloy.simplifications

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import edu.mit.csail.sdg.alloy4.A4Reporter
import edu.mit.csail.sdg.ast.Expr
import edu.mit.csail.sdg.ast.ExprBinary
import edu.mit.csail.sdg.ast.ExprCall
import edu.mit.csail.sdg.ast.ExprLet
import edu.mit.csail.sdg.ast.ExprList
import edu.mit.csail.sdg.ast.ExprQt
import edu.mit.csail.sdg.ast.ExprUnary
import edu.mit.csail.sdg.ast.ExprVar
import edu.mit.csail.sdg.parser.CompUtil

class AlloyRewriteRenderTest : BasePlatformTestCase() {
    private fun isNot(node: Expr) = node is ExprUnary && node.op == ExprUnary.Op.NOT

    /** `not not p` → `p` */
    private val doubleNegation = rule { node ->
        val inner = (node as? ExprUnary)?.takeIf { isNot(it) }?.let { strip(it.sub) }?.takeIf { isNot(it) } as? ExprUnary
        if (inner != null && written(node)) replace(node, slice(inner.sub), node, inner) else null
    }

    /** `not (not a or not b)` → `a and b`, and the dual */
    private val deMorgan = rule { node ->
        val list = (node as? ExprUnary)?.takeIf { isNot(it) && written(it) }?.let { strip(it.sub) } as? ExprList
        if (list == null || list.op !in setOf(ExprList.Op.AND, ExprList.Op.OR) || !written(list) || !list.args.all(::isNegation)) return@rule null
        val dual = if (list.op == ExprList.Op.OR) AlloyOperator.AND else AlloyOperator.OR
        val operands = list.args.map { unnegate(it) ?: return@rule null }
        replace(node, AlloyPiece.Op(dual, operands), node, list)
    }

    /** `a implies b` → `not a or b` */
    private val impliesToOr = rule { node ->
        val implies = (node as? ExprBinary)?.takeIf { it.op == ExprBinary.Op.IMPLIES && written(it) } ?: return@rule null
        val left = negate(implies.left) ?: return@rule null
        replace(node, AlloyPiece.Op(AlloyOperator.OR, left, slice(implies.right) ?: return@rule null), node)
    }

    /** `a or b` → `b or a` */
    private val swapOr = rule { node ->
        val or = (node as? ExprList)?.takeIf { it.op == ExprList.Op.OR && it.args.size == 2 && written(it) } ?: return@rule null
        replace(node, AlloyPiece.Op(AlloyOperator.OR, slice(or.args[1]) ?: return@rule null, slice(or.args[0]) ?: return@rule null), node)
    }

    /** `not (some x: A | p)` → `no x: A | p` */
    private val negatedSome = rule { node ->
        val quantifier = (node as? ExprUnary)?.takeIf { isNot(it) }?.let { strip(it.sub) } as? ExprQt
        if (quantifier == null || quantifier.op != ExprQt.Op.SOME) return@rule null
        val decls = decls(quantifier) ?: return@rule null
        val body = slice(quantifier.sub) ?: return@rule null
        replace(node, AlloyPiece.Bind("no", source.document.getText(decls), decls, body), node, quantifier)
    }

    private val header = "sig A { f: set A, r: set A }\nsig B in A {}\none sig C { g: set A }\none sig x, y extends A {}\n" +
        "pred a { some A }\npred b { no B }\npred c { lone A }\npred p { some C }\npred q { no C.g }\n"

    /**
     * Rewrites [formula] with [rules] and checks that it reads [expected]. Then it checks the round trip: the
     * rewritten model type-checks, and its formula has the same AST as [parenthesized], which spells out the
     * expected grouping.
     */
    private fun check(formula: String, expected: String, parenthesized: String = expected, vararg rules: AlloyRewriteRule) {
        myFixture.configureByText("model.als", header + "pred subject { $formula }\n")
        val text = myFixture.rewritten(*rules)
        assertEquals(header + "pred subject { $expected }\n", text)
        val module = CompUtil.parseEverything_fromString(A4Reporter.NOP, "$text\npred expected { $parenthesized }\n")
        val subject = module.allFunc.single { it.label == "this/subject" }.body
        val grouping = module.allFunc.single { it.label == "this/expected" }.body
        assertTrue("$expected does not mean $parenthesized", sameAst(subject, grouping))
    }

    /**
     * [Expr.isSame], which compares lists and quantifiers by identity, extended to compare them by structure,
     * with the bound variables of [a] standing for those of [b] in [bound].
     */
    private fun sameAst(a: Expr, b: Expr, bound: MutableMap<Expr, Expr> = HashMap()): Boolean {
        val x = a.deNOP()
        val y = b.deNOP()
        return when {
            x is ExprList && y is ExprList -> x.op == y.op && x.args.size == y.args.size && x.args.indices.all { sameAst(x.args[it], y.args[it], bound) }
            x is ExprQt && y is ExprQt -> x.op == y.op && x.decls.size == y.decls.size && x.decls.indices.all { i ->
                val (d, e) = x.decls[i] to y.decls[i]
                (d.disjoint != null) == (e.disjoint != null) && d.names.size == e.names.size && sameAst(d.expr, e.expr, bound) &&
                    d.names.indices.all { bound[d.names[it]] = e.names[it]; true }
            } && sameAst(x.sub, y.sub, bound)
            x is ExprLet && y is ExprLet -> sameAst(x.expr, y.expr, bound) && bound.put(x.`var`, y.`var`).let { true } && sameAst(x.sub, y.sub, bound)
            x is ExprUnary && y is ExprUnary -> x.op == y.op && sameAst(x.sub, y.sub, bound)
            x is ExprBinary && y is ExprBinary -> x.op == y.op && sameAst(x.left, y.left, bound) && sameAst(x.right, y.right, bound)
            x is ExprCall && y is ExprCall -> x.`fun` == y.`fun` && x.args.size == y.args.size && x.args.indices.all { sameAst(x.args[it], y.args[it], bound) }
            x is ExprVar && y is ExprVar -> bound[x] == y
            else -> x.isSame(y)
        }
    }

    fun testOperandsKeptAsWritten() {
        check("not ((not this/A.f in B) or (not some g))", "this/A.f in B and some g", "(this/A.f in B) and (some g)", rules = arrayOf(deMorgan))
    }

    fun testParenthesesKept() {
        check("x.r = y implies not not (a or b)", "x.r = y implies (a or b)", "(x.r = y) implies (a or b)", rules = arrayOf(doubleNegation))
    }

    fun testParenthesesAddedWhereNeeded() {
        check("c and not (not a and not b)", "c and (a or b)", "c and ((a) or (b))", rules = arrayOf(deMorgan))
    }

    fun testResultInsideAnOperator() {
        check("(p implies q) and a", "(not p or q) and a", "((not p) or q) and a", rules = arrayOf(impliesToOr))
    }

    fun testSymbolicOperators() {
        check("!(!a || !b)", "a && b", "(a) and (b)", rules = arrayOf(deMorgan))
    }

    fun testKeywordOperators() {
        check("not (not a or not b)", "a and b", "(a) and (b)", rules = arrayOf(deMorgan))
    }

    fun testNegatedOperandsInSymbolicStyle() {
        check("a => b", "!a || b", "(not a) or b", rules = arrayOf(impliesToOr))
        check("some A => no B", "no A || no B", "(no A) or (no B)", rules = arrayOf(impliesToOr))
    }

    fun testNegatedComparison() {
        check("x in y implies a", "x not in y or a", "(x not in y) or a", rules = arrayOf(impliesToOr))
    }

    fun testQuantifierMovedFromTheRightmostOperand() {
        check("some A or all z: A | some z.r", "(all z: A | some z.r) or some A", rules = arrayOf(swapOr))
    }

    fun testQuantifierMovedToTheRightmostOperand() {
        check("(all z: A | some z.r) or some A", "some A or (all z: A | some z.r)", "some A or all z: A | some z.r", rules = arrayOf(swapOr))
    }

    fun testQuantifierAsTheRightmostOperand() {
        check("a and not (some z: A | some z.r)", "a and no z: A | some z.r", "a and (no z: A | some z.r)", rules = arrayOf(negatedSome))
    }

    fun testQuantifierBeforeAnOperator() {
        check("not (some z: A | some z.r) and a", "(no z: A | some z.r) and a", rules = arrayOf(negatedSome))
        check("not (some z: A | some z.r) implies a", "(no z: A | some z.r) implies a", rules = arrayOf(negatedSome))
    }

    fun testQuantifierBeforeASequence() {
        // Parentheses are kept before a `;`, although they are not needed there (see testSequenceEndsAQuantifierBody)
        check("not (some z: A | some z.r) ; a", "(no z: A | some z.r) ; a", rules = arrayOf(negatedSome))
        check("b and not (some z: A | some z.r) ; a", "b and (no z: A | some z.r) ; a", rules = arrayOf(negatedSome))
        check("b ; not (some z: A | some z.r)", "b ; no z: A | some z.r", "b ; (no z: A | some z.r)", rules = arrayOf(negatedSome))
    }

    fun testLetBody() {
        check("let z = A | not not (a or b)", "let z = A | a or b", "let z = A | (a or b)", rules = arrayOf(doubleNegation))
        check("(let z = A | not (some w: z | some w.r)) and a", "(let z = A | no w: z | some w.r) and a", rules = arrayOf(negatedSome))
        check("let z = A | not (some w: z | some w.r) ; a", "let z = A | (no w: z | some w.r) ; a", "(let z = A | (no w: z | some w.r)) ; a", rules = arrayOf(negatedSome))
    }

    /** Pins the analyzer's grammar: `;` binds more loosely than a quantifier's or a `let`'s body. */
    fun testSequenceEndsAQuantifierBody() {
        val module = CompUtil.parseEverything_fromString(
            A4Reporter.NOP,
            header + "pred s { no z: A | some z.r ; a }\npred t { (no z: A | some z.r) ; a }\npred u { no z: A | (some z.r ; a) }\n" +
                "pred v { let z = A | some z ; a }\npred w { (let z = A | some z) ; a }\n",
        )
        val (s, t, u, v, w) = listOf("s", "t", "u", "v", "w").map { name -> module.allFunc.single { it.label == "this/$name" }.body }
        assertTrue(sameAst(s, t))
        assertFalse(sameAst(s, u))
        assertTrue(sameAst(v, w))
    }

    fun testLetGroupingIsCompared() {
        // A positive control for the `let` case of [sameAst]: a different grouping is told apart
        val module = CompUtil.parseEverything_fromString(A4Reporter.NOP, header + "pred s { let z = A | some z and a }\npred t { (let z = A | some z) and a }\npred u { let w = A | some w and a }\n")
        val (s, t, u) = listOf("s", "t", "u").map { name -> module.allFunc.single { it.label == "this/$name" }.body }
        assertFalse(sameAst(s, t))
        assertTrue(sameAst(s, u))
    }

    fun testQuantifierOnABlockLine() {
        check("not (some z: A | some z.r)\n  a", "no z: A | some z.r\n  a", "(no z: A | some z.r) and a", rules = arrayOf(negatedSome))
    }

    fun testBlockLineNeedsNoParentheses() {
        check("a\n  p implies q\n  b", "a\n  not p or q\n  b", "a and ((not p) or q) and b", rules = arrayOf(impliesToOr))
    }

    fun testOperandOfAnAndNeedsParentheses() {
        check("a and (p implies q) and b", "a and (not p or q) and b", "a and ((not p) or q) and b", rules = arrayOf(impliesToOr))
    }

    fun testCommentsInsideOperandsAreKept() {
        check("not not (\n  -- why\n  a)", "(\n  -- why\n  a)", "a", rules = arrayOf(doubleNegation))
    }

    fun testDroppedCommentIsNotRewritten() {
        myFixture.configureByText("model.als", header + "pred subject { not /* keep */ not a }\n")
        assertEmpty(myFixture.rewrites(doubleNegation))
    }

    fun testDroppedOperandIsNotRewritten() {
        // `A <: f` is `f` to the analyzer, which leaves no node for `A <:`
        myFixture.configureByText("model.als", header + "pred subject { not (A <: f = r) }\n")
        val negatedComparison = rule { node -> if (isNot(node)) replace(node, negate(strip((node as ExprUnary).sub)), node) else null }
        assertEmpty(myFixture.rewrites(negatedComparison))
        myFixture.configureByText("model.als", header + "pred subject { not (f = r) }\n")
        assertEquals("pred subject { f != r }\n", myFixture.rewritten(negatedComparison).removePrefix(header))
    }
}
