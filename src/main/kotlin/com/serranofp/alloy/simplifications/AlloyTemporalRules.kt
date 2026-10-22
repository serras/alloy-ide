package com.serranofp.alloy.simplifications

import com.serranofp.alloy.AlloyBundle
import edu.mit.csail.sdg.ast.ExprUnary

/** The simplifications of "Temporal formula can be simplified". */
object AlloyTemporalRules {
    /** The operators that are idempotent: `after` and `before` are not, as `after after p` is two states later. */
    private val IDEMPOTENT = listOf(ExprUnary.Op.ALWAYS, ExprUnary.Op.EVENTUALLY, ExprUnary.Op.HISTORICALLY, ExprUnary.Op.ONCE)

    /** `always always p` → `always p`, and likewise for `eventually`, `historically` and `once` */
    val idempotence: AlloyRewriteRule = rule { node ->
        val op = (strip(node) as? ExprUnary)?.op?.takeIf { it in IDEMPOTENT } ?: return@rule null
        val outer = unary(node, op) ?: return@rule null
        val inner = unary(outer.sub, op) ?: return@rule null
        val keyword = AlloyOperator.of(outer)!!.keyword
        val fix = AlloyBundle.message("simplification.repeated.operator.fix", keyword)
        rewrite(node, slice(inner), AlloyBundle.message("simplification.repeated.operator", keyword), { fix }, uses = listOf(outer))
    }

    /** `eventually always eventually p` → `always eventually p`, and `always eventually always p` → `eventually always p` */
    val absorption: AlloyRewriteRule = rule { node ->
        val e = strip(node) as? ExprUnary ?: return@rule null
        val other = when (e.op) {
            ExprUnary.Op.EVENTUALLY -> ExprUnary.Op.ALWAYS
            ExprUnary.Op.ALWAYS -> ExprUnary.Op.EVENTUALLY
            else -> return@rule null
        }
        val outer = unary(node, e.op) ?: return@rule null
        val middle = unary(outer.sub, other) ?: return@rule null
        unary(middle.sub, e.op) ?: return@rule null
        val keyword = AlloyOperator.of(outer)!!.keyword
        val fixName = simplifyTo(AlloyBundle.message("simplification.absorbed.operator.fix", keyword))
        rewrite(node, slice(middle), AlloyBundle.message("simplification.absorbed.operator", keyword), fixName, uses = listOf(outer))
    }

    val ALL: List<AlloyRewriteRule> = listOf(idempotence, absorption)
}
