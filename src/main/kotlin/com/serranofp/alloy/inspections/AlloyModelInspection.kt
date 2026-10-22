package com.serranofp.alloy.inspections

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import com.serranofp.alloy.psi.AlloyCommandDecl
import com.serranofp.alloy.psi.AlloyFile
import com.serranofp.alloy.psi.AlloyPredDecl
import com.serranofp.alloy.semantics.AlloyModelResult
import com.serranofp.alloy.semantics.AlloyModelService
import com.serranofp.alloy.semantics.AlloyPositions
import com.serranofp.alloy.viewer.isAlloyEvaluatorFragment
import edu.mit.csail.sdg.alloy4.Pos

/**
 * An inspection of the analyzer's model of a file (see [AlloyModelService]). It reports nothing unless the
 * model parses and type-checks; then [check] looks at the analyzer's objects declared in the file, and places
 * its problems in the PSI through the [AlloyInspectionContext].
 */
abstract class AlloyModelInspection : LocalInspectionTool() {
    // The evaluator's fragment is an expression, not a module
    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        val alloyFile = (file as? AlloyFile)?.takeIf { it.virtualFile != null && !it.isAlloyEvaluatorFragment } ?: return null
        val model = AlloyModelService.getInstance(file.project).model(alloyFile) as? AlloyModelResult.Model ?: return null
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return null
        val holder = ProblemsHolder(manager, file, isOnTheFly)
        check(AlloyInspectionContext(alloyFile, model, document), holder)
        return holder.resultsArray
    }

    protected abstract fun check(context: AlloyInspectionContext, holder: ProblemsHolder)
}

/** The type-checked [model] of [file], and how the analyzer's positions in it map to its PSI. */
class AlloyInspectionContext(val file: AlloyFile, val model: AlloyModelResult.Model, val document: Document) {
    /** The analyzer's name for [file]. */
    val filename: String = AlloyPositions.filename(file.virtualFile)

    /** Whether [pos] is in [file], and not in a module it opens. */
    fun inFile(pos: Pos?): Boolean = pos != null && pos.filename == filename

    fun range(pos: Pos): TextRange = AlloyPositions.textRange(pos, document)

    /** The command of [file] that contains [pos], such as a `Command.pos`, which starts at its `run` or `check`. */
    fun commandDecl(pos: Pos): AlloyCommandDecl? = declAt(pos, AlloyCommandDecl::class.java)

    /** The predicate of [file] that contains [pos], such as a `Func.pos`, which starts at its `pred`. */
    fun predDecl(pos: Pos): AlloyPredDecl? = declAt(pos, AlloyPredDecl::class.java)

    private fun <T : PsiElement> declAt(pos: Pos, type: Class<T>): T? {
        if (!inFile(pos)) return null
        return PsiTreeUtil.getParentOfType(file.findElementAt(range(pos).startOffset), type, false)
    }

    /** Which command of the `=>` chain [decl] starts at [commandPos], counting from 0. */
    fun part(decl: AlloyCommandDecl, commandPos: Pos): Int = AlloyScopeText.partAt(decl, range(commandPos).startOffset)

    /**
     * The tokens of [decl] that a scope item's [pos] spans, such as `exactly 2 A` for a `CommandScope.pos`.
     * Null if it spans none of them.
     */
    fun scopeItemRange(decl: AlloyCommandDecl, pos: Pos): TextRange? {
        if (!inFile(pos)) return null
        val target = range(pos)
        // Leaf tokens too, which `children` leaves out
        val tokens = generateSequence(decl.firstChild) { it.nextSibling }
            .filter { it !is PsiWhiteSpace && it !is PsiComment && it.textRange.intersectsStrict(target) }
            .toList()
        if (tokens.isEmpty()) return null
        return TextRange(tokens.first().textRange.startOffset, tokens.last().textRange.endOffset)
    }

    /** Registers a problem on [range] of [file], which must lie inside [element]. */
    fun register(
        holder: ProblemsHolder,
        element: PsiElement,
        range: TextRange,
        message: String,
        vararg fixes: LocalQuickFix,
        type: ProblemHighlightType = ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
    ) {
        holder.registerProblem(element, message, type, range.shiftLeft(element.textRange.startOffset), *fixes)
    }
}
