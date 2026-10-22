package com.serranofp.alloy.semantics

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Key
import edu.mit.csail.sdg.alloy4.Pos
import edu.mit.csail.sdg.ast.Expr
import edu.mit.csail.sdg.ast.VisitQueryOnce
import edu.mit.csail.sdg.parser.CompModule

/**
 * What `CompModule.find` answers at a position, from one visit of the module's expressions instead of one per
 * lookup: resolving every reference of a file after an edit would otherwise cost the size of the model each.
 * The expressions are kept in the order `find` visits them, by file and by the "line" of `Pos.start`
 * (`y * 500 + x`, so a column past 500 counts as a later line, as it does in `Pos.contains`).
 */
internal class AlloyExprIndex(module: CompModule) {
    private val buckets = HashMap<String, HashMap<Int, MutableList<Expr>>>()

    init {
        module.visitExpressionsResilient(object : VisitQueryOnce<Any>() {
            override fun visited(expr: Expr): Boolean {
                if (super.visited(expr)) return true
                ProgressManager.checkCanceled()
                val pos = expr.pos
                if (pos != null && pos != Pos.UNKNOWN) {
                    val lines = buckets.getOrPut(pos.filename, ::HashMap)
                    for (line in line(pos.y, pos.x)..line(pos.y2, pos.x2)) lines.getOrPut(line, ::ArrayList) += expr
                }
                return false
            }
        })
    }

    /** As `CompModule.find`: the narrowest expression around [pos], preferring one that refers to something. */
    fun find(pos: Pos): Expr? {
        var width = Int.MAX_VALUE
        var found: Expr? = null
        for (expr in buckets[pos.filename]?.get(line(pos.y, pos.x)).orEmpty()) {
            if (!expr.pos.contains(pos)) continue
            val exprWidth = expr.pos.width()
            if (exprWidth <= width && (found == null || found.referenced() == null || expr.referenced() != null)) {
                width = exprWidth
                found = expr
            }
        }
        return found
    }

    private fun line(y: Int, x: Int): Int = (y * 500 + x) / 500

    companion object {
        val KEY: Key<AlloyExprIndex> = Key.create("alloy.expr.index")

        fun of(model: AlloyModelResult.Model): AlloyExprIndex = model.derived(KEY) { AlloyExprIndex(it.module) }
    }
}
