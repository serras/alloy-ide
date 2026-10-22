package com.serranofp.alloy.simplifications

import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.serranofp.alloy.psi.AlloyFile
import com.serranofp.alloy.semantics.AlloyModelResult
import edu.mit.csail.sdg.ast.Expr

/**
 * The written nodes of a type-checked model, by their range in the file, each with what a rule sees of it,
 * so that an intention finds the nodes around the caret without walking the model on every caret move.
 */
class AlloyRewriteIndex private constructor(private val entries: List<Entry>) {
    /** A written [node], with the parentheses around it in [range], and the [context] a rule matches it in. */
    class Entry(val node: Expr, val range: TextRange, val context: AlloyRewriteContext)

    /** The nodes whose range contains [offset], or ends at it, innermost first. */
    fun around(offset: Int): List<Entry> =
        entries.withIndex()
            .filter { (_, entry) -> entry.range.startOffset <= offset && offset <= entry.range.endOffset }
            // Of two nodes with the same range, the deeper one comes later in the walk
            .sortedWith(compareBy({ it.value.range.length }, { -it.index }))
            .map { it.value }

    companion object {
        private val KEY = Key.create<AlloyRewriteIndex>("alloy.simplifications.index")

        /**
         * The index of [model], the model of [file], built on first use and kept with the model, which is new
         * for every version of the file's text.
         */
        fun of(model: AlloyModelResult.Model, file: AlloyFile, document: Document): AlloyRewriteIndex =
            model.derived(KEY) { build(it, AlloySourceText.of(it, file, document)) }

        private fun build(model: AlloyModelResult.Model, source: AlloySourceText): AlloyRewriteIndex {
            val entries = ArrayList<Entry>()
            val record = rule { node ->
                val range = source(node)?.range
                if (range != null && written(node)) entries += Entry(node, range, this)
                null
            }
            AlloyRewriteWalker(source, listOf(record)).walk(model.module)
            return AlloyRewriteIndex(entries)
        }
    }
}
