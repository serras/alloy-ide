package com.serranofp.alloy.simplifications

import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiFileRange
import com.serranofp.alloy.AlloyBundle
import com.serranofp.alloy.AlloyFileType
import com.serranofp.alloy.inspections.AlloyInspectionContext
import com.serranofp.alloy.inspections.AlloyModelInspection

/**
 * Reports the formulas and expressions that the simplifications in [rules] rewrite, each with a quick-fix that
 * applies the rewrite. Only the outermost of nested simplifications is reported; the inner ones are once it
 * is fixed.
 */
abstract class AlloySimplificationInspection(private val rules: List<AlloyRewriteRule>) : AlloyModelInspection() {
    override fun check(context: AlloyInspectionContext, holder: ProblemsHolder) {
        val source = AlloySourceText.of(context.model, context.file, context.document)
        val pointers = SmartPointerManager.getInstance(context.file.project)
        for (result in AlloyRewriteWalker(source, rules).walk(context.model.module)) {
            val original = context.document.getText(result.range)
            val range = pointers.createSmartPsiFileRangePointer(context.file, result.range)
            // On the paragraph, where the suppression comments are looked for
            context.register(holder, result.paragraph, result.range, result.message, AlloyRewriteFix(range, original, result.text, result.fixName))
        }
    }
}

class AlloySimplifiableBooleanInspection : AlloySimplificationInspection(AlloyBooleanRules.ALL)

class AlloySimplifiableCardinalityInspection : AlloySimplificationInspection(AlloyCardinalityRules.ALL)

class AlloySimplifiableTemporalInspection : AlloySimplificationInspection(AlloyTemporalRules.ALL)

class AlloySimplifiableQuantifierInspection : AlloySimplificationInspection(AlloyQuantifierRules.ALL)

class AlloySimplifiableRelationalInspection : AlloySimplificationInspection(AlloyRelationalRules.ALL)

/**
 * Replaces the problem's text, [range], which read [original] when it was reported, by [replacement]. The
 * range follows the edits made since, such as the other fixes of a "Fix all" in the same paragraph, which
 * the problem's range, relative to the paragraph, does not; if its text was edited, the fix does nothing,
 * and the next highlighting pass reports afresh.
 */
class AlloyRewriteFix(
    private val range: SmartPsiFileRange,
    private val original: String,
    private val replacement: String,
    private val name: String,
) : LocalQuickFix {
    override fun getName(): String = name

    override fun getFamilyName(): String = AlloyBundle.message("simplification.fix.family")

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val file = range.containingFile ?: return
        val range = range.range?.let(TextRange::create) ?: return
        val documents = PsiDocumentManager.getInstance(project)
        val document = documents.getDocument(file) ?: return
        if (range.endOffset > document.textLength || document.getText(range) != original) return
        document.replaceString(range.startOffset, range.endOffset, replacement)
        documents.commitDocument(document)
    }

    // The range is in the original file, not in the preview's copy
    override fun generatePreview(project: Project, previewDescriptor: ProblemDescriptor): IntentionPreviewInfo =
        IntentionPreviewInfo.CustomDiff(AlloyFileType, original, replacement)
}
