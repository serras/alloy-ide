package com.serranofp.alloy.simplifications

import com.serranofp.alloy.AlloyBundle
import edu.mit.csail.sdg.ast.Expr
import edu.mit.csail.sdg.ast.ExprBinary
import edu.mit.csail.sdg.ast.ExprList
import edu.mit.csail.sdg.ast.ExprQt
import edu.mit.csail.sdg.ast.ExprUnary
import org.jetbrains.annotations.PropertyKey

/** The rewrites between equivalent forms that the intentions offer, one list per intention. */
object AlloyEquivalenceRules {
    /** A rewrite named by the message [key], whatever the replacement. */
    private fun AlloyRewriteContext.rewrite(
        node: Expr,
        replacement: AlloyPiece?,
        @PropertyKey(resourceBundle = "messages.AlloyBundle") key: String,
        uses: List<Expr>,
    ): AlloyRewrite? {
        val text = AlloyBundle.message(key)
        return rewrite(node, replacement, text, { text }, uses = uses)
    }

    /** `not (a or b)` → `not a and not b`, and `not (a and b)` → `not a or not b` */
    private val deMorganIn = rule { node ->
        val not = unary(node, ExprUnary.Op.NOT) ?: return@rule null
        val list = (list(not.sub, ExprList.Op.OR) ?: list(not.sub, ExprList.Op.AND))?.takeIf { it.args.size >= 2 } ?: return@rule null
        val dual = if (list.op == ExprList.Op.OR) AlloyOperator.AND else AlloyOperator.OR
        rewrite(node, AlloyPiece.Op(dual, list.args.map { negate(it) ?: return@rule null }), "simplification.de.morgan", listOf(not, list))
    }

    /** `not a and not b` → `not (a or b)`, and `not a or not b` → `not (a and b)` */
    private val deMorganOut = rule { node ->
        val list = (list(node, ExprList.Op.AND) ?: list(node, ExprList.Op.OR)) ?: return@rule null
        // Block lines are left alone: their `and` is not written
        if (list.args.size < 2 || !source.inFile(list.pos) || !list.args.all { isNegation(it) }) return@rule null
        val dual = if (list.op == ExprList.Op.AND) AlloyOperator.OR else AlloyOperator.AND
        val operands = list.args.map { unnegate(it) ?: return@rule null }
        rewrite(node, AlloyPiece.Op(AlloyOperator.NOT, AlloyPiece.Op(dual, operands)), "simplification.de.morgan", listOf(list))
    }

    val DE_MORGAN_RULES: List<AlloyRewriteRule> = listOf(deMorganIn, deMorganOut)

    /** `not a or b` → `a implies b` */
    val OR_TO_IMPLIES: List<AlloyRewriteRule> = listOf(
        rule { node ->
            val or = list(node, ExprList.Op.OR)?.takeIf { it.args.size >= 2 && isNegation(it.args[0]) } ?: return@rule null
            val premise = unnegate(or.args[0]) ?: return@rule null
            val rest = or.args.drop(1).map { slice(it) ?: return@rule null }
            val conclusion = rest.singleOrNull() ?: AlloyPiece.Op(AlloyOperator.OR, rest)
            rewrite(node, AlloyPiece.Op(AlloyOperator.IMPLIES, premise, conclusion), "simplification.intention.or.to.implies", listOf(or))
        },
    )

    /** `a implies b` → `not a or b`; an `implies … else` is left alone */
    val IMPLIES_TO_OR: List<AlloyRewriteRule> = listOf(
        rule { node ->
            val implies = binary(node, ExprBinary.Op.IMPLIES) ?: return@rule null
            val left = negate(implies.left) ?: return@rule null
            val right = slice(implies.right) ?: return@rule null
            rewrite(node, AlloyPiece.Op(AlloyOperator.OR, left, right), "simplification.intention.implies.to.or", listOf(implies))
        },
    )

