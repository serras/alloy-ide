package com.serranofp.alloy.simplifications

import com.serranofp.alloy.AlloyBundle
import edu.mit.csail.sdg.ast.ExprBinary
import edu.mit.csail.sdg.ast.ExprList
import edu.mit.csail.sdg.ast.ExprUnary

/** The simplifications of "Boolean formula can be simplified". */
object AlloyBooleanRules {
    private val COMPARISONS = NEGATED_COMPARISONS.keys.toTypedArray()

    /** `not not p` → `p` */
    val doubleNegation: AlloyRewriteRule = rule { node ->
        val outer = unary(node, ExprUnary.Op.NOT) ?: return@rule null
        val inner = unary(outer.sub, ExprUnary.Op.NOT) ?: return@rule null
        val fix = AlloyBundle.message("simplification.double.negation.fix")
        rewrite(node, slice(inner.sub), AlloyBundle.message("simplification.double.negation"), { fix }, uses = listOf(outer, inner))
    }

    /** `not (not a or not b …)` → `a and b …`, and `not (not a and not b …)` → `a or b …` */
    val deMorgan: AlloyRewriteRule = rule { node ->
        val not = unary(node, ExprUnary.Op.NOT) ?: return@rule null
        val operands = (list(not.sub, ExprList.Op.OR) ?: list(not.sub, ExprList.Op.AND)) ?: return@rule null
        if (operands.args.size < 2 || !operands.args.all { isNegation(it) }) return@rule null
        val (dual, message) =
            if (operands.op == ExprList.Op.OR) AlloyOperator.AND to "simplification.negated.disjunction"
            else AlloyOperator.OR to "simplification.negated.conjunction"
        val pieces = operands.args.map { unnegate(it) ?: return@rule null }
        rewrite(
            node, AlloyPiece.Op(dual, pieces), AlloyBundle.message(message), simplifyTo(AlloyBundle.message("simplification.de.morgan")),
            uses = listOf(not, operands),
        )
    }

    /** `not (a implies b)` → `a and not b`; an `implies … else` is left alone */
    val negatedImplication: AlloyRewriteRule = rule { node ->
        val not = unary(node, ExprUnary.Op.NOT) ?: return@rule null
        val implies = binary(not.sub, ExprBinary.Op.IMPLIES) ?: return@rule null
        val left = slice(implies.left) ?: return@rule null
        val right = negate(implies.right) ?: return@rule null
        rewrite(
            node, AlloyPiece.Op(AlloyOperator.AND, left, right), AlloyBundle.message("simplification.negated.implication"),
            simplifyTo(AlloyBundle.message("simplification.negated.implication.fix")), uses = listOf(not, implies),
        )
    }

    /** `not (a = b)` → `a != b`, and likewise for every comparison */
    val negatedComparison: AlloyRewriteRule = rule { node ->
        val not = unary(node, ExprUnary.Op.NOT) ?: return@rule null
        val comparison = binary(not.sub, *COMPARISONS) ?: return@rule null
        rewrite(
            node, negate(comparison), AlloyBundle.message("simplification.negated.comparison"),
            simplifyTo(AlloyBundle.message("simplification.negated.comparison.fix")), uses = listOf(not),
        )
    }

    /** Adjacent conjuncts `a implies b` and `b implies a` → `a iff b` */
    val converseImplications: AlloyRewriteRule = rule { node ->
        val conjuncts = strip(node) as? ExprList ?: return@rule null
        adjacentConjuncts(conjuncts, AlloyBundle.message("simplification.converse.implications"), replaceWith("iff")) { first, second ->
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
