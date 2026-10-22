package com.serranofp.alloy.simplifications

import com.serranofp.alloy.AlloyBundle
import edu.mit.csail.sdg.ast.Expr
import edu.mit.csail.sdg.ast.ExprBinary
import edu.mit.csail.sdg.ast.ExprConstant
import edu.mit.csail.sdg.ast.ExprList
import edu.mit.csail.sdg.ast.ExprUnary

/** The simplifications of "Relational expression can be simplified". */
object AlloyRelationalRules {
    /** `~~r` → `r` */
    val doubleTranspose: AlloyRewriteRule = rule { node ->
        val outer = unary(node, ExprUnary.Op.TRANSPOSE) ?: return@rule null
        val inner = unary(outer.sub, ExprUnary.Op.TRANSPOSE) ?: return@rule null
        val fix = AlloyBundle.message("simplification.double.transpose.fix")
        rewrite(node, slice(inner.sub), AlloyBundle.message("simplification.double.transpose"), { fix }, uses = listOf(outer, inner))
    }

    /** Whether [node] is a relation, rather than an integer, whose `+` would be an addition. */
    private fun isRelation(node: Expr): Boolean = node.type().arity() >= 1 && !node.type().is_int && !node.type().is_small_int

    /** `r + r` and `r & r` → `r` */
    val idempotence: AlloyRewriteRule = rule { node ->
        val e = binary(node, ExprBinary.Op.PLUS, ExprBinary.Op.INTERSECT) ?: return@rule null
        if (!isRelation(e) || !isRelation(e.left) || !e.left.isSame(e.right)) return@rule null
        val message = if (e.op == ExprBinary.Op.PLUS) "simplification.union.idempotence" else "simplification.intersection.idempotence"
        rewrite(
            node, slice(e.left), AlloyBundle.message(message), simplifyTo(AlloyBundle.message("simplification.idempotence.fix")),
            uses = listOf(e), discards = listOf(e.right),
        )
    }

    /** `^r + iden` and `iden + ^r` → `*r` */
    val reflexiveClosure: AlloyRewriteRule = rule { node ->
        val plus = binary(node, ExprBinary.Op.PLUS) ?: return@rule null
        val (closure, iden) = unary(plus.left, ExprUnary.Op.CLOSURE)?.let { it to plus.right }
            ?: unary(plus.right, ExprUnary.Op.CLOSURE)?.let { it to plus.left }
            ?: return@rule null
        val constant = (strip(iden) as? ExprConstant)?.takeIf { it.op == ExprConstant.Op.IDEN && written(it) } ?: return@rule null
        val relation = slice(closure.sub) ?: return@rule null
        rewrite(
            node, AlloyPiece.Op(AlloyOperator.RCLOSURE, relation), AlloyBundle.message("simplification.reflexive.closure"),
            simplifyTo(AlloyBundle.message("simplification.reflexive.closure.fix")), uses = listOf(plus, closure, constant),
        )
    }

    /** Adjacent conjuncts `a in b` and `b in a` → `a = b` */
    val mutualInclusion: AlloyRewriteRule = rule { node ->
        val conjuncts = strip(node) as? ExprList ?: return@rule null
        val fixName = simplifyTo(AlloyBundle.message("simplification.fix.replace.with", "="))
        adjacentConjuncts(conjuncts, AlloyBundle.message("simplification.mutual.inclusion"), fixName) { first, second ->
            val a = binary(first, ExprBinary.Op.IN) ?: return@adjacentConjuncts null
            val b = binary(second, ExprBinary.Op.IN) ?: return@adjacentConjuncts null
            if (!a.left.isSame(b.right) || !a.right.isSame(b.left)) return@adjacentConjuncts null
            val left = slice(a.left, uses = listOf(a, b)) ?: return@adjacentConjuncts null
            val right = slice(a.right) ?: return@adjacentConjuncts null
            AlloyPiece.Op(AlloyOperator.EQUALS, left, right) to listOf(b.left, b.right)
        }
    }

    val ALL: List<AlloyRewriteRule> = listOf(doubleTranspose, idempotence, reflexiveClosure, mutualInclusion)
}
