package com.serranofp.alloy.inspections

import com.intellij.openapi.progress.ProgressManager
import edu.mit.csail.sdg.ast.Expr
import edu.mit.csail.sdg.ast.ExprBinary
import edu.mit.csail.sdg.ast.ExprCall
import edu.mit.csail.sdg.ast.ExprList
import edu.mit.csail.sdg.ast.ExprUnary
import edu.mit.csail.sdg.ast.Func
import edu.mit.csail.sdg.ast.Sig
import edu.mit.csail.sdg.ast.Sig.Field
import edu.mit.csail.sdg.ast.VisitQuery

/**
 * What the type-checked body of a predicate says about the next state, through the predicates, functions
 * and macros it calls (macros are already expanded by the analyzer). Summaries are memoized per [Func].
 */
class AlloyFrameAnalysis {
    /**
     * The mutable relations (`var` fields, `var sig`s, and the meta model) a body [mentions], those it
     * [constrains] in the next state, under `'` or `after`, whether it constrains the [next] state at all,
     * whether it uses an operator about the whole [future] of the trace, and whether it uses any [temporal]
     * operator, past ones included.
     */
    data class Summary(
        val mentions: Set<Expr>,
        val constrains: Set<Expr>,
        val next: Boolean,
        val future: Boolean,
        val temporal: Boolean,
    )

    private val summaries = HashMap<Func, Summary>()
    private val visiting = ArrayList<Func>()

    /** Funcs being walked whose summary misses part of a cycle, so it is not memoized. */
    private val partial = HashSet<Func>()

    fun summary(func: Func): Summary {
        summaries[func]?.let { return it }
        val index = visiting.indexOf(func)
        if (index >= 0) {
            // A recursive call adds nothing to what [func] already says, but the funcs on the way do not see all of it
            partial += visiting.subList(index + 1, visiting.size)
            return EMPTY
        }
        visiting += func
        try {
            val summary = walk(func.body)
            if (!partial.remove(func)) summaries[func] = summary
            return summary
        } finally {
            visiting.removeLast()
        }
    }

    /** The summary of [body], such as a fact's, through the funcs it calls. It is not memoized. */
    fun summary(body: Expr): Summary = walk(body)

    /**
     * A predicate that constrains the next state, but no later ones: past operators are guards, while
     * `always`, `eventually`, `until`, `releases` and `;` describe a property or a scenario.
     */
    fun isEvent(func: Func): Boolean = func.isPred && summary(func).let { it.next && !it.future }

    private fun walk(body: Expr): Summary {
        ProgressManager.checkCanceled()
        val mentions = LinkedHashSet<Expr>()
        val constrains = LinkedHashSet<Expr>()
        var next = false
        var future = false
        var temporal = false
        // How many `'` or `after` the current expression is under
        var depth = 0
        fun mention(relation: Expr) {
            mentions += relation
            if (depth > 0) {
                constrains += relation
                next = true
            }
        }
        body.accept(object : VisitQuery<Unit>() {
            override fun visit(x: ExprUnary): Unit? {
                when (x.op) {
                    ExprUnary.Op.PRIME, ExprUnary.Op.AFTER -> {
                        temporal = true
                        if (x.op == ExprUnary.Op.AFTER) {
                            if (isSequence(x)) future = true else next = true
                        }
                        depth++
                        try {
                            return super.visit(x)
                        } finally {
                            depth--
                        }
                    }
                    ExprUnary.Op.ALWAYS, ExprUnary.Op.EVENTUALLY -> {
                        future = true
                        temporal = true
                    }
                    ExprUnary.Op.BEFORE, ExprUnary.Op.HISTORICALLY, ExprUnary.Op.ONCE -> temporal = true
                    else -> {}
                }
                return super.visit(x)
            }

            override fun visit(x: ExprBinary): Unit? {
                when (x.op) {
                    ExprBinary.Op.UNTIL, ExprBinary.Op.RELEASES -> {
                        future = true
                        temporal = true
                    }
                    ExprBinary.Op.SINCE, ExprBinary.Op.TRIGGERED -> temporal = true
                    else -> {}
                }
                return super.visit(x)
            }

            override fun visit(x: ExprCall): Unit? {
                val callee = summary(x.`fun`)
                // A call under `'` or `after` is about the next state as a whole
                if (depth > 0) callee.mentions.forEach(::mention) else constrains += callee.constrains
                mentions += callee.mentions
                next = next || callee.next
                future = future || callee.future
                temporal = temporal || callee.temporal
                return super.visit(x)
            }

            // The meta model stands for every relation, so it counts as mutable: the analyzer makes the `value` of
            // `File$shared` only as `var` as `File`, even when `shared` is `var`
            override fun visit(x: Sig): Unit? {
                if (isMutable(x)) mention(x)
                return super.visit(x)
            }

            override fun visit(x: Field): Unit? {
                if (isMutable(x)) mention(x)
                return super.visit(x)
            }
        })
        return Summary(mentions, constrains, next, future, temporal)
    }

    companion object {
        private val EMPTY = Summary(emptySet(), emptySet(), next = false, future = false, temporal = false)

        /** A `var sig`, or a signature of the meta model, such as `var$` or `File$shared`. */
        fun isMutable(sig: Sig): Boolean = sig.isVariable != null || sig.isMeta != null

        /** A `var` field, or a field of the meta model, such as `value`. */
        fun isMutable(field: Field): Boolean = field.isVariable != null || field.sig.isMeta != null

        /** The conjuncts of [body], flattened as the analyzer's `ExprList.makeAND` does. */
        fun conjuncts(body: Expr): List<Expr> = when (val x = body.deNOP()) {
            is ExprList if x.op == ExprList.Op.AND -> x.args.flatMap(::conjuncts)
            is ExprBinary if x.op == ExprBinary.Op.AND -> conjuncts(x.left) + conjuncts(x.right)
            else -> listOf(x)
        }

        /**
         * The analyzer desugars `a ; b` into `a and after b`, with the `after` at the one column of `;`; a
         * written `after` spans its keyword. `AlloyFrameAnalysisTest` pins this convention of the analyzer.
         */
        fun isSequence(x: ExprUnary): Boolean = x.pos.y == x.pos.y2 && x.pos.x == x.pos.x2
    }
}
