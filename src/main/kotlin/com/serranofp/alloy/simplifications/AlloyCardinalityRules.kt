package com.serranofp.alloy.simplifications

import edu.mit.csail.sdg.ast.Expr
import edu.mit.csail.sdg.ast.ExprBinary
import edu.mit.csail.sdg.ast.ExprConstant
import edu.mit.csail.sdg.ast.ExprUnary
import edu.mit.csail.sdg.ast.Sig

/** The simplifications of "Emptiness or cardinality test can be simplified". */
object AlloyCardinalityRules {
    private val family = AlloyRewriteFamily.CARDINALITY

    /** `not some e` → `no e`, and `not no e` → `some e` */
    val negatedMultiplicity: AlloyRewriteRule = AlloySimplification(family) { node ->
        val not = unary(node, ExprUnary.Op.NOT) ?: return@AlloySimplification null
        val (test, operator) = unary(not.sub, ExprUnary.Op.SOME)?.let { it to AlloyOperator.NO }
            ?: unary(not.sub, ExprUnary.Op.NO)?.let { it to AlloyOperator.SOME }
            ?: return@AlloySimplification null
        val operand = slice(test.sub) ?: return@AlloySimplification null
        val keyword = operator.keyword
        rewrite(node, AlloyPiece.Op(operator, operand), "Negated '${if (test.op == ExprUnary.Op.SOME) "some" else "no"}'", { "Replace with '$keyword'" }, uses = listOf(not, test))
    }

    /**
     * The multiplicity that `#e op n` tests, with `#e` on the left: `#e = 0` is `no e`, `#e > 0` is `some e`,
     * `#e = 1` is `one e` and `#e =< 1` is `lone e`.
     */
    private fun multiplicity(op: ExprBinary.Op, n: Int): AlloyOperator? = when {
        op == ExprBinary.Op.EQUALS && n == 0 || op == ExprBinary.Op.LT && n == 1 || op == ExprBinary.Op.LTE && n == 0 -> AlloyOperator.NO
        op == ExprBinary.Op.GT && n == 0 || op == ExprBinary.Op.GTE && n == 1 || op == ExprBinary.Op.NOT_EQUALS && n == 0 -> AlloyOperator.SOME
        op == ExprBinary.Op.EQUALS && n == 1 -> AlloyOperator.ONE
        op == ExprBinary.Op.LTE && n == 1 || op == ExprBinary.Op.LT && n == 2 -> AlloyOperator.LONE
        else -> null
    }

    /** `n op #e` as `#e op' n`. */
    private fun mirrored(op: ExprBinary.Op): ExprBinary.Op = when (op) {
        ExprBinary.Op.LT -> ExprBinary.Op.GT
        ExprBinary.Op.LTE -> ExprBinary.Op.GTE
        ExprBinary.Op.GT -> ExprBinary.Op.LT
        ExprBinary.Op.GTE -> ExprBinary.Op.LTE
        else -> op
    }

    private val CARDINALITY_COMPARISONS = arrayOf(
        ExprBinary.Op.EQUALS, ExprBinary.Op.NOT_EQUALS, ExprBinary.Op.LT, ExprBinary.Op.LTE, ExprBinary.Op.GT, ExprBinary.Op.GTE,
    )

    /** `#e = 0` → `no e`, `#e > 0` → `some e`, `#e = 1` → `one e`, `#e =< 1` → `lone e`, and their equivalent and mirrored forms */
    val cardinality: AlloyRewriteRule = AlloySimplification(family) { node ->
        val comparison = binary(node, *CARDINALITY_COMPARISONS) ?: return@AlloySimplification null
        val (card, number, op) = unary(comparison.left, ExprUnary.Op.CARDINALITY)?.let { Triple(it, number(comparison.right), comparison.op) }
            ?: unary(comparison.right, ExprUnary.Op.CARDINALITY)?.let { Triple(it, number(comparison.left), mirrored(comparison.op)) }
            ?: return@AlloySimplification null
        if (number == null) return@AlloySimplification null
        val operator = multiplicity(op, number.num) ?: return@AlloySimplification null
        val operand = slice(card.sub) ?: return@AlloySimplification null
        val keyword = operator.keyword
        rewrite(node, AlloyPiece.Op(operator, operand), "Cardinality test", { "Replace with '$keyword'" }, uses = listOf(comparison, card, number))
    }

    /** `e = none` → `no e`, and `e != none` → `some e`, for a set `e` */
    val none: AlloyRewriteRule = AlloySimplification(family) { node ->
        val comparison = binary(node, ExprBinary.Op.EQUALS, ExprBinary.Op.NOT_EQUALS) ?: return@AlloySimplification null
        val (set, none) = when {
            isNone(comparison.right) -> comparison.left to strip(comparison.right)
            isNone(comparison.left) -> comparison.right to strip(comparison.left)
            else -> return@AlloySimplification null
        }
        if (set.type().arity() != 1 || set.type().is_int || set.type().is_small_int) return@AlloySimplification null
        val operator = if (comparison.op == ExprBinary.Op.EQUALS) AlloyOperator.NO else AlloyOperator.SOME
        val operand = slice(set) ?: return@AlloySimplification null
        val keyword = operator.keyword
        rewrite(node, AlloyPiece.Op(operator, operand), "Comparison with 'none'", { "Replace with '$keyword'" }, uses = listOf(comparison, none))
    }

    private fun AlloyRewriteContext.number(node: Expr): ExprConstant? =
        (strip(node) as? ExprConstant)?.takeIf { it.op == ExprConstant.Op.NUMBER && written(it) }

    private fun AlloyRewriteContext.isNone(node: Expr): Boolean {
        val e = strip(node)
        return e.deNOP() == Sig.NONE && written(e)
    }

    val ALL: List<AlloyRewriteRule> = listOf(negatedMultiplicity, cardinality, none)
}
