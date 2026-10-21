package com.serranofp.alloy.inspections

import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.serranofp.alloy.inspections.AlloyTextEdits.balanced
import com.serranofp.alloy.inspections.AlloyTextEdits.bodyIndent
import com.serranofp.alloy.inspections.AlloyTextEdits.indentOf
import com.serranofp.alloy.psi.AlloyFactDecl
import edu.mit.csail.sdg.ast.Expr
import edu.mit.csail.sdg.ast.ExprBinary
import edu.mit.csail.sdg.ast.ExprCall
import edu.mit.csail.sdg.ast.ExprUnary
import edu.mit.csail.sdg.ast.VisitQuery

/**
 * A fact over mutable state that, without `always`, constrains only the start of the trace: the transition out
 * of the first state, or only the first state when it uses no temporal operator at all. Signature facts are
 * left out, since the analyzer puts those over mutable state under `always`.
 */
class AlloyFactOnlyAtStartInspection : AlloyModelInspection() {
    override fun check(context: AlloyInspectionContext, holder: ProblemsHolder) {
        val analysis = AlloyFrameAnalysis()
        for (fact in context.model.module.allFacts) {
            val body = fact.b
            val span = body.span()
            if (!context.inFile(span)) continue
            val decl = factDecl(context, context.range(span).startOffset) ?: continue
            val fix = AlloyWrapInAlwaysFix(decl)
            val occurrence = firstOccurrence(context, decl, analysis, body)
            if (occurrence != null) {
                val (range, message) = occurrence
                context.register(holder, decl, range, message, fix)
                continue
            }
            val summary = analysis.summary(body)
            if (summary.temporal || summary.mentions.isEmpty()) continue
            val message = "This fact constrains only the first state, because it uses no temporal operator"
            context.register(holder, decl, header(decl), message, fix, type = ProblemHighlightType.INFORMATION)
        }
    }

    private fun factDecl(context: AlloyInspectionContext, offset: Int): AlloyFactDecl? =
        generateSequence(context.file.findElementAt(offset)) { it.parent }.filterIsInstance<AlloyFactDecl>().firstOrNull()

    /** The `fact` keyword and the fact's name, if it has one. */
    private fun header(decl: AlloyFactDecl): TextRange {
        val end = (decl.qualifiedName ?: decl.stringLiteral ?: decl.firstChild).textRange.endOffset
        return TextRange(decl.textRange.startOffset, end)
    }

    private enum class Kind { PRIME, AFTER, EVENT }

    /**
     * Something that constrains the next state outside every `always`, `eventually`, `until` and `releases`,
     * under [nesting] written `after`s, the [outermost] of which is the one whose transition it constrains.
     */
    private class Occurrence(val expr: Expr, val kind: Kind, val nesting: Int, val outermost: Expr?)

