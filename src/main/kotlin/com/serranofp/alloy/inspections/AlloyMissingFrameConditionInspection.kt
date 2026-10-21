package com.serranofp.alloy.inspections

import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.serranofp.alloy.inspections.AlloyCommandInspection.Companion.isUser
import com.serranofp.alloy.inspections.AlloyCommandInspection.Companion.name
import com.serranofp.alloy.inspections.AlloyTextEdits.bodyIndent
import com.serranofp.alloy.inspections.AlloyTextEdits.indentOf
import com.serranofp.alloy.psi.AlloyPredDecl
import edu.mit.csail.sdg.ast.Expr
import edu.mit.csail.sdg.ast.Sig

/**
 * An event predicate (see [AlloyFrameAnalysis]) that leaves some mutable relation of the model unconstrained in
 * the next state, in every branch of its body and of the predicates it calls.
 */
class AlloyMissingFrameConditionInspection : AlloyModelInspection() {
    override fun check(context: AlloyInspectionContext, holder: ProblemsHolder) {
        val relations = mutableRelations(context)
        if (relations.isEmpty()) return
        val analysis = AlloyFrameAnalysis()
        for (func in context.model.module.allFunc) {
            if (!func.isPred || !context.inFile(func.pos)) continue
            val decl = context.predDecl(func.pos) ?: continue
            val name = decl.nameIdentifier ?: continue
            if (!analysis.isEvent(func)) continue
            val constrained = analysis.summary(func).constrains
            // Frame conditions over the meta model, as `all v : var$ - uploaded$ | v.value = v.value'`, may be about any relation
            if (constrained.any { (it is Sig.Field && it.sig.isMeta != null) || (it is Sig && it.isMeta != null) }) continue
            val missing = relations.filterKeys { it !in constrained }
            if (missing.isEmpty()) continue
            val conditions = missing.values.map { "$it' = $it" }
            val fixes = conditions.map { AlloyFrameConditionFix(decl, listOf(it), "Add $it") } +
                AlloyFrameConditionFix(decl, conditions, "Add all missing frame conditions")
            val message = "${name.text} does not constrain the next value of ${missing.values.joinToString()}"
            context.register(holder, decl, name.textRange, message, *fixes.toTypedArray())
        }
    }

    /**
     * The `var` fields and `var sig`s of the user's modules, each as a frame condition names it: a field whose
     * name another mutable field shares is restricted to its signature, `S <: f`.
     */
    private fun mutableRelations(context: AlloyInspectionContext): Map<Expr, String> {
        val sigs = context.model.module.allReachableSigs.filter(::isUser)
        val fields = sigs.flatMap { sig -> sig.fields.filter { it.isVariable != null } }
        val ambiguous = fields.groupBy { it.label }.filterValues { it.size > 1 }.keys
        val relations = LinkedHashMap<Expr, String>()
        for (sig in sigs) if (sig.isVariable != null) relations[sig] = name(sig)
        for (field in fields) relations[field] = if (field.label in ambiguous) "${name(field.sig)} <: ${field.label}" else field.label
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
) : LocalQuickFixAndIntentionActionOnPsiElement(decl) {
    override fun getText(): String = text

    override fun getFamilyName(): String = text

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
