package com.serranofp.alloy.simplifications

import edu.mit.csail.sdg.ast.ExprBinary
import edu.mit.csail.sdg.ast.ExprList
import edu.mit.csail.sdg.ast.ExprUnary

/** The simplifications of "Boolean formula can be simplified". */
object AlloyBooleanRules {
    private val family = AlloyRewriteFamily.BOOLEAN

    private val COMPARISONS = arrayOf(
        ExprBinary.Op.EQUALS, ExprBinary.Op.NOT_EQUALS, ExprBinary.Op.IN, ExprBinary.Op.NOT_IN,
        ExprBinary.Op.LT, ExprBinary.Op.LTE, ExprBinary.Op.GT, ExprBinary.Op.GTE,
        ExprBinary.Op.NOT_LT, ExprBinary.Op.NOT_LTE, ExprBinary.Op.NOT_GT, ExprBinary.Op.NOT_GTE,
    )

    /** `not not p` → `p` */
    val doubleNegation: AlloyRewriteRule = AlloySimplification(family) { node ->
        val outer = unary(node, ExprUnary.Op.NOT) ?: return@AlloySimplification null
        val inner = unary(outer.sub, ExprUnary.Op.NOT) ?: return@AlloySimplification null
        rewrite(node, slice(inner.sub), "Double negation", { "Remove double negation" }, uses = listOf(outer, inner))
    }

    /** `not (not a or not b …)` → `a and b …`, and `not (not a and not b …)` → `a or b …` */
    val deMorgan: AlloyRewriteRule = AlloySimplification(family) { node ->
        val not = unary(node, ExprUnary.Op.NOT) ?: return@AlloySimplification null
        val operands = (list(not.sub, ExprList.Op.OR) ?: list(not.sub, ExprList.Op.AND)) ?: return@AlloySimplification null
        if (operands.args.size < 2 || !operands.args.all { isNegation(it) }) return@AlloySimplification null
        val (dual, message) =
            if (operands.op == ExprList.Op.OR) AlloyOperator.AND to "Negated disjunction of negations"
            else AlloyOperator.OR to "Negated conjunction of negations"
        val pieces = operands.args.map { unnegate(it) ?: return@AlloySimplification null }
        rewrite(node, AlloyPiece.Op(dual, pieces), message, simplifyTo("Apply De Morgan's law"), uses = listOf(not, operands))
    }

    /** `not (a implies b)` → `a and not b`; an `implies … else` is left alone */
    val negatedImplication: AlloyRewriteRule = AlloySimplification(family) { node ->
        val not = unary(node, ExprUnary.Op.NOT) ?: return@AlloySimplification null
        val implies = binary(not.sub, ExprBinary.Op.IMPLIES) ?: return@AlloySimplification null
        val left = slice(implies.left) ?: return@AlloySimplification null
        val right = negate(implies.right) ?: return@AlloySimplification null
        rewrite(node, AlloyPiece.Op(AlloyOperator.AND, left, right), "Negated implication", simplifyTo("Remove the negated implication"), uses = listOf(not, implies))
    }

    /** `not (a = b)` → `a != b`, and likewise for every comparison */
    val negatedComparison: AlloyRewriteRule = AlloySimplification(family) { node ->
        val not = unary(node, ExprUnary.Op.NOT) ?: return@AlloySimplification null
        val comparison = binary(not.sub, *COMPARISONS) ?: return@AlloySimplification null
        rewrite(node, negate(comparison), "Negated comparison", simplifyTo("Negate the comparison"), uses = listOf(not))
    }

    /** Adjacent conjuncts `a implies b` and `b implies a` → `a iff b` */
    val converseImplications: AlloyRewriteRule = AlloySimplification(family) { node ->
        val conjuncts = strip(node) as? ExprList ?: return@AlloySimplification null
        adjacentConjuncts(conjuncts, "Converse implications", { "Replace with 'iff'" }) { first, second ->
            val a = binary(first, ExprBinary.Op.IMPLIES) ?: return@adjacentConjuncts null
            val b = binary(second, ExprBinary.Op.IMPLIES) ?: return@adjacentConjuncts null
            if (!a.left.isSame(b.right) || !a.right.isSame(b.left)) return@adjacentConjuncts null
            val left = slice(a.left, uses = listOf(a, b)) ?: return@adjacentConjuncts null
            val right = slice(a.right) ?: return@adjacentConjuncts null
            AlloyPiece.Op(AlloyOperator.IFF, left, right) to listOf(b.left, b.right)
        }
    }

    val ALL: List<AlloyRewriteRule> = listOf(doubleNegation, deMorgan, negatedImplication, negatedComparison, converseImplications)
}
