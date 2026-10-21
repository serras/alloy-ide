package com.serranofp.alloy.simplifications

import edu.mit.csail.sdg.ast.Expr
import edu.mit.csail.sdg.ast.ExprBinary
import edu.mit.csail.sdg.ast.ExprList
import edu.mit.csail.sdg.ast.ExprQt
import edu.mit.csail.sdg.ast.ExprUnary

/** The rewrites between equivalent forms that the intentions offer, one list per intention. */
object AlloyEquivalenceRules {
    private fun equivalence(name: String, rule: AlloyRewriteContext.(Expr) -> AlloyRewrite?): AlloyRewriteRule =
        AlloyEquivalence(AlloyRewriteFamily(name), rule)

    /** A rewrite named [text], whatever the replacement. */
    private fun AlloyRewriteContext.rewrite(node: Expr, replacement: AlloyPiece?, text: String, uses: List<Expr>): AlloyRewrite? =
        rewrite(node, replacement, text, { text }, uses = uses)

    private const val DE_MORGAN = "Apply De Morgan's law"

    /** `not (a or b)` → `not a and not b`, and `not (a and b)` → `not a or not b` */
    private val deMorganIn = equivalence(DE_MORGAN) { node ->
        val not = unary(node, ExprUnary.Op.NOT) ?: return@equivalence null
        val list = (list(not.sub, ExprList.Op.OR) ?: list(not.sub, ExprList.Op.AND))?.takeIf { it.args.size >= 2 } ?: return@equivalence null
        val dual = if (list.op == ExprList.Op.OR) AlloyOperator.AND else AlloyOperator.OR
        rewrite(node, AlloyPiece.Op(dual, list.args.map { negate(it) ?: return@equivalence null }), DE_MORGAN, listOf(not, list))
    }

    /** `not a and not b` → `not (a or b)`, and `not a or not b` → `not (a and b)` */
    private val deMorganOut = equivalence(DE_MORGAN) { node ->
        val list = (list(node, ExprList.Op.AND) ?: list(node, ExprList.Op.OR)) ?: return@equivalence null
        // Block lines are left alone: their `and` is not written
        if (list.args.size < 2 || !source.inFile(list.pos) || !list.args.all { isNegation(it) }) return@equivalence null
        val dual = if (list.op == ExprList.Op.AND) AlloyOperator.OR else AlloyOperator.AND
        val operands = list.args.map { unnegate(it) ?: return@equivalence null }
        rewrite(node, AlloyPiece.Op(AlloyOperator.NOT, AlloyPiece.Op(dual, operands)), DE_MORGAN, listOf(list))
    }

    val DE_MORGAN_RULES: List<AlloyRewriteRule> = listOf(deMorganIn, deMorganOut)

    /** `not a or b` → `a implies b` */
    val OR_TO_IMPLIES: List<AlloyRewriteRule> = listOf(
        equivalence("Replace 'or' with 'implies'") { node ->
            val or = list(node, ExprList.Op.OR)?.takeIf { it.args.size >= 2 && isNegation(it.args[0]) } ?: return@equivalence null
            val premise = unnegate(or.args[0]) ?: return@equivalence null
            val rest = or.args.drop(1).map { slice(it) ?: return@equivalence null }
            val conclusion = rest.singleOrNull() ?: AlloyPiece.Op(AlloyOperator.OR, rest)
            rewrite(node, AlloyPiece.Op(AlloyOperator.IMPLIES, premise, conclusion), "Replace 'or' with 'implies'", listOf(or))
        },
    )

    /** `a implies b` → `not a or b`; an `implies … else` is left alone */
    val IMPLIES_TO_OR: List<AlloyRewriteRule> = listOf(
        equivalence("Replace 'implies' with 'or'") { node ->
            val implies = binary(node, ExprBinary.Op.IMPLIES) ?: return@equivalence null
            val left = negate(implies.left) ?: return@equivalence null
            val right = slice(implies.right) ?: return@equivalence null
            rewrite(node, AlloyPiece.Op(AlloyOperator.OR, left, right), "Replace 'implies' with 'or'", listOf(implies))
        },
    )

    /** `quantifier` with the declarations of [qt] as written and [body]. */
    private fun AlloyRewriteContext.bind(quantifier: String, qt: ExprQt, body: AlloyPiece?): AlloyPiece? {
        val decls = decls(qt) ?: return null
        return AlloyPiece.Bind(quantifier, source.document.getText(decls), decls, body ?: return null)
    }

    private fun AlloyRewriteContext.quantifier(node: Expr): ExprQt? =
        (strip(node) as? ExprQt)?.takeIf { (it.op == ExprQt.Op.ALL || it.op == ExprQt.Op.SOME) && written(it) }

    private fun dual(op: ExprQt.Op): String = if (op == ExprQt.Op.ALL) "some" else "all"

