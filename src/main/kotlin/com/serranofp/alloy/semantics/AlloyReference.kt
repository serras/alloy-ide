package com.serranofp.alloy.semantics

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.impl.DocumentImpl
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.ResolveResult
import com.intellij.psi.impl.source.resolve.ResolveCache
import com.intellij.psi.util.PsiTreeUtil
import com.serranofp.alloy.psi.AlloyAssertDecl
import com.serranofp.alloy.psi.AlloyBinderDecl
import com.serranofp.alloy.psi.AlloyElementFactory
import com.serranofp.alloy.psi.AlloyEnumDecl
import com.serranofp.alloy.psi.AlloyEnumMember
import com.serranofp.alloy.psi.AlloyFieldDecl
import com.serranofp.alloy.psi.AlloyFile
import com.serranofp.alloy.psi.AlloyFunDecl
import com.serranofp.alloy.psi.AlloyLetDecl
import com.serranofp.alloy.psi.AlloyLetExpr
import com.serranofp.alloy.psi.AlloyMacroDecl
import com.serranofp.alloy.psi.AlloyNamedDeclaration
import com.serranofp.alloy.psi.AlloyOpenDecl
import com.serranofp.alloy.psi.AlloyParamDecl
import com.serranofp.alloy.psi.AlloyPredDecl
import com.serranofp.alloy.psi.AlloySigDecl
import com.serranofp.alloy.psi.AlloySigName
import com.serranofp.alloy.psi.AlloyTypes
import com.serranofp.alloy.psi.AlloyBlock
import com.serranofp.alloy.psi.AlloyComprehension
import com.serranofp.alloy.psi.AlloyQuantifierExpr

/**
 * A use of a name. With a model that type-checks for the current text, it resolves to exactly what the
 * analyzer resolved it to (`CompModule.find` and `Expr.referenced`); otherwise by name, through [AlloyScopes].
 * A rename replaces only its last identifier, keeping a qualifier: `ord/first` becomes `ord/last`.
 */
class AlloyReference(element: PsiElement) : PsiReferenceBase.Poly<PsiElement>(element, TextRange(0, element.textLength), false) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val file = element.containingFile as? AlloyFile ?: return ResolveResult.EMPTY_ARRAY
        // Until the model is computed (only off the EDT) a result by name would outlive it in the cache
        if (AlloyModelService.getInstance(element.project).availableModel(file) == null) return resolveTargets()
        return ResolveCache.getInstance(element.project).resolveWithCaching(this, RESOLVER, false, incompleteCode)
    }

    private fun resolveTargets(): Array<ResolveResult> {
        val targets = exactTarget()?.let(::listOf) ?: AlloyScopes.resolve(element, element.text)
        return targets.map(::PsiElementResolveResult).toTypedArray()
    }

    /** The declaration the analyzer resolved this use to, if the model type-checks for the current text. */
    fun exactTarget(): AlloyNamedDeclaration? {
        val file = element.containingFile as? AlloyFile ?: return null
        val model = AlloyModelService.getInstance(element.project).availableModel(file) as? AlloyModelResult.Model ?: return null
        val filename = AlloyPositions.filename(file.virtualFile ?: return null)
        val document = model.modules.contents[filename]?.let(::DocumentImpl) ?: return null
        val start = element.textRange.startOffset
        if (start >= document.textLength) return null
        val target = model.module.find(AlloyPositions.pos(filename, document, TextRange(start, start)))?.referenced() ?: return null
        return declarationAt(target.pos(), model.modules, element.manager)
    }

    /** How the analyzer explains what this use refers to, if the model type-checks for the current text. */
    fun exactExplanation(): String? {
        val file = element.containingFile as? AlloyFile ?: return null
        val model = AlloyModelService.getInstance(element.project).availableModel(file) as? AlloyModelResult.Model ?: return null
        val filename = AlloyPositions.filename(file.virtualFile ?: return null)
        val document = model.modules.contents[filename]?.let(::DocumentImpl) ?: return null
        val start = element.textRange.startOffset
        if (start >= document.textLength) return null
        return model.module.find(AlloyPositions.pos(filename, document, TextRange(start, start)))?.referenced()?.explain()
    }

    /** Whether this use resolves through the analyzer, rather than by name. */
    fun isExact(): Boolean = exactTarget() != null

    override fun handleElementRename(newElementName: String): PsiElement {
        val identifier = element.node.getChildren(null).lastOrNull { it.elementType == AlloyTypes.IDENTIFIER } ?: return element
        identifier.psi.replace(AlloyElementFactory.identifier(element.project, newElementName))
        return element
    }

    override fun getVariants(): Array<Any> = emptyArray()

    companion object {
        private val RESOLVER = ResolveCache.PolyVariantResolver<AlloyReference> { reference, _ -> reference.resolveTargets() }

        /**
         * The declaration at the analyzer's [pos]: a name (signatures, fields, binders) or the start of a
         * paragraph (predicates, functions, assertions).
         */
        fun declarationAt(pos: edu.mit.csail.sdg.alloy4.Pos, modules: AlloyModules, manager: PsiManager): AlloyNamedDeclaration? {
            val virtualFile = AlloyPositions.file(pos.filename, modules.files) ?: return null
            val file = manager.findFile(virtualFile) as? AlloyFile ?: return null
            val document: Document = modules.contents[pos.filename]?.let(::DocumentImpl)
                ?: FileDocumentManager.getInstance().getDocument(virtualFile) ?: return null
            val offset = AlloyPositions.textRange(pos, document).startOffset
            val declarations = generateSequence(file.findElementAt(offset)) { it.parent.takeUnless { parent -> parent is PsiFile } }
                .filterIsInstance<AlloyNamedDeclaration>()
                .toList()
            return declarations.firstOrNull { it.nameIdentifier?.textRange?.startOffset == offset }
                ?: declarations.firstOrNull { it.parent is AlloyFile }
        }
    }
}

