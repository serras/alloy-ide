package com.serranofp.alloy.inspections

import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.serranofp.alloy.inspections.AlloyCommandInspection.Companion.isUser
import com.serranofp.alloy.inspections.AlloyTextEdits.balanced
import com.serranofp.alloy.psi.AlloyMacroDecl
import com.serranofp.alloy.psi.AlloyTypes
import edu.mit.csail.sdg.ast.Expr
import edu.mit.csail.sdg.ast.ExprCall
import edu.mit.csail.sdg.ast.ExprLet
import edu.mit.csail.sdg.ast.ExprUnary
import edu.mit.csail.sdg.ast.ExprVar
import edu.mit.csail.sdg.ast.Func
import edu.mit.csail.sdg.ast.Sig
import edu.mit.csail.sdg.ast.Sig.Field
import edu.mit.csail.sdg.ast.VisitQuery

/**
 * A prime on an expression that mentions no mutable relation, directly or through the functions it calls, so it
 * is the same in every state. Quantified variables and parameters are rigid; a `let` variable is as mutable as
 * its definition. Primes in macros are left out, since a macro may prime the argument it substitutes.
 */
class AlloyStaticPrimeInspection : AlloyModelInspection() {
    override fun check(context: AlloyInspectionContext, holder: ProblemsHolder) {
        val module = context.model.module
        val analysis = AlloyFrameAnalysis()
        val reported = HashSet<TextRange>()
        val roots = buildList {
            module.allFacts.mapTo(this) { it.b to null }
            module.allAssertions.mapTo(this) { it.expr to null }
            module.allFunc.mapTo(this) { it.body to it }
            module.allSigs.filter(::isUser).flatMapTo(this) { sig -> sig.facts.map { it to null } }
        }
        for ((body, func) in roots) {
            if (!context.inFile(body.span())) continue
            val lets = HashMap<ExprVar, Expr>()
            body.accept(object : VisitQuery<Unit>() {
                override fun visit(x: ExprLet): Unit? {
                    x.expr.accept(this)
                    lets[x.`var`] = x.expr
                    try {
                        return x.sub.accept(this)
                    } finally {
                        lets.remove(x.`var`)
                    }
                }

                override fun visit(x: ExprUnary): Unit? {
                    if (x.op == ExprUnary.Op.PRIME && !isMutable(analysis, lets, x.sub)) {
                        val problem = problem(context, func, x)
                        if (problem != null && reported.add(problem.range)) {
                            context.register(holder, problem.paragraph, problem.range, problem.message, AlloyRemovePrimeFix(problem.prime))
                        }
                    }
                    return super.visit(x)
                }
            })
        }
    }

    private class Problem(val range: TextRange, val paragraph: PsiElement, val prime: PsiElement, val message: String)

    /** The problem for a written prime [x] outside macros, if it is one. */
    private fun problem(context: AlloyInspectionContext, func: Func?, x: ExprUnary): Problem? {
        val span = x.span()
        if (!context.inFile(span)) return null
        val written = context.range(span)
        if (written.isEmpty) return null
        // An analyzer-built prime has no `'` of its own
        val prime = context.file.findElementAt(written.endOffset - 1)?.takeIf { it.node.elementType == AlloyTypes.PRIME } ?: return null
        if (PsiTreeUtil.getParentOfType(prime, AlloyMacroDecl::class.java) != null) return null
        val paragraph = generateSequence(prime) { it.parent }.firstOrNull { it.parent === context.file } ?: return null
        // The operand of a prime in the paragraph comes from elsewhere when it is a macro's argument
        val operand = x.sub.span()
        if (!context.inFile(operand) || !paragraph.textRange.contains(context.range(operand))) return null
        val range = balanced(context.document, written, paragraph.textRange)
        val text = context.document.getText(range)
        val unprimed = text.removeSuffix("'")
        val message = when (val sub = x.sub.deNOP()) {
            is ExprVar if func != null && func.params().any { it === sub } ->
                "${sub.label} is a parameter, and parameters keep their value across states, so $text is the same as $unprimed. " +
                        "A macro, such as ${macro(context, func)}, primes its argument instead"

            is ExprVar -> "${sub.label} is a quantified variable, which keeps its value across states, so $text is the same as $unprimed"
            else -> {
                val statics = relations(x.sub)
                when (statics.size) {
                    0 -> "$unprimed never changes, so $text is the same as $unprimed"
                    1 -> "${statics.single()} is static, so $text is the same as $unprimed"
                    else -> "${statics.joinToString()} are static, so $text is the same as $unprimed"
                }
            }
        }
        return Problem(range, paragraph, prime, message)
    }

    /** [func] written as a macro: `let unchanged[r] { r' = r }`, with its body if it fits on one line. */
    private fun macro(context: AlloyInspectionContext, func: Func): String {
        val name = func.label.substringAfterLast('/')
        val params = func.params().joinToString(", ") { it.label }
        val block = context.predDecl(func.pos)?.blockList?.lastOrNull()?.text
        val body = block?.takeIf { '\n' !in it && it.length <= 60 } ?: "{ … }"
        return "let $name[$params] $body"
    }

    /**
     * Whether [expr] mentions a `var` field or `var sig`, directly, through calls, or through `let` variables. The
     * meta model counts as mutable, since it is about every relation.
     */
    private fun isMutable(analysis: AlloyFrameAnalysis, lets: Map<ExprVar, Expr>, expr: Expr): Boolean {
        val bound = HashMap(lets)
        return expr.accept(object : VisitQuery<Boolean>() {
            override fun visit(x: Sig): Boolean? = if (x.isVariable != null || x.isMeta != null) true else super.visit(x)

            // A field of the meta model, as `value`, stands for any relation
            override fun visit(x: Field): Boolean? = if (x.isVariable != null || x.sig.isMeta != null) true else super.visit(x)

            override fun visit(x: ExprCall): Boolean? = if (analysis.summary(x.`fun`).mentions.isNotEmpty()) true else super.visit(x)

            override fun visit(x: ExprVar): Boolean? = bound[x]?.accept(this)

            override fun visit(x: ExprLet): Boolean? {
                x.expr.accept(this)?.let { return it }
                bound[x.`var`] = x.expr
                return x.sub.accept(this)
            }
        }) == true
    }

    /** The static fields and signatures [expr] mentions, by name. */
    private fun relations(expr: Expr): List<String> {
        val names = LinkedHashSet<String>()
        expr.accept(object : VisitQuery<Unit>() {
            override fun visit(x: Sig): Unit? {
                names += x.label.removePrefix("this/")
                return super.visit(x)
            }

            override fun visit(x: Field): Unit? {
                names += x.label
                return super.visit(x)
            }
        })
        return names.toList()
    }
}

/** Deletes a `'`. */
class AlloyRemovePrimeFix(prime: PsiElement) : LocalQuickFixAndIntentionActionOnPsiElement(prime) {
    override fun getText(): String = "Remove the prime"

    override fun getFamilyName(): String = text

    override fun invoke(project: Project, file: PsiFile, editor: Editor?, startElement: PsiElement, endElement: PsiElement) {
        val documents = PsiDocumentManager.getInstance(project)
        val document = documents.getDocument(file) ?: return
        document.deleteString(startElement.textRange.startOffset, startElement.textRange.endOffset)
        documents.commitDocument(document)
    }
}
