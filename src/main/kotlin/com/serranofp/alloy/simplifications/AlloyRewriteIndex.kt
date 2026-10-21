package com.serranofp.alloy.simplifications

import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.serranofp.alloy.psi.AlloyFile
import com.serranofp.alloy.semantics.AlloyModelResult
import edu.mit.csail.sdg.ast.Expr
import edu.mit.csail.sdg.parser.CompModule
import java.util.Collections
import java.util.WeakHashMap

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
        private val INDEX = AlloyRewriteFamily("INDEX")

        /** By the analyzer's module, which is new for every version of the file's text. */
        private val indexes = Collections.synchronizedMap(WeakHashMap<CompModule, AlloyRewriteIndex>())

        /** The index of [model], the model of [file], built on first use. */
        fun of(model: AlloyModelResult.Model, file: AlloyFile, document: Document): AlloyRewriteIndex =
            indexes.getOrPut(model.module) { build(model, AlloySourceText(file, document)) }

        private fun build(model: AlloyModelResult.Model, source: AlloySourceText): AlloyRewriteIndex {
            val entries = ArrayList<Entry>()
            val record = object : AlloyRewriteRule {
                override val family = INDEX
                override val kind = AlloyRewriteKind.EQUIVALENCE

                override fun match(node: Expr, context: AlloyRewriteContext): AlloyRewrite? {
                    val range = context.source(node)?.range
                    if (range != null && context.written(node)) entries += Entry(node, range, context)
                    return null
                }
            }
            AlloyRewriteWalker(source, listOf(record)).walk(model.module)
            return AlloyRewriteIndex(entries)
        }
    }
}
