package com.serranofp.alloy.simplifications

import edu.mit.csail.sdg.ast.Expr
import edu.mit.csail.sdg.ast.ExprBinary
import edu.mit.csail.sdg.ast.ExprList
import edu.mit.csail.sdg.ast.ExprUnary

/** A simplification of [family], which an inspection reports. */
internal class AlloySimplification(
    override val family: AlloyRewriteFamily,
    private val rule: AlloyRewriteContext.(Expr) -> AlloyRewrite?,
) : AlloyRewriteRule {
    override val kind: AlloyRewriteKind get() = AlloyRewriteKind.SIMPLIFICATION

    override fun match(node: Expr, context: AlloyRewriteContext): AlloyRewrite? = context.rule(node)
}

/** A rewrite of [family] between equivalent forms, which an intention offers. */
internal class AlloyEquivalence(
    override val family: AlloyRewriteFamily,
    private val rule: AlloyRewriteContext.(Expr) -> AlloyRewrite?,
) : AlloyRewriteRule {
    override val kind: AlloyRewriteKind get() = AlloyRewriteKind.EQUIVALENCE

    override fun match(node: Expr, context: AlloyRewriteContext): AlloyRewrite? = context.rule(node)
}

/** A rewrite of [node], with the parentheses written around it, by [replacement]. */
internal fun AlloyRewriteContext.rewrite(
    node: Expr,
    replacement: AlloyPiece?,
    message: String,
    fixName: (String) -> String,
    uses: List<Expr> = emptyList(),
    discards: List<Expr> = emptyList(),
): AlloyRewrite? {
    val range = source(node)?.range ?: return null
    return AlloyRewrite(range, replacement ?: return null, message, uses, discards, fixName = fixName)
}

/** "Simplify to '…'", or [otherwise] when the replacement is long or spans lines. */
internal fun simplifyTo(otherwise: String): (String) -> String =
    { if (it.length <= 40 && '\n' !in it) "Simplify to '$it'" else otherwise }

/** [node], stripped, if it is written with the unary operator [op]. */
internal fun AlloyRewriteContext.unary(node: Expr, op: ExprUnary.Op): ExprUnary? =
    (strip(node) as? ExprUnary)?.takeIf { it.op == op && written(it) }

/** [node], stripped, if it is written with the binary operator [op]. */
internal fun AlloyRewriteContext.binary(node: Expr, vararg op: ExprBinary.Op): ExprBinary? =
    (strip(node) as? ExprBinary)?.takeIf { it.op in op && written(it) }

/** [node], stripped, if it is a written list of [op]. */
internal fun AlloyRewriteContext.list(node: Expr, op: ExprList.Op): ExprList? =
    (strip(node) as? ExprList)?.takeIf { it.op == op && written(it) }

/**
 * A rewrite of two adjacent operands of the `and` [list], `[first]` and `[first] + 1`, together, which
 * [combine] turns into one; the text between them, a line break or an `and`, goes too.
 */
internal fun AlloyRewriteContext.adjacentConjuncts(
    list: ExprList,
    message: String,
    fixName: (String) -> String,
    combine: AlloyRewriteContext.(Expr, Expr) -> Pair<AlloyPiece, List<Expr>>?,
): AlloyRewrite? {
    if (list.op != ExprList.Op.AND || !written(list)) return null
    for (i in 0 until list.args.size - 1) {
        val (replacement, discards) = combine(strip(list.args[i]), strip(list.args[i + 1])) ?: continue
        val first = source(list.args[i]) ?: continue
        val second = source(list.args[i + 1]) ?: continue
        val range = first.range.union(second.range)
        return AlloyRewrite(range, replacement, message, listOf(list), discards, operandsPosition(list, i, i + 1), fixName)
    }
    return null
}
