package com.serranofp.alloy.inspections

import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.serranofp.alloy.AlloyBundle
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
 *
 * Wrapping in `always` would also turn a conjunct about the first state only, such as `no inbox`, into an
 * invariant, so it is not offered for a fact with such a conjunct. A fact without temporal operators may well
 * be about the initial state, as `fact init { no inbox }`, so there the fix has a low priority.
 */
class AlloyFactOnlyAtStartInspection : AlloyModelInspection() {
    override fun check(context: AlloyInspectionContext, holder: ProblemsHolder) {
        val analysis = AlloyFrameAnalysis()
        for (fact in context.model.module.allFacts) {
            ProgressManager.checkCanceled()
            val body = fact.b
            val span = body.span()
            if (!context.inFile(span)) continue
            val decl = factDecl(context, context.range(span).startOffset) ?: continue
            val occurrence = firstOccurrence(context, decl, analysis, body)
            if (occurrence != null) {
                val (range, message) = occurrence
                val initial = AlloyFrameAnalysis.conjuncts(body).any { analysis.summary(it).let { c -> c.mentions.isNotEmpty() && !c.temporal } }
                val fixes = if (initial) emptyArray() else arrayOf(AlloyWrapInAlwaysFix(decl, PriorityAction.Priority.NORMAL))
                context.register(holder, decl, range, message, *fixes)
                continue
            }
            val summary = analysis.summary(body)
            if (summary.temporal || summary.mentions.isEmpty()) continue
            val message = AlloyBundle.message("inspection.fact.no.temporal")
            val fix = AlloyWrapInAlwaysFix(decl, PriorityAction.Priority.LOW)
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
        // Written `after`s with an `always`, `eventually`, `until`, `releases` or `;` inside, as in `after always P`:
        // they constrain the rest of the trace, not only one state
        val future = HashSet<Expr>()
        // Primes reported as the join they end, since `n.inbox'` is `n.(inbox')`
        val joined = HashSet<Expr>()
        // A prime on a static expression is the static-prime inspection's
        fun mutable(prime: ExprUnary): Boolean = analysis.summary(prime.sub).mentions.isNotEmpty()
        fun record(expr: Expr, kind: Kind) {
            if (depth == 0) occurrences += Occurrence(expr, kind, afters.size, afters.firstOrNull())
        }
        fun <T> nested(body: () -> T): T {
            future += afters
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
                        if (x in future) occurrences.removeIf { it.expr === x }
                    }
                }
                ExprUnary.Op.PRIME -> {
                    if (x !in joined && mutable(x)) record(x, Kind.PRIME)
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
                        if (mutable(right)) record(x, Kind.PRIME)
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
                ?.let { balanced(context.file, context.range(it), fact) }?.takeIf { fact.contains(it) && !it.isEmpty }
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
                    AlloyBundle.message("inspection.fact.transition", ordinal(transitions.minOf { it.nesting } + 1))
                } else {
                    AlloyBundle.message("inspection.fact.state", ordinal((inner.filter { it.kind == Kind.AFTER }.maxOfOrNull { it.nesting } ?: 0) + 2))
                }
                AlloyBundle.message(if (range != null) "inspection.fact.after" else "inspection.fact.after.fact", what)
            }
            first.kind == Kind.EVENT -> {
                val event = (first.expr as ExprCall).`fun`.label.removePrefix("this/")
                AlloyBundle.message("inspection.fact.event", event)
            }
            shown != null -> AlloyBundle.message("inspection.fact.prime", shown)
            else -> AlloyBundle.message("inspection.fact.prime.fact")
        }
        return (range ?: header(decl)) to message
    }

    private fun ordinal(n: Int): String = when (n) {
        1 -> AlloyBundle.message("inspection.fact.ordinal.1")
        2 -> AlloyBundle.message("inspection.fact.ordinal.2")
        3 -> AlloyBundle.message("inspection.fact.ordinal.3")
        else -> AlloyBundle.message("inspection.fact.ordinal.n", n)
    }
}

/**
 * Wraps the body of a fact in `always { … }`: on the same line for a one-line body, and otherwise on lines of
 * its own, with the body's lines indented one more level.
 */
class AlloyWrapInAlwaysFix(decl: AlloyFactDecl, private val priority: PriorityAction.Priority) :
    LocalQuickFixAndIntentionActionOnPsiElement(decl), PriorityAction {
    override fun getText(): String = AlloyBundle.message("inspection.fact.fix")

    override fun getFamilyName(): String = text

    override fun getPriority(): PriorityAction.Priority = priority

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
