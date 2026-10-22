package com.serranofp.alloy.inspections

import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.serranofp.alloy.AlloyBundle
import com.serranofp.alloy.inspections.AlloyCommandInspection.Companion.isUser
import com.serranofp.alloy.inspections.AlloyCommandInspection.Companion.name
import com.serranofp.alloy.inspections.AlloyTextEdits.bodyIndent
import com.serranofp.alloy.inspections.AlloyTextEdits.indentOf
import com.serranofp.alloy.psi.AlloyPredDecl
import edu.mit.csail.sdg.ast.Expr
import edu.mit.csail.sdg.ast.ExprCall
import edu.mit.csail.sdg.ast.Func
import edu.mit.csail.sdg.ast.Sig
import edu.mit.csail.sdg.ast.VisitQuery

/**
 * An event predicate (see [AlloyFrameAnalysis]) that leaves some mutable relation of the model unconstrained in
 * the next state, in every branch of its body and of the predicates it calls. An event that only other events
 * call, such as `pred moveMsg[m] { inbox' = inbox - m }` called from `send`, is a part of them: they frame it.
 */
class AlloyMissingFrameConditionInspection : AlloyModelInspection() {
    override fun check(context: AlloyInspectionContext, holder: ProblemsHolder) {
        val relations = mutableRelations(context)
        if (relations.isEmpty()) return
        val analysis = AlloyFrameAnalysis()
        val callers = callers(context)
        for (func in context.model.module.allFunc) {
            ProgressManager.checkCanceled()
            if (!func.isPred || !context.inFile(func.pos)) continue
            val decl = context.predDecl(func.pos) ?: continue
            val name = decl.nameIdentifier ?: continue
            if (!analysis.isEvent(func)) continue
            val calledBy = callers[func].orEmpty()
            if (calledBy.isNotEmpty() && calledBy.all { it != null && it !== func && analysis.isEvent(it) }) continue
            val constrained = analysis.summary(func).constrains
            // Frame conditions over the meta model, as `all v : var$ - File$shared | v.value = v.value'`, may be about any relation
            if (constrained.any { (it is Sig.Field && it.sig.isMeta != null) || (it is Sig && it.isMeta != null) }) continue
            val missing = relations.filterKeys { it !in constrained }
            if (missing.isEmpty()) continue
            val conditions = missing.values.map { "$it' = $it" }
            val family = AlloyBundle.message("inspection.frame.fix.family")
            val fixes = conditions.map { AlloyFrameConditionFix(decl, listOf(it), AlloyBundle.message("inspection.frame.fix", it), family) } +
                AlloyFrameConditionFix(decl, conditions, AlloyBundle.message("inspection.frame.fix.all"), family)
            val message = AlloyBundle.message("inspection.frame.message", name.text, missing.values.joinToString())
            context.register(holder, decl, name.textRange, message, *fixes.toTypedArray())
        }
    }

    /**
     * The predicates and functions that call each func in the bodies of the model's funcs; null stands for a
     * fact, an assertion or a signature fact.
     */
    private fun callers(context: AlloyInspectionContext): Map<Func, Set<Func?>> {
        val module = context.model.module
        val roots = buildList {
            module.allFunc.mapTo(this) { it.body to it }
            module.allFacts.mapTo(this) { it.b to null }
            module.allAssertions.mapTo(this) { it.expr to null }
            module.allReachableSigs.filter(::isUser).flatMapTo(this) { sig -> sig.facts.map { it to null } }
        }
        val callers = HashMap<Func, MutableSet<Func?>>()
        for ((body, owner) in roots) {
            ProgressManager.checkCanceled()
            body.accept(object : VisitQuery<Unit>() {
                override fun visit(x: ExprCall): Unit? {
                    callers.getOrPut(x.`fun`) { HashSet() } += owner
                    return super.visit(x)
                }
            })
        }
        return callers
    }

    /**
     * The `var` fields and `var sig`s of the user's modules, each as a frame condition names it: a field whose
     * name another field or a signature shares, mutable or not, is restricted to its signature, `S <: f`.
     */
    private fun mutableRelations(context: AlloyInspectionContext): Map<Expr, String> {
        val sigs = context.model.module.allReachableSigs.filter(::isUser)
        val names = sigs.flatMap { sig -> sig.fields.map { it.label } } + sigs.map(::name)
        val ambiguous = names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        val relations = LinkedHashMap<Expr, String>()
        for (sig in sigs) if (sig.isVariable != null) relations[sig] = name(sig)
        for (sig in sigs) for (field in sig.fields) {
            if (field.isVariable == null) continue
            relations[field] = if (field.label in ambiguous) "${name(field.sig)} <: ${field.label}" else field.label
        }
        return relations
    }
}

/**
 * Adds [conditions] at the end of the body of a predicate, one per line, with the indentation of the body's
 * last line.
 */
class AlloyFrameConditionFix(
    decl: AlloyPredDecl,
    private val conditions: List<String>,
    private val text: String,
    private val family: String,
) : LocalQuickFixAndIntentionActionOnPsiElement(decl) {
    override fun getText(): String = text

    override fun getFamilyName(): String = family

    override fun invoke(project: Project, file: PsiFile, editor: Editor?, startElement: PsiElement, endElement: PsiElement) {
        val body = (startElement as? AlloyPredDecl)?.blockList?.lastOrNull() ?: return
        val documents = PsiDocumentManager.getInstance(project)
        val document = documents.getDocument(file) ?: return
        val open = body.textRange.startOffset
        val close = body.textRange.endOffset - 1
        val closeLine = document.getLineNumber(close)
        val beforeClose = document.getText(TextRange(document.getLineStartOffset(closeLine), close))
        val indent = bodyIndent(document, open, close) ?: (indentOf(document, document.getLineNumber(startElement.textRange.startOffset)) + "  ")
        if (beforeClose.isBlank() && document.getLineNumber(open) < closeLine) {
            // `}` on a line of its own: the conditions go on the lines before it
            document.insertString(document.getLineStartOffset(closeLine), conditions.joinToString("") { "$indent$it\n" })
        } else {
            val closeIndent = indentOf(document, closeLine)
            val trimmed = close - (beforeClose.length - beforeClose.trimEnd().length)
            document.replaceString(trimmed, close, conditions.joinToString("") { "\n$indent$it" } + "\n$closeIndent")
        }
        documents.commitDocument(document)
    }
}