/**
 * Resolution by name, for a model that does not type-check (or is not computed yet), innermost scope first:
 * binders and parameters, the fields of the enclosing signature, the declarations of the module, those of
 * the modules it opens (by alias, or unqualified), and built-ins, which resolve to nothing. Every match of
 * the first scope with any is returned.
 */
object AlloyScopes {
    fun resolve(reference: PsiElement, text: String): List<AlloyNamedDeclaration> {
        val file = reference.containingFile as? AlloyFile ?: return emptyList()
        val qualifier = text.substringBeforeLast('/', "").ifEmpty { null }
        val name = text.substringAfterLast('/')
        if (qualifier == null) {
            locals(reference, name).ifNotEmpty { return it }
            enclosingFields(reference, name).ifNotEmpty { return it }
        }
        if (qualifier == null || qualifier == "this") {
            topLevel(file, name).ifNotEmpty { return it }
            if (qualifier == "this") return emptyList()
        }
        return opened(file, qualifier, name)
    }

    /** The binders and parameters in scope at [reference], innermost first. */
    private fun locals(reference: PsiElement, name: String): List<AlloyNamedDeclaration> {
        val offset = reference.textRange.startOffset
        var scope: PsiElement? = reference.parent
        while (scope != null && scope !is AlloyFile) {
            val found: List<AlloyNamedDeclaration> = when (scope) {
                is AlloyQuantifierExpr, is AlloyComprehension ->
                    if (offset >= bodyStart(scope)) scope.children.filterIsInstance<AlloyBinderDecl>() else emptyList()
                is AlloyLetExpr -> if (offset >= bodyStart(scope)) scope.children.filterIsInstance<AlloyLetDecl>() else emptyList()
                is AlloyPredDecl, is AlloyFunDecl, is AlloyMacroDecl -> scope.children.filterIsInstance<AlloyParamDecl>()
                else -> emptyList()
            }
            found.filter { it.name == name }.ifNotEmpty { return it }
            scope = scope.parent
        }
        return emptyList()
    }

    /** Where the body of a binder starts: after its `|`, or at its block. Nothing is in scope before it. */
    private fun bodyStart(scope: PsiElement): Int {
        val bar = scope.node.findChildByType(AlloyTypes.BAR)
        if (bar != null) return bar.startOffset
        return scope.children.filterIsInstance<AlloyBlock>().firstOrNull()?.textRange?.startOffset ?: Int.MAX_VALUE
    }

    private fun enclosingFields(reference: PsiElement, name: String): List<AlloyNamedDeclaration> {
        val sig = PsiTreeUtil.getParentOfType(reference, AlloySigDecl::class.java) ?: return emptyList()
        return PsiTreeUtil.findChildrenOfType(sig, AlloyFieldDecl::class.java).filter { it.name == name }
    }

    /** The declarations of [file] that other paragraphs see: signatures, fields, enums, predicates, functions... */
    fun declarations(file: AlloyFile): List<AlloyNamedDeclaration> = file.children.flatMap { paragraph ->
        when (paragraph) {
            is AlloySigDecl -> paragraph.children.filterIsInstance<AlloySigName>() +
                PsiTreeUtil.findChildrenOfType(paragraph, AlloyFieldDecl::class.java)
            is AlloyEnumDecl -> listOf(paragraph) + PsiTreeUtil.findChildrenOfType(paragraph, AlloyEnumMember::class.java)
            is AlloyPredDecl, is AlloyFunDecl, is AlloyAssertDecl, is AlloyMacroDecl -> listOf(paragraph as AlloyNamedDeclaration)
            else -> emptyList()
        }
    }

    private fun topLevel(file: AlloyFile, name: String): List<AlloyNamedDeclaration> = declarations(file).filter { it.name == name }

    private fun opened(file: AlloyFile, qualifier: String?, name: String): List<AlloyNamedDeclaration> {
        val service = AlloyModelService.getInstance(file.project)
        return file.children.filterIsInstance<AlloyOpenDecl>().flatMap { open ->
            val names = open.qualifiedNameList
            val path = names.firstOrNull()?.text ?: return@flatMap emptyList()
            val alias = names.getOrNull(1)?.text ?: path.substringAfterLast('/')
            if (qualifier != null && qualifier != alias && qualifier != path) return@flatMap emptyList()
            service.openedModule(file, open)?.let { topLevel(it, name) }.orEmpty()
        }
    }

    private inline fun <T> List<T>.ifNotEmpty(action: (List<T>) -> Unit) {
        if (isNotEmpty()) action(this)
    }
}
