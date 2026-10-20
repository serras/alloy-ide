package com.serranofp.alloy.editor

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.lookup.AutoCompletionPolicy
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.patterns.PlatformPatterns.psiElement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.JBColor
import com.intellij.util.ProcessingContext
import com.serranofp.alloy.AlloyFileType
import com.serranofp.alloy.AlloyLanguage
import com.serranofp.alloy.lexer.AlloyTokenSets
import com.serranofp.alloy.psi.AlloyAssertDecl
import com.serranofp.alloy.psi.AlloyCommandDecl
import com.serranofp.alloy.psi.AlloyFile
import com.serranofp.alloy.psi.AlloyFunDecl
import com.serranofp.alloy.psi.AlloyModuleDecl
import com.serranofp.alloy.psi.AlloyNamedDeclaration
import com.serranofp.alloy.psi.AlloyOpenDecl
import com.serranofp.alloy.psi.AlloyPredDecl
import com.serranofp.alloy.psi.AlloyQualifiedName
import com.serranofp.alloy.psi.AlloySigDecl
import com.serranofp.alloy.psi.AlloySigName
import com.serranofp.alloy.psi.AlloyTypes
import com.serranofp.alloy.semantics.AlloyModelService
import com.serranofp.alloy.semantics.AlloyPositions
import com.serranofp.alloy.semantics.AlloyReference
import com.serranofp.alloy.semantics.AlloyScopes

/** What completion offers at a position, decided from the PSI around it. */
sealed interface AlloyCompletionContext {
    /** A comment or a string. */
    data object None : AlloyCompletionContext

    /** Anywhere else that is not a reference, such as a new declaration's name or between paragraphs. */
    data object NotAReference : AlloyCompletionContext

    /** The path of an `open` declaration. */
    data object ModulePath : AlloyCompletionContext

    /** A name after `ord/`, `util/ordering/` or `this/`. */
    data class Qualified(val qualifier: String) : AlloyCompletionContext

    /** The target of `run` (predicates and functions) or `check` (assertions). */
    data class CommandTarget(val check: Boolean) : AlloyCompletionContext

    /** A parent after `extends` or `in` in a signature header. */
    data object SignatureParent : AlloyCompletionContext

    /** Any other reference. */
    data object Names : AlloyCompletionContext

    companion object {
        fun of(position: PsiElement): AlloyCompletionContext {
            val tokenType = position.node.elementType
            if (AlloyTokenSets.COMMENTS.contains(tokenType) || AlloyTokenSets.STRINGS.contains(tokenType)) return None
            val name = position.parent
            if (name is AlloyQualifiedName && (name.parent as? AlloyOpenDecl)?.qualifiedNameList?.firstOrNull() === name) return ModulePath
            if (PsiTreeUtil.skipWhitespacesAndCommentsBackward(position)?.node?.elementType == AlloyTypes.SLASH) {
                val qualifier = name.text.substring(0, position.startOffsetInParent).removeSuffix("/")
                return Qualified(qualifier)
            }
            if (name?.reference !is AlloyReference) return NotAReference
            if (name is AlloyQualifiedName) {
                when (name.parent) {
                    is AlloyCommandDecl -> when (PsiTreeUtil.skipWhitespacesAndCommentsBackward(name)?.node?.elementType) {
                        AlloyTypes.RUN -> return CommandTarget(check = false)
                        AlloyTypes.CHECK -> return CommandTarget(check = true)
                    }
                    is AlloySigDecl -> return SignatureParent
                }
            }
            return Names
        }
    }
}

/**
 * Completes the names visible at the caret, under the scope rules of name-based resolution ([AlloyScopes]),
 * so it works whether or not the model type-checks.
 */
class AlloyCompletionContributor : CompletionContributor() {
    init {
        extend(CompletionType.BASIC, psiElement().withLanguage(AlloyLanguage), NameProvider)
    }

