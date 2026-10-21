package com.serranofp.alloy.simplifications

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.serranofp.alloy.inspections.AlloyInspectionContext
import com.serranofp.alloy.inspections.AlloyModelInspection

/**
 * Reports the formulas and expressions that the simplifications in [rules] rewrite, each with a quick-fix that
 * applies the rewrite. Only the outermost of nested simplifications is reported; the inner ones are once it
 * is fixed.
 */
abstract class AlloySimplificationInspection(private val rules: List<AlloyRewriteRule>) : AlloyModelInspection() {
    override fun check(context: AlloyInspectionContext, holder: ProblemsHolder) {
        val source = AlloySourceText(context.file, context.document)
        for (result in AlloyRewriteWalker(source, rules).walk(context.model.module)) {
            val original = context.document.getText(result.range)
            // On the paragraph, where the suppression comments are looked for
            context.register(holder, result.paragraph, result.range, result.message, AlloyRewriteFix(original, result.text, result.fixName))
        }
    }
}

class AlloySimplifiableBooleanInspection : AlloySimplificationInspection(AlloyBooleanRules.ALL)

class AlloySimplifiableCardinalityInspection : AlloySimplificationInspection(AlloyCardinalityRules.ALL)

class AlloySimplifiableTemporalInspection : AlloySimplificationInspection(AlloyTemporalRules.ALL)

class AlloySimplifiableQuantifierInspection : AlloySimplificationInspection(AlloyQuantifierRules.ALL)

class AlloySimplifiableRelationalInspection : AlloySimplificationInspection(AlloyRelationalRules.ALL)

/**
 * Replaces the problem's text, which read [original] when it was reported, by [replacement]. If the file
 * was edited since, it does nothing: the next highlighting pass reports afresh.
 */
class AlloyRewriteFix(private val original: String, private val replacement: String, private val name: String) : LocalQuickFix {
    override fun getName(): String = name

    override fun getFamilyName(): String = "Simplify"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val element = descriptor.psiElement ?: return
        val range = descriptor.textRangeInElement?.shiftRight(element.textRange.startOffset) ?: return
        val documents = PsiDocumentManager.getInstance(project)
        val document = documents.getDocument(element.containingFile) ?: return
        if (range.endOffset > document.textLength || document.getText(range) != original) return
        document.replaceString(range.startOffset, range.endOffset, replacement)
        documents.commitDocument(document)
    }
}
