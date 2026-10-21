package com.serranofp.alloy.simplifications

import edu.mit.csail.sdg.ast.ExprUnary

/** The simplifications of "Temporal formula can be simplified". */
object AlloyTemporalRules {
    private val family = AlloyRewriteFamily.TEMPORAL

    /** The operators that are idempotent: `after` and `before` are not, as `after after p` is two states later. */
    private val IDEMPOTENT = listOf(ExprUnary.Op.ALWAYS, ExprUnary.Op.EVENTUALLY, ExprUnary.Op.HISTORICALLY, ExprUnary.Op.ONCE)

    /** `always always p` → `always p`, and likewise for `eventually`, `historically` and `once` */
    val idempotence: AlloyRewriteRule = AlloySimplification(family) { node ->
        val op = (strip(node) as? ExprUnary)?.op?.takeIf { it in IDEMPOTENT } ?: return@AlloySimplification null
        val outer = unary(node, op) ?: return@AlloySimplification null
        val inner = unary(outer.sub, op) ?: return@AlloySimplification null
        val keyword = AlloyOperator.of(outer)!!.keyword
        rewrite(node, slice(inner), "Repeated '$keyword'", { "Remove repeated '$keyword'" }, uses = listOf(outer))
    }

    /** `eventually always eventually p` → `always eventually p`, and `always eventually always p` → `eventually always p` */
    val absorption: AlloyRewriteRule = AlloySimplification(family) { node ->
        val e = strip(node) as? ExprUnary ?: return@AlloySimplification null
        val other = when (e.op) {
            ExprUnary.Op.EVENTUALLY -> ExprUnary.Op.ALWAYS
            ExprUnary.Op.ALWAYS -> ExprUnary.Op.EVENTUALLY
            else -> return@AlloySimplification null
        }
        val outer = unary(node, e.op) ?: return@AlloySimplification null
        val middle = unary(outer.sub, other) ?: return@AlloySimplification null
        unary(middle.sub, e.op) ?: return@AlloySimplification null
        val keyword = AlloyOperator.of(outer)!!.keyword
        rewrite(node, slice(middle), "Absorbed '$keyword'", simplifyTo("Remove absorbed '$keyword'"), uses = listOf(outer))
    }

    val ALL: List<AlloyRewriteRule> = listOf(idempotence, absorption)
}
