package com.serranofp.alloy.simplifications

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.serranofp.alloy.psi.AlloyFile
import com.serranofp.alloy.semantics.AlloyModelResult
import com.serranofp.alloy.semantics.AlloyModelService
import edu.mit.csail.sdg.ast.Expr
import java.util.concurrent.TimeUnit

/** A rule for tests, matching with [match]. */
internal fun rule(
    family: AlloyRewriteFamily = AlloyRewriteFamily("TEST"),
    kind: AlloyRewriteKind = AlloyRewriteKind.SIMPLIFICATION,
    match: AlloyRewriteContext.(Expr) -> AlloyRewrite?,
): AlloyRewriteRule = object : AlloyRewriteRule {
    override val family = family
    override val kind = kind

    override fun match(node: Expr, context: AlloyRewriteContext): AlloyRewrite? = context.match(node)
}

/** A rewrite of [node], with its parentheses, by [replacement]. */
internal fun AlloyRewriteContext.replace(node: Expr, replacement: AlloyPiece?, vararg uses: Expr): AlloyRewrite? {
    val range = source(node)?.range ?: return null
    return AlloyRewrite(range, replacement ?: return null, "Test rewrite", uses.toList()) { "Rewrite to '$it'" }
}

/** Runs [block] on the type-checked model of the fixture's file, off the EDT and in a read action, as the inspections do. */
internal fun <T : Any> CodeInsightTestFixture.onModel(block: (AlloySourceText, AlloyModelResult.Model) -> T): T =
    onModelIfAny(block) ?: error("The model does not type-check")

/** [onModel], or null if the fixture's file does not type-check. */
internal fun <T : Any> CodeInsightTestFixture.onModelIfAny(block: (AlloySourceText, AlloyModelResult.Model) -> T): T? {
    val file = file as AlloyFile
    return ApplicationManager.getApplication()
        .executeOnPooledThread<T?> {
            ReadAction.computeBlocking<T?, Throwable> {
                val model = AlloyModelService.getInstance(project).model(file) as? AlloyModelResult.Model
                model?.let { block(AlloySourceText(file, PsiDocumentManager.getInstance(project).getDocument(file)!!), it) }
            }
        }
        .get(60, TimeUnit.SECONDS)
}

/** The rewrites that [rules] find in the fixture's file. */
internal fun CodeInsightTestFixture.rewrites(vararg rules: AlloyRewriteRule): List<AlloyRewriteResult> =
    onModel { source, model -> AlloyRewriteWalker(source, rules.toList()).walk(model.module) }

/** Applies [results] to the fixture's file. */
internal fun CodeInsightTestFixture.apply(results: List<AlloyRewriteResult>) {
    val document = PsiDocumentManager.getInstance(project).getDocument(file)!!
    WriteCommandAction.runWriteCommandAction(project) {
        for (result in results.sortedByDescending { it.range.startOffset }) {
            document.replaceString(result.range.startOffset, result.range.endOffset, result.text)
        }
        PsiDocumentManager.getInstance(project).commitDocument(document)
    }
}

/** The text of the fixture's file once the rewrites of [rules] are applied. */
internal fun CodeInsightTestFixture.rewritten(vararg rules: AlloyRewriteRule): String {
    apply(rewrites(*rules))
    return file.text
}
