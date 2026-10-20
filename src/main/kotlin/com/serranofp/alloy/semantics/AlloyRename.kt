package com.serranofp.alloy.semantics

import com.intellij.lang.refactoring.RefactoringSupportProvider
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.refactoring.util.CommonRefactoringUtil
import com.serranofp.alloy.psi.AlloyFile
import com.serranofp.alloy.psi.AlloyNamedDeclaration
import com.serranofp.alloy.psi.impl.AlloyLocalDeclarationMixin

/**
 * Renames an Alloy declaration and its references, but only when every affected file type-checks: by-name
 * references in a file with errors could be wrong. Declarations of the bundled library are read-only.
 */
class AlloyRenameProcessor : RenamePsiElementProcessor() {
    override fun canProcessElement(element: PsiElement): Boolean = element is AlloyNamedDeclaration

    override fun substituteElementToRename(element: PsiElement, editor: Editor?): PsiElement? {
        val refusal = refusal(element as AlloyNamedDeclaration) ?: return element
        CommonRefactoringUtil.showErrorHint(element.project, editor, refusal, TITLE, null)
        return null
    }

    /** Why [declaration] cannot be renamed, or null if it can. */
    fun refusal(declaration: AlloyNamedDeclaration): String? {
        val file = declaration.containingFile?.virtualFile ?: return null
        if (!file.isWritable || file.fileSystem is JarFileSystem) {
            return "${declaration.name} cannot be renamed: it is declared in the read-only library module ${file.nameWithoutExtension}"
        }
        val withErrors = ProgressManager.getInstance().runProcessWithProgressSynchronously(
            ThrowableComputable<List<String>, RuntimeException> { ReadAction.computeBlocking<List<String>, RuntimeException> { filesWithErrors(declaration) } },
            "Checking the Alloy models",
            true,
            declaration.project,
        )
        if (withErrors.isEmpty()) return null
        return "$NEEDS_A_MODEL_WITHOUT_ERRORS: ${withErrors.joinToString(", ")} " + (if (withErrors.size == 1) "has errors" else "have errors")
    }

    /** The declaring file and the files with references, among those whose model does not type-check. */
    private fun filesWithErrors(declaration: AlloyNamedDeclaration): List<String> {
        val files = linkedSetOf(declaration.containingFile)
        ReferencesSearch.search(declaration, declaration.useScope).forEach { files += it.element.containingFile }
        val service = AlloyModelService.getInstance(declaration.project)
        return files.filterIsInstance<AlloyFile>()
            .filter { service.model(it) !is AlloyModelResult.Model }
            .map { it.name }
    }

    companion object {
        const val TITLE = "Rename"
        const val NEEDS_A_MODEL_WITHOUT_ERRORS = "Rename needs a model without errors"
    }
}

/** Parameters and binders are renamed in place: their uses are all in one paragraph. */
class AlloyRefactoringSupportProvider : RefactoringSupportProvider() {
    override fun isInplaceRenameAvailable(element: PsiElement, context: PsiElement?): Boolean = element is AlloyLocalDeclarationMixin
}