    private fun dual(op: ExprQt.Op): String = if (op == ExprQt.Op.ALL) "some" else "all"

    /** `not (all x: A | p)` → `some x: A | not p`, and `not (some x: A | p)` → `all x: A | not p`, and back */
    val QUANTIFIER_NEGATION: List<AlloyRewriteRule> = listOf(
        rule { node ->
            val not = unary(node, ExprUnary.Op.NOT) ?: return@rule null
            val qt = quantifier(not.sub, ExprQt.Op.ALL, ExprQt.Op.SOME) ?: return@rule null
            rewrite(node, bind(dual(qt.op), qt, negate(qt.sub)), "simplification.intention.quantifier.negation.in", listOf(not, qt))
        },
        rule { node ->
            val qt = quantifier(node, ExprQt.Op.ALL, ExprQt.Op.SOME)?.takeIf { isNegation(it.sub) } ?: return@rule null
            // Parenthesized, as the negation of a quantifier is usually written
            val negated = bind(dual(qt.op), qt, unnegate(qt.sub))?.let { AlloyPiece.Op(AlloyOperator.NOT, AlloyPiece.Group(it)) }
            rewrite(node, negated, "simplification.intention.quantifier.negation.out", listOf(qt))
        },
    )

    /** The duals of the unary temporal operators; `before` has none, since `before p` is false in the first state. */
    private val UNARY_DUALS = mapOf(
        ExprUnary.Op.ALWAYS to AlloyOperator.EVENTUALLY,
        ExprUnary.Op.EVENTUALLY to AlloyOperator.ALWAYS,
        ExprUnary.Op.AFTER to AlloyOperator.AFTER,
        ExprUnary.Op.HISTORICALLY to AlloyOperator.ONCE,
        ExprUnary.Op.ONCE to AlloyOperator.HISTORICALLY,
    )

    private val BINARY_DUALS = mapOf(
        ExprBinary.Op.UNTIL to AlloyOperator.RELEASES,
        ExprBinary.Op.RELEASES to AlloyOperator.UNTIL,
        ExprBinary.Op.SINCE to AlloyOperator.TRIGGERED,
        ExprBinary.Op.TRIGGERED to AlloyOperator.SINCE,
    )

    /**
     * `not always p` ↔ `eventually not p`, `not eventually p` ↔ `always not p`, `not after p` ↔ `after not p`,
     * `not historically p` ↔ `once not p`, `not once p` ↔ `historically not p`, and `not (p until q)` ↔
     * `not p releases not q`, `not (p since q)` ↔ `not p triggered not q` and the other way round
     */
    val TEMPORAL_NEGATION: List<AlloyRewriteRule> = listOf(
        rule { node ->
            val not = unary(node, ExprUnary.Op.NOT) ?: return@rule null
            val sub = strip(not.sub)
            if (!written(sub)) return@rule null
            val replacement = when (sub) {
                is ExprUnary -> UNARY_DUALS[sub.op]?.let { dual -> negate(sub.sub)?.let { AlloyPiece.Op(dual, it) } }
                is ExprBinary -> BINARY_DUALS[sub.op]?.let { dual ->
                    AlloyPiece.Op(dual, negate(sub.left) ?: return@rule null, negate(sub.right) ?: return@rule null)
                }
                else -> null
            }
            rewrite(node, replacement, "simplification.intention.temporal.negation.in", listOf(not, sub))
        },
        rule { node ->
            val e = strip(node)
            if (!written(e)) return@rule null
            val replacement = when (e) {
                is ExprUnary -> UNARY_DUALS[e.op]?.takeIf { isNegation(e.sub) }?.let { dual -> unnegate(e.sub)?.let { AlloyPiece.Op(dual, it) } }
                is ExprBinary -> BINARY_DUALS[e.op]?.takeIf { isNegation(e.left) && isNegation(e.right) }?.let { dual ->
                    AlloyPiece.Op(dual, unnegate(e.left) ?: return@rule null, unnegate(e.right) ?: return@rule null)
                }
                else -> null
            }
            rewrite(node, replacement?.let { AlloyPiece.Op(AlloyOperator.NOT, it) }, "simplification.intention.temporal.negation.out", listOf(e))
        },
    )