    private object NameProvider : CompletionProvider<CompletionParameters>() {
        override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
            val position = parameters.position
            val accepts: (AlloyNamedDeclaration) -> Boolean
            val qualifier: String?
            when (val completion = AlloyCompletionContext.of(position)) {
                AlloyCompletionContext.None, AlloyCompletionContext.NotAReference -> return
                AlloyCompletionContext.ModulePath -> return ModulePaths.add(parameters, result)
                is AlloyCompletionContext.Qualified -> {
                    if (position.parent?.reference !is AlloyReference) return
                    accepts = { true }
                    qualifier = completion.qualifier
                }
                is AlloyCompletionContext.CommandTarget -> {
                    accepts = if (completion.check) { it -> it is AlloyAssertDecl } else { it -> it is AlloyPredDecl || it is AlloyFunDecl }
                    qualifier = null
                }
                AlloyCompletionContext.SignatureParent -> {
                    accepts = { it is AlloySigName }
                    qualifier = null
                }
                AlloyCompletionContext.Names -> {
                    accepts = { true }
                    qualifier = null
                }
            }
            // Only the identifier at the caret is replaced, after any qualifier; Alloy names can contain `'`
            val prefix = position.text.substring(0, parameters.offset - position.textRange.startOffset)
            val names = result.withPrefixMatcher(prefix)
            for (visible in AlloyScopes.visible(position, qualifier)) {
                if (accepts(visible.declaration)) names.addElement(ModulePaths.lookup(visible))
            }
        }
    }

    /**
     * The modules an `open` can name: the project files that the analyzer would load under that path, by their
     * declared or path-derived name, then the bundled `util` modules that no project file shadows. A project
     * file whose declared name does not lead back to it is offered last, greyed out.
     */
    private object ModulePaths {
        fun add(parameters: CompletionParameters, result: CompletionResultSet) {
            val opener = parameters.originalFile as? AlloyFile ?: return
            val openerPath = opener.virtualFile?.path ?: return
            val moduleName = moduleDeclaration(opener).orEmpty()
            // The whole path up to the caret is the prefix: completion replaces `util/` with `util/ordering`
            val path = parameters.position.parent
            val prefix = path.text.substring(0, parameters.offset - path.textRange.startOffset)
            val paths = result.withPrefixMatcher(prefix)

            val reachable = mutableSetOf<String>()
            val manager = PsiManager.getInstance(opener.project)
            for (candidate in FileTypeIndex.getFiles(AlloyFileType, GlobalSearchScope.projectScope(opener.project))) {
                if (candidate == opener.virtualFile) continue
                val file = manager.findFile(candidate) as? AlloyFile ?: continue
                val declared = moduleDeclaration(file)
                if (declared == null) {
                    val name = AlloyModelService.pathDerivedName(openerPath, moduleName, candidate.path) ?: continue
                    reachable += name
                    paths.addElement(module(name, file))
                } else if (AlloyModelService.openedPath(openerPath, moduleName, declared) == candidate.path) {
                    reachable += declared
                    paths.addElement(module(declared, file))
                } else {
                    paths.addElement(unreachable(declared, file))
                }
            }
            val library = AlloyPositions.libraryRoot()?.findChild("util")?.children.orEmpty()
            for (file in library.filter { it.extension == "als" }) {
                val name = "util/" + file.nameWithoutExtension
                if (name !in reachable) paths.addElement(module(name))
            }
        }

        private fun module(name: String, file: AlloyFile? = null): LookupElement =
            (if (file == null) LookupElementBuilder.create(name) else LookupElementBuilder.create(file, name)).withIcon(AllIcons.Nodes.Module)

        private fun unreachable(name: String, file: AlloyFile): LookupElement = PrioritizedLookupElement.withPriority(
            LookupElementBuilder.create(file, name)
                .withIcon(AllIcons.Nodes.Module)
                .withItemTextForeground(JBColor.GRAY)
                .withTailText(" cannot be opened from this file", true)
                .withAutoCompletionPolicy(AutoCompletionPolicy.NEVER_AUTOCOMPLETE),
            -1.0,
        )

        fun lookup(visible: AlloyScopes.Visible): LookupElement {
            val declaration = visible.declaration
            var builder = LookupElementBuilder.create(declaration, declaration.name.orEmpty())
                .withIcon(AlloyPresentation.icon(declaration))
                .withTypeText(AlloyPresentation.kind(declaration))
            visible.module?.let { builder = builder.withTailText(" " + moduleName(it), true) }
            return PrioritizedLookupElement.withPriority(builder, PRIORITIES.getValue(visible.layer))
        }

        /** Locals first, then the fields of the enclosing signature, the module's declarations, and opened ones. */
        private val PRIORITIES = mapOf(
            AlloyScopes.Layer.LOCAL to 4.0,
            AlloyScopes.Layer.FIELD to 3.0,
            AlloyScopes.Layer.TOP_LEVEL to 2.0,
            AlloyScopes.Layer.OPENED to 1.0,
        )

        /** `util/ordering` for the bundled module, its declared name for a project module, or its file name. */
        private fun moduleName(file: AlloyFile): String =
            moduleDeclaration(file) ?: file.virtualFile?.nameWithoutExtension ?: file.name.removeSuffix(".als")

        /** The name in the `module` declaration of [file], if it has one. */
        private fun moduleDeclaration(file: AlloyFile): String? =
            file.children.filterIsInstance<AlloyModuleDecl>().firstOrNull()?.qualifiedName?.text
    }
}
