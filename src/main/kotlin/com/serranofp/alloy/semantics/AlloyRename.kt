package com.serranofp.alloy.semantics

import com.intellij.lang.refactoring.NamesValidator
import com.intellij.lang.refactoring.RefactoringSupportProvider
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.refactoring.util.CommonRefactoringUtil
import com.serranofp.alloy.AlloyBundle
import com.serranofp.alloy.lexer.AlloyLexer
import com.serranofp.alloy.lexer.AlloyTokenSets
import com.serranofp.alloy.psi.AlloyFile
import com.serranofp.alloy.psi.AlloyNamedDeclaration
import com.serranofp.alloy.psi.AlloyTypes
import com.serranofp.alloy.psi.impl.AlloyLocalDeclarationMixin

/**
 * Renames an Alloy declaration and its references, but only when every affected file type-checks: by-name
 * references in a file with errors could be wrong. Declarations of the bundled library are read-only.
 */
class AlloyRenameProcessor : RenamePsiElementProcessor() {
    override fun canProcessElement(element: PsiElement): Boolean = element is AlloyNamedDeclaration

    override fun substituteElementToRename(element: PsiElement, editor: Editor?): PsiElement? {
        val refusal = try {
            refusal(element as AlloyNamedDeclaration) ?: return element
        } catch (_: ProcessCanceledException) {
            // The user cancelled the check of the models, and so the rename
            return null
        }
        CommonRefactoringUtil.showErrorHint(element.project, editor, refusal, TITLE, null)
        return null
    }

    /** Why [declaration] cannot be renamed, or null if it can. */
    fun refusal(declaration: AlloyNamedDeclaration): String? {
        val file = declaration.containingFile?.virtualFile ?: return null
        if (file.fileSystem is JarFileSystem) {
            return AlloyBundle.message("rename.read.only.library", declaration.name.orEmpty(), file.nameWithoutExtension)
        }
        if (!file.isWritable) return AlloyBundle.message("rename.read.only.file", declaration.name.orEmpty(), file.name)
        val withErrors = ProgressManager.getInstance().runProcessWithProgressSynchronously(
            ThrowableComputable<List<String>, RuntimeException> { ReadAction.computeBlocking<List<String>, RuntimeException> { filesWithErrors(declaration) } },
            AlloyBundle.message("rename.checking.models"),
            true,
            declaration.project,
        )
        if (withErrors.isEmpty()) return null
        return AlloyBundle.message("rename.files.with.errors", NEEDS_A_MODEL_WITHOUT_ERRORS, withErrors.joinToString(", "), withErrors.size)
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
        val TITLE: String get() = AlloyBundle.message("rename.title")
        val NEEDS_A_MODEL_WITHOUT_ERRORS: String get() = AlloyBundle.message("rename.needs.model.without.errors")
    }
}

/** Parameters and binders are renamed in place: their uses are all in one paragraph. */
class AlloyRefactoringSupportProvider : RefactoringSupportProvider() {
    override fun isInplaceRenameAvailable(element: PsiElement, context: PsiElement?): Boolean = element is AlloyLocalDeclarationMixin
}

/**
 * What a rename accepts: one Alloy identifier (which can contain `"` after its first letter, but not `'`) that is
 * not a keyword. The platform's default accepts Java identifiers, `sig` among them, which no Alloy PSI can hold.
 */
class AlloyNamesValidator : NamesValidator {
    override fun isKeyword(name: String, project: Project?): Boolean = token(name) in KEYWORDS

    override fun isIdentifier(name: String, project: Project?): Boolean = token(name) == AlloyTypes.IDENTIFIER

    /** The type of the token that [name] is, or null if it is not exactly one token. */
    private fun token(name: String) = AlloyLexer().run {
        start(name)
        tokenType.takeIf { tokenEnd == name.length }
    }

    private val KEYWORDS = setOf(*AlloyTokenSets.KEYWORDS.types, *AlloyTokenSets.TEMPORAL_OPERATORS.types)
}
