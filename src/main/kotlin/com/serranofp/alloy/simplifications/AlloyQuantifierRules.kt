package com.serranofp.alloy.simplifications

import com.serranofp.alloy.AlloyBundle
import edu.mit.csail.sdg.ast.Expr
import edu.mit.csail.sdg.ast.ExprBinary
import edu.mit.csail.sdg.ast.ExprList
import edu.mit.csail.sdg.ast.ExprQt
import edu.mit.csail.sdg.ast.ExprUnary
import edu.mit.csail.sdg.ast.ExprVar

/** The simplifications of "Quantified formula can be simplified". */
object AlloyQuantifierRules {
    /**
     * `not (some x: A | p)` → `no x: A | p`, `not (no x: A | p)` → `some x: A | p` and
     * `not (all x: A | not p)` → `some x: A | p`
     */
    val negatedQuantifier: AlloyRewriteRule = rule { node ->
        val not = unary(node, ExprUnary.Op.NOT) ?: return@rule null
        val (qt, replacement) = quantifier(not.sub, ExprQt.Op.SOME)?.let { it to bind("no", it, slice(it.sub)) }
            ?: quantifier(not.sub, ExprQt.Op.NO)?.let { it to bind("some", it, slice(it.sub)) }
            ?: quantifier(not.sub, ExprQt.Op.ALL)?.takeIf { isNegation(it.sub) }?.let { it to bind("some", it, unnegate(it.sub)) }
            ?: return@rule null
        val fixName = simplifyTo(AlloyBundle.message("simplification.negated.quantifier.fix"))
        rewrite(node, replacement, AlloyBundle.message("simplification.negated.quantifier"), fixName, uses = listOf(not, qt))
    }

    /** `all x: A | not p` → `no x: A | p` */
    val universalNegation: AlloyRewriteRule = rule { node ->
        val qt = quantifier(node, ExprQt.Op.ALL) ?: return@rule null
        val not = unary(qt.sub, ExprUnary.Op.NOT) ?: return@rule null
        rewrite(node, bind("no", qt, slice(not.sub)), AlloyBundle.message("simplification.universal.negation"), replaceWith("no"), uses = listOf(qt, not))
    }

    /**
     * `all x: A | x in B` → `A in B`, `some x: A | x in B` → `some A & B` and `no x: A | x in B` → `no A & B`,
     * when `x` is the only variable, has a plain or `one` bound, and does not occur in `B`
     */
    val membership: AlloyRewriteRule = rule { node ->
        val qt = quantifier(node, ExprQt.Op.ALL, ExprQt.Op.SOME, ExprQt.Op.NO) ?: return@rule null
        if (qt.decls.size != 1 || qt.count() != 1 || qt.decls[0].disjoint != null) return@rule null
        val decl = qt.decls[0]
        val variable = decl.names[0] as? ExprVar ?: return@rule null
        val bound = (decl.expr.deNOP() as? ExprUnary)?.takeIf { it.op == ExprUnary.Op.ONEOF } ?: return@rule null
        val membership = binary(qt.sub, ExprBinary.Op.IN) ?: return@rule null
        if (strip(membership.left).deNOP() != variable || membership.right.hasVar(variable)) return@rule null
        val set = slice(bound.sub) ?: return@rule null
        val other = slice(membership.right) ?: return@rule null
        val replacement = when (qt.op) {
            ExprQt.Op.ALL -> AlloyPiece.Op(AlloyOperator.IN, set, other)
            ExprQt.Op.SOME -> AlloyPiece.Op(AlloyOperator.SOME, AlloyPiece.Op(AlloyOperator.INTERSECT, set, other))
            else -> AlloyPiece.Op(AlloyOperator.NO, AlloyPiece.Op(AlloyOperator.INTERSECT, set, other))
        }
        val uses = listOf(qt, membership) + listOfNotNull(bound.takeIf { written(it) })
        val fixName = simplifyTo(AlloyBundle.message("simplification.quantified.membership.fix"))
        rewrite(
            node, replacement, AlloyBundle.message("simplification.quantified.membership"), fixName,
            uses = uses, discards = listOf(variable, membership.left),
        )
    }

    /** Whether [test] reads `x != y`, `y != x` or `not x = y`. */
    private fun AlloyRewriteContext.distinct(test: Expr, x: ExprVar, y: ExprVar): Boolean {
        val e = strip(test)
        val comparison = binary(e, ExprBinary.Op.NOT_EQUALS)
            ?: unary(e, ExprUnary.Op.NOT)?.let { binary(it.sub, ExprBinary.Op.EQUALS) }
            ?: return false
        val left = strip(comparison.left).deNOP()
        val right = strip(comparison.right).deNOP()
        return (left == x && right == y) || (left == y && right == x)
    }

    /**
     * `all x, y: A | x != y implies p` → `all disj x, y: A | p` and `some x, y: A | x != y and p` →
     * `some disj x, y: A | p`, when `x` and `y` are the only names of their declaration, which is not `disj`
     */
    val distinctVariables: AlloyRewriteRule = rule { node ->
        val qt = quantifier(node, ExprQt.Op.ALL, ExprQt.Op.SOME) ?: return@rule null
        val decls = decls(qt) ?: return@rule null
        for (decl in qt.decls) {
            if (decl.names.size != 2 || decl.disjoint != null) continue
            val x = decl.names[0] as? ExprVar ?: continue
            val y = decl.names[1] as? ExprVar ?: continue
            val (test, body, uses) = if (qt.op == ExprQt.Op.ALL) {
                val implies = binary(qt.sub, ExprBinary.Op.IMPLIES) ?: continue
                Triple(implies.left, slice(implies.right), listOf(implies))
            } else {
                val conjunction = list(qt.sub, ExprList.Op.AND) ?: continue
                val rest = conjunction.args.drop(1).mapNotNull { slice(it) }.takeIf { it.size == conjunction.args.size - 1 } ?: continue
                val body = if (rest.size == 1) rest.single() else AlloyPiece.Op(AlloyOperator.AND, rest)
                Triple(conjunction.args[0], body, listOf(conjunction))
            }
            if (!distinct(test, x, y)) continue
            val first = source.source(x)?.range ?: continue
            val text = source.document.getText(decls)
            val offset = first.startOffset - decls.startOffset
            val withDisj = text.substring(0, offset) + "disj " + text.substring(offset)
            val keyword = if (qt.op == ExprQt.Op.ALL) "all" else "some"
            val replacement = AlloyPiece.Bind(keyword, withDisj, decls, body ?: continue)
            val fix = AlloyBundle.message("simplification.distinct.variables.fix")
            return@rule rewrite(
                node, replacement, AlloyBundle.message("simplification.distinct.variables"), { fix },
                uses = listOf(qt) + uses, discards = listOf(test),
            )
        }
        null
    }

    val ALL: List<AlloyRewriteRule> = listOf(negatedQuantifier, universalNegation, membership, distinctVariables)
}