    /** The temporal operators that distribute over a list: `always` and `historically` over `and`, `eventually` and `once` over `or`. */
    private val DISTRIBUTES = mapOf(
        ExprUnary.Op.ALWAYS to ExprList.Op.AND,
        ExprUnary.Op.HISTORICALLY to ExprList.Op.AND,
        ExprUnary.Op.EVENTUALLY to ExprList.Op.OR,
        ExprUnary.Op.ONCE to ExprList.Op.OR,
    )

    /**
     * `always (p and q …)` → `always p and always q …`, and likewise `historically` over `and`, and
     * `eventually` and `once` over `or`; and back
     */
    val TEMPORAL_DISTRIBUTION: List<AlloyRewriteRule> = listOf(
        rule { node ->
            val temporal = (strip(node) as? ExprUnary)?.takeIf { it.op in DISTRIBUTES && written(it) } ?: return@rule null
            val list = list(temporal.sub, DISTRIBUTES.getValue(temporal.op))?.takeIf { it.args.size >= 2 } ?: return@rule null
            val operator = AlloyOperator.of(temporal)!!
            val operands = list.args.map { AlloyPiece.Op(operator, slice(it) ?: return@rule null) }
            rewrite(node, AlloyPiece.Op(AlloyOperator.of(list)!!, operands), "simplification.intention.temporal.distribution.in", listOf(temporal, list))
        },
        rule { node ->
            val list = (list(node, ExprList.Op.AND) ?: list(node, ExprList.Op.OR))?.takeIf { it.args.size >= 2 && source.inFile(it.pos) }
                ?: return@rule null
            val operands = list.args.map { strip(it) as? ExprUnary ?: return@rule null }
            val op = operands.first().op
            if (DISTRIBUTES[op] != list.op || operands.any { it.op != op || !written(it) }) return@rule null
            val subs = operands.map { slice(it.sub) ?: return@rule null }
            val factored = AlloyPiece.Op(AlloyOperator.of(operands.first())!!, AlloyPiece.Op(AlloyOperator.of(list)!!, subs))
            rewrite(node, factored, "simplification.intention.temporal.distribution.out", listOf(list) + operands)
        },
    )

    private fun arity(e: Expr): Int = e.type().arity()

    /** `e.~r` → `r.e`, and `r.e` → `e.~r` (or `(~s).e` → `e.s`), for a set `e` and a binary relation `r` */
    val TRANSPOSE_JOIN: List<AlloyRewriteRule> = listOf(
        rule { node ->
            val join = binary(node, ExprBinary.Op.JOIN) ?: return@rule null
            val transpose = unary(join.right, ExprUnary.Op.TRANSPOSE)
            val replacement = when {
                transpose != null && arity(join.left) == 1 && arity(transpose.sub) == 2 ->
                    AlloyPiece.Op(AlloyOperator.JOIN, slice(transpose.sub) ?: return@rule null, slice(join.left) ?: return@rule null, uses = listOf(transpose))
                arity(join.left) == 2 && arity(join.right) == 1 -> {
                    // A transpose of a transpose would be reported as simplifiable: it is dropped instead
                    val transposed = unary(join.left, ExprUnary.Op.TRANSPOSE)
                        ?.let { slice(it.sub, uses = listOf(it)) ?: return@rule null }
                        ?: AlloyPiece.Op(AlloyOperator.TRANSPOSE, slice(join.left) ?: return@rule null)
                    AlloyPiece.Op(AlloyOperator.JOIN, slice(join.right) ?: return@rule null, transposed)
                }
                else -> null
            }
            rewrite(node, replacement, "simplification.intention.transpose.join", listOf(join))
        },
    )
}
