package com.serranofp.alloy.simplifications

import edu.mit.csail.sdg.ast.Expr
import edu.mit.csail.sdg.ast.ExprBinary
import edu.mit.csail.sdg.ast.ExprUnary

/** Comparisons and their negations, both ways. */
private val NEGATED_COMPARISONS = mapOf(
    ExprBinary.Op.EQUALS to AlloyOperator.NOT_EQUALS,
    ExprBinary.Op.NOT_EQUALS to AlloyOperator.EQUALS,
    ExprBinary.Op.IN to AlloyOperator.NOT_IN,
    ExprBinary.Op.NOT_IN to AlloyOperator.IN,
    ExprBinary.Op.LT to AlloyOperator.GTE,
    ExprBinary.Op.LTE to AlloyOperator.GT,
    ExprBinary.Op.GT to AlloyOperator.LTE,
    ExprBinary.Op.GTE to AlloyOperator.LT,
    ExprBinary.Op.NOT_LT to AlloyOperator.LT,
    ExprBinary.Op.NOT_LTE to AlloyOperator.LTE,
    ExprBinary.Op.NOT_GT to AlloyOperator.GT,
    ExprBinary.Op.NOT_GTE to AlloyOperator.GTE,
)

/** The comparisons that read as a negation: `!=`, `not in` and the `!<` family. */
private val NEGATIVE_COMPARISONS = setOf(
    ExprBinary.Op.NOT_EQUALS, ExprBinary.Op.NOT_IN,
    ExprBinary.Op.NOT_LT, ExprBinary.Op.NOT_LTE, ExprBinary.Op.NOT_GT, ExprBinary.Op.NOT_GTE,
)

/**
 * Whether [operand] is written as a negation that [unnegate] takes off: `not p`, `no e`, or a negated
 * comparison such as `x != y` or `x not in y`.
 */
fun AlloyRewriteContext.isNegation(operand: Expr): Boolean {
    val e = strip(operand)
    val negation = when (e) {
        is ExprUnary -> e.op == ExprUnary.Op.NOT || e.op == ExprUnary.Op.NO
        is ExprBinary -> e.op in NEGATIVE_COMPARISONS
        else -> false
    }
    return negation && written(e)
}

/** [operand], a negation (see [isNegation]), without it: `p` for `not p`, `some e` for `no e`, `x = y` for `x != y`. */
fun AlloyRewriteContext.unnegate(operand: Expr): AlloyPiece? {
    val e = strip(operand)
    if (!isNegation(e)) return null
    return when (e) {
        is ExprUnary ->
            if (e.op == ExprUnary.Op.NOT) slice(e.sub, uses = listOf(e))
            else slice(e.sub)?.let { AlloyPiece.Op(AlloyOperator.SOME, it, uses = listOf(e)) }
        is ExprBinary -> compared(e)
        else -> null
    }
}

/**
 * The negation of [operand], written as its negated form rather than with a `not` where it has one: `p` for
 * `not p`, the negated comparison for a comparison, `no e` for `some e` and `some e` for `no e`. Otherwise
 * `not` is added.
 */
fun AlloyRewriteContext.negate(operand: Expr): AlloyPiece? {
    val e = strip(operand)
    unnegate(e)?.let { return it }
    if (written(e)) {
        if (e is ExprUnary && e.op == ExprUnary.Op.SOME) {
            slice(e.sub)?.let { return AlloyPiece.Op(AlloyOperator.NO, it, uses = listOf(e)) }
        }
        if (e is ExprBinary) compared(e)?.let { return it }
    }
    return slice(e)?.let { AlloyPiece.Op(AlloyOperator.NOT, it) }
}

/** The comparison [comparison] with its operator negated. */
private fun AlloyRewriteContext.compared(comparison: ExprBinary): AlloyPiece? {
    val operator = NEGATED_COMPARISONS[comparison.op] ?: return null
    val left = slice(comparison.left) ?: return null
    val right = slice(comparison.right) ?: return null
    return AlloyPiece.Op(operator, left, right, uses = listOf(comparison))
}