    /** Where to report the first occurrence in [body] of [decl], if any, and the message. */
    private fun firstOccurrence(
        context: AlloyInspectionContext,
        decl: AlloyFactDecl,
        analysis: AlloyFrameAnalysis,
        body: Expr,
    ): Pair<TextRange, String>? {
        val occurrences = ArrayList<Occurrence>()
        // How many `always`, `eventually`, `until`, `releases` or `;` the current expression is under
        var depth = 0
        val afters = ArrayList<Expr>()
        // Primes reported as the join they end, since `n.inbox'` is `n.(inbox')`
        val joined = HashSet<Expr>()
        fun record(expr: Expr, kind: Kind) {
            if (depth == 0) occurrences += Occurrence(expr, kind, afters.size, afters.firstOrNull())
        }
        fun <T> nested(body: () -> T): T {
            depth++
            try {
                return body()
            } finally {
                depth--
            }
        }
        body.accept(object : VisitQuery<Unit>() {
            override fun visit(x: ExprUnary): Unit? = when (x.op) {
                ExprUnary.Op.ALWAYS, ExprUnary.Op.EVENTUALLY -> nested { super.visit(x) }
                // A `;` describes a scenario
                ExprUnary.Op.AFTER -> if (AlloyFrameAnalysis.isSequence(x)) nested { super.visit(x) } else {
                    record(x, Kind.AFTER)
                    afters += x
                    try {
                        super.visit(x)
                    } finally {
                        afters.removeLast()
                    }
                }
                ExprUnary.Op.PRIME -> {
                    if (x !in joined) record(x, Kind.PRIME)
                    super.visit(x)
                }
                else -> super.visit(x)
            }

            override fun visit(x: ExprBinary): Unit? = when (x.op) {
                ExprBinary.Op.UNTIL, ExprBinary.Op.RELEASES -> nested { super.visit(x) }
                ExprBinary.Op.JOIN -> {
                    val right = x.right.deNOP()
                    if (right is ExprUnary && right.op == ExprUnary.Op.PRIME) {
                        joined += right
                        record(x, Kind.PRIME)
                    }
                    super.visit(x)
                }
                else -> super.visit(x)
            }

            override fun visit(x: ExprCall): Unit? {
                if (analysis.isEvent(x.`fun`)) record(x, Kind.EVENT)
                return super.visit(x)
            }
        })
        if (occurrences.isEmpty()) return null
        val fact = decl.textRange
        // A written `after` is reported on its keyword
        fun range(expr: Expr): TextRange? =
            (if (expr is ExprUnary && expr.op == ExprUnary.Op.AFTER) expr.pos else expr.span()).takeIf(context::inFile)
                ?.let { balanced(context.document, context.range(it), fact) }?.takeIf { fact.contains(it) && !it.isEmpty }
        // Occurrences from an expanded macro have no place in the fact, and come last
        val first = occurrences.minBy { range(it.expr)?.startOffset ?: Int.MAX_VALUE }
        val reported = first.outermost ?: first.expr
        val range = range(reported)
        val shown = range?.let { context.document.getText(it) }
        val message = when {
            first.outermost != null || first.kind == Kind.AFTER -> {
                // What the outermost `after` constrains: the first transition or state under it
                val inner = occurrences.filter { it.outermost === reported }
                val transitions = inner.filter { it.kind != Kind.AFTER }
                val what = if (transitions.isNotEmpty()) {
                    "the ${ordinal(transitions.minOf { it.nesting } + 1)} transition"
                } else {
                    "the ${ordinal((inner.filter { it.kind == Kind.AFTER }.maxOfOrNull { it.nesting } ?: 0) + 2)} state"
                }
                "${if (range != null) "This after" else "This fact"} constrains only $what, because the fact is not under always"
            }
            first.kind == Kind.EVENT -> {
                val event = (first.expr as ExprCall).`fun`.label.removePrefix("this/")
                "The event $event is constrained to happen only in the first transition, because the fact is not under always"
            }
            shown != null -> "$shown constrains only the transition out of the first state, because the fact is not under always"
            else -> "This fact constrains only the transition out of the first state, because it is not under always"
        }
        return (range ?: header(decl)) to message
    }

    private fun ordinal(n: Int): String = when (n) {
        1 -> "first"
        2 -> "second"
        3 -> "third"
        else -> "${n}th"
    }
}

/**
 * Wraps the body of a fact in `always { … }`: on the same line for a one-line body, and otherwise on lines of
 * its own, with the body's lines indented one more level.
 */
class AlloyWrapInAlwaysFix(decl: AlloyFactDecl) : LocalQuickFixAndIntentionActionOnPsiElement(decl) {
    override fun getText(): String = "Wrap in always"

    override fun getFamilyName(): String = text

    override fun invoke(project: Project, file: PsiFile, editor: Editor?, startElement: PsiElement, endElement: PsiElement) {
        val decl = startElement as? AlloyFactDecl ?: return
        val block = decl.block ?: return
        val documents = PsiDocumentManager.getInstance(project)
        val document = documents.getDocument(file) ?: return
        val open = block.textRange.startOffset
        val close = block.textRange.endOffset - 1
        val inner = document.getText(TextRange(open + 1, close))
        if ('\n' !in inner) {
            document.replaceString(open, close + 1, "{ always { ${inner.trim()} } }")
        } else {
            val declIndent = indentOf(document, document.getLineNumber(decl.textRange.startOffset))
            val indent = bodyIndent(document, open, close) ?: "$declIndent  "
            val unit = indent.removePrefix(declIndent).takeIf { indent.startsWith(declIndent) && it.isNotEmpty() } ?: "  "
            val lines = inner.split('\n')
            // Text after `{` or before `}` goes on a line of its own
            val body = listOfNotNull(
                lines.first().takeIf { it.isNotBlank() }?.let { indent + it.trim() },
                *lines.subList(1, lines.size - 1).toTypedArray(),
                lines.last().takeIf { it.isNotBlank() }?.let { indent + it.trim() },
            )
            val closeIndent = lines.last().takeIf { it.isBlank() } ?: declIndent
            val wrapped = body.joinToString("\n") { if (it.isBlank()) it else unit + it }
            document.replaceString(open, close + 1, "{\n${indent}always {\n$wrapped\n$indent}\n$closeIndent}")
        }
        documents.commitDocument(document)
    }
}