    /** `not (all x: A | p)` → `some x: A | not p`, and `not (some x: A | p)` → `all x: A | not p`, and back */
    val QUANTIFIER_NEGATION: List<AlloyRewriteRule> = listOf(
        equivalence("Move negation into quantifier") { node ->
            val not = unary(node, ExprUnary.Op.NOT) ?: return@equivalence null
            val qt = quantifier(not.sub) ?: return@equivalence null
            rewrite(node, bind(dual(qt.op), qt, negate(qt.sub)), "Move negation into quantifier", listOf(not, qt))
        },
        equivalence("Move negation out of quantifier") { node ->
            val qt = quantifier(node)?.takeIf { isNegation(it.sub) } ?: return@equivalence null
            // Parenthesized, as the negation of a quantifier is usually written
            val negated = bind(dual(qt.op), qt, unnegate(qt.sub))?.let { AlloyPiece.Op(AlloyOperator.NOT, AlloyPiece.Group(it)) }
            rewrite(node, negated, "Move negation out of quantifier", listOf(qt))
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

    private const val INTO_TEMPORAL = "Move negation into temporal operator"
    private const val OUT_OF_TEMPORAL = "Move negation out of temporal operator"

    /**
     * `not always p` ↔ `eventually not p`, `not eventually p` ↔ `always not p`, `not after p` ↔ `after not p`,
     * `not historically p` ↔ `once not p`, `not once p` ↔ `historically not p`, and `not (p until q)` ↔
     * `not p releases not q`, `not (p since q)` ↔ `not p triggered not q` and the other way round
     */
    val TEMPORAL_NEGATION: List<AlloyRewriteRule> = listOf(
        equivalence(INTO_TEMPORAL) { node ->
            val not = unary(node, ExprUnary.Op.NOT) ?: return@equivalence null
            val sub = strip(not.sub)
            if (!written(sub)) return@equivalence null
            val replacement = when (sub) {
                is ExprUnary -> UNARY_DUALS[sub.op]?.let { dual -> negate(sub.sub)?.let { AlloyPiece.Op(dual, it) } }
                is ExprBinary -> BINARY_DUALS[sub.op]?.let { dual ->
                    AlloyPiece.Op(dual, negate(sub.left) ?: return@equivalence null, negate(sub.right) ?: return@equivalence null)
                }
                else -> null
            }
            rewrite(node, replacement, INTO_TEMPORAL, listOf(not, sub))
        },
        equivalence(OUT_OF_TEMPORAL) { node ->
            val e = strip(node)
            if (!written(e)) return@equivalence null
            val replacement = when (e) {
                is ExprUnary -> UNARY_DUALS[e.op]?.takeIf { isNegation(e.sub) }?.let { dual -> unnegate(e.sub)?.let { AlloyPiece.Op(dual, it) } }
                is ExprBinary -> BINARY_DUALS[e.op]?.takeIf { isNegation(e.left) && isNegation(e.right) }?.let { dual ->
                    AlloyPiece.Op(dual, unnegate(e.left) ?: return@equivalence null, unnegate(e.right) ?: return@equivalence null)
                }
                else -> null
            }
            rewrite(node, replacement?.let { AlloyPiece.Op(AlloyOperator.NOT, it) }, OUT_OF_TEMPORAL, listOf(e))
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
        equivalence("Distribute temporal operator") { node ->
            val temporal = (strip(node) as? ExprUnary)?.takeIf { it.op in DISTRIBUTES && written(it) } ?: return@equivalence null
            val list = list(temporal.sub, DISTRIBUTES.getValue(temporal.op))?.takeIf { it.args.size >= 2 } ?: return@equivalence null
            val operator = AlloyOperator.of(temporal)!!
            val operands = list.args.map { AlloyPiece.Op(operator, slice(it) ?: return@equivalence null) }
            rewrite(node, AlloyPiece.Op(AlloyOperator.of(list)!!, operands), "Distribute temporal operator", listOf(temporal, list))
        },
        equivalence("Factor out temporal operator") { node ->
            val list = (list(node, ExprList.Op.AND) ?: list(node, ExprList.Op.OR))?.takeIf { it.args.size >= 2 && source.inFile(it.pos) }
                ?: return@equivalence null
            val operands = list.args.map { strip(it) as? ExprUnary ?: return@equivalence null }
            val op = operands.first().op
            if (DISTRIBUTES[op] != list.op || operands.any { it.op != op || !written(it) }) return@equivalence null
            val subs = operands.map { slice(it.sub) ?: return@equivalence null }
            val factored = AlloyPiece.Op(AlloyOperator.of(operands.first())!!, AlloyPiece.Op(AlloyOperator.of(list)!!, subs))
            rewrite(node, factored, "Factor out temporal operator", listOf(list) + operands)
        },
    )

    private fun arity(e: Expr): Int = e.type().arity()

    /** `e.~r` → `r.e`, and `r.e` → `e.~r`, for a set `e` and a binary relation `r` */
    val TRANSPOSE_JOIN: List<AlloyRewriteRule> = listOf(
        equivalence("Swap join with transpose") { node ->
            val join = binary(node, ExprBinary.Op.JOIN) ?: return@equivalence null
            val transpose = unary(join.right, ExprUnary.Op.TRANSPOSE)
            val replacement = when {
                transpose != null && arity(join.left) == 1 && arity(transpose.sub) == 2 ->
                    AlloyPiece.Op(AlloyOperator.JOIN, slice(transpose.sub) ?: return@equivalence null, slice(join.left) ?: return@equivalence null, uses = listOf(transpose))
                arity(join.left) == 2 && arity(join.right) == 1 -> AlloyPiece.Op(
                    AlloyOperator.JOIN,
                    slice(join.right) ?: return@equivalence null,
                    AlloyPiece.Op(AlloyOperator.TRANSPOSE, slice(join.left) ?: return@equivalence null),
                )
                else -> null
            }
            rewrite(node, replacement, "Swap join with transpose", listOf(join))
        },
    )
}
