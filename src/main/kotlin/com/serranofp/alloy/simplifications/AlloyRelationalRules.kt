package com.serranofp.alloy.simplifications

import edu.mit.csail.sdg.ast.Expr
import edu.mit.csail.sdg.ast.ExprBinary
import edu.mit.csail.sdg.ast.ExprConstant
import edu.mit.csail.sdg.ast.ExprList
import edu.mit.csail.sdg.ast.ExprUnary

/** The simplifications of "Relational expression can be simplified". */
object AlloyRelationalRules {
    private val family = AlloyRewriteFamily.RELATIONAL

    /** `~~r` → `r` */
    val doubleTranspose: AlloyRewriteRule = AlloySimplification(family) { node ->
        val outer = unary(node, ExprUnary.Op.TRANSPOSE) ?: return@AlloySimplification null
        val inner = unary(outer.sub, ExprUnary.Op.TRANSPOSE) ?: return@AlloySimplification null
        rewrite(node, slice(inner.sub), "Double transpose", { "Remove double transpose" }, uses = listOf(outer, inner))
    }

    /** Whether [node] is a relation, rather than an integer, whose `+` would be an addition. */
    private fun isRelation(node: Expr): Boolean = node.type().arity() >= 1 && !node.type().is_int && !node.type().is_small_int

    /** `r + r` and `r & r` → `r` */
    val idempotence: AlloyRewriteRule = AlloySimplification(family) { node ->
        val e = binary(node, ExprBinary.Op.PLUS, ExprBinary.Op.INTERSECT) ?: return@AlloySimplification null
        if (!isRelation(e) || !isRelation(e.left) || !e.left.isSame(e.right)) return@AlloySimplification null
        val message = if (e.op == ExprBinary.Op.PLUS) "Union of an expression with itself" else "Intersection of an expression with itself"
        rewrite(node, slice(e.left), message, simplifyTo("Remove the repeated operand"), uses = listOf(e), discards = listOf(e.right))
    }

    /** `^r + iden` and `iden + ^r` → `*r` */
    val reflexiveClosure: AlloyRewriteRule = AlloySimplification(family) { node ->
        val plus = binary(node, ExprBinary.Op.PLUS) ?: return@AlloySimplification null
        val (closure, iden) = unary(plus.left, ExprUnary.Op.CLOSURE)?.let { it to plus.right }
            ?: unary(plus.right, ExprUnary.Op.CLOSURE)?.let { it to plus.left }
            ?: return@AlloySimplification null
        val constant = (strip(iden) as? ExprConstant)?.takeIf { it.op == ExprConstant.Op.IDEN && written(it) } ?: return@AlloySimplification null
        val relation = slice(closure.sub) ?: return@AlloySimplification null
        rewrite(node, AlloyPiece.Op(AlloyOperator.RCLOSURE, relation), "Closure plus identity", simplifyTo("Use the reflexive closure"), uses = listOf(plus, closure, constant))
    }

    /** Adjacent conjuncts `a in b` and `b in a` → `a = b` */
    val mutualInclusion: AlloyRewriteRule = AlloySimplification(family) { node ->
        val conjuncts = strip(node) as? ExprList ?: return@AlloySimplification null
        adjacentConjuncts(conjuncts, "Mutual inclusion", simplifyTo("Replace with '='")) { first, second ->
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
