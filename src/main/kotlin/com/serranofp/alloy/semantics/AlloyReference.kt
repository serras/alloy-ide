package com.serranofp.alloy.semantics

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.ResolveResult
import com.intellij.psi.impl.source.resolve.ResolveCache
import com.intellij.psi.util.PsiTreeUtil
import com.serranofp.alloy.lexer.AlloyTokenSets
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
import com.serranofp.alloy.psi.declarationNodes
import com.serranofp.alloy.psi.AlloyComprehension
import com.serranofp.alloy.psi.AlloyQuantifierExpr
import edu.mit.csail.sdg.ast.Clause

/**
 * A use of a name. With a model that type-checks for the current text, it resolves to exactly what the
 * analyzer resolved it to (`CompModule.find` and `Expr.referenced`); otherwise by name, through [AlloyScopes].
 * A rename replaces only its last identifier, keeping a qualifier: `ord/first` becomes `ord/last`.
 */
class AlloyReference(element: PsiElement) : PsiReferenceBase.Poly<PsiElement>(element, TextRange(0, element.textLength), false) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val file = element.containingFile as? AlloyFile ?: return ResolveResult.EMPTY_ARRAY
        // Until the model is computed (only off the EDT) a result by name would outlive it in the cache; a file
        // that is not a module never gets a model, and resolves by name only
        if (file.isAnalyzable && AlloyModelService.getInstance(element.project).availableModel(file) == null) return resolveTargets()
        return ResolveCache.getInstance(element.project).resolveWithCaching(this, RESOLVER, false, incompleteCode)
    }

    private fun resolveTargets(): Array<ResolveResult> {
        val targets = exactTarget()?.let(::listOf) ?: AlloyScopes.resolve(element, element.text)
        return targets.map(::PsiElementResolveResult).toTypedArray()
    }

    /**
     * What the analyzer resolved this use to (`CompModule.find` and `Expr.referenced`), if the model type-checks
     * for the current text: what it refers to, and its declaration if that is in a file, such as a signature
     * rather than a built-in.
     */
    fun exactResolution(): AlloyResolution? {
        val file = element.containingFile as? AlloyFile ?: return null
        if (!file.isAnalyzable) return null
        val model = AlloyModelService.getInstance(element.project).availableModel(file) as? AlloyModelResult.Model ?: return null
        val filename = AlloyPositions.filename(file.virtualFile)
        val lines = model.modules.lines(filename) ?: return null
        val start = element.textRange.startOffset
        if (start >= lines.length) return null
        val pos = AlloyPositions.pos(filename, lines, TextRange(start, start))
        val referenced = AlloyExprIndex.of(model).find(pos)?.referenced() ?: return null
        return AlloyResolution(referenced, declarationAt(referenced.pos(), model.modules, element.manager))
    }

    /** The declaration the analyzer resolved this use to, if the model type-checks for the current text. */
    fun exactTarget(): AlloyNamedDeclaration? = exactResolution()?.declaration

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
            // The text the analyzer saw, or for a library module (which never changes) its file's
            val offset = modules.lines(pos.filename)?.let { AlloyPositions.textRange(pos, it).startOffset }
                ?: FileDocumentManager.getInstance().getDocument(virtualFile)?.let { AlloyPositions.textRange(pos, it).startOffset }
                ?: return null
            val declarations = generateSequence(file.findElementAt(offset)) { it.parent.takeUnless { parent -> parent is PsiFile } }
                .filterIsInstance<AlloyNamedDeclaration>()
                .toList()
            return declarations.firstOrNull { it.nameIdentifier?.textRange?.startOffset == offset }
                ?: declarations.firstOrNull { it.parent is AlloyFile }
        }
    }
}

/** What the analyzer resolved a use to: the expression it refers to, and where that is declared. */
class AlloyResolution(val referenced: Clause, val declaration: AlloyNamedDeclaration?)

/**
 * Resolution by name, for a model that does not type-check (or is not computed yet), innermost scope first:
 * binders and parameters, the fields of the enclosing signature, the declarations of the module, those of
 * the modules it opens (by alias, or unqualified), and built-ins, which resolve to nothing. Every match of
 * the first scope with any is returned. [visible] lists what these scopes offer, for completion.
 */
object AlloyScopes {
    fun resolve(reference: PsiElement, text: String): List<AlloyNamedDeclaration> {
        val file = reference.containingFile as? AlloyFile ?: return emptyList()
        val qualifier = text.substringBeforeLast('/', "").ifEmpty { null }
        val name = text.substringAfterLast('/')
        if (qualifier == null) {
            localScopes(reference).map { scope -> scope.filter { it.name == name } }.firstOrNull { it.isNotEmpty() }?.let { return it }
            enclosingFields(reference).filter { it.name == name }.ifNotEmpty { return it }
        }
        if (qualifier == null || qualifier == "this") {
            topLevel(file, name).ifNotEmpty { return it }
            if (qualifier == "this") return emptyList()
        }
        return openedModules(file, qualifier).flatMap { topLevel(it, name) }
    }

    /** Where a visible declaration comes from, innermost first. */
    enum class Layer { LOCAL, FIELD, TOP_LEVEL, OPENED }

    /** A declaration visible at some position, from [module], or from the current file if it is null. */
    data class Visible(val declaration: AlloyNamedDeclaration, val layer: Layer, val module: AlloyFile?)

    /**
     * The declarations that a name at [position] can refer to, after [qualifier] (an alias or path of an
     * opened module, or `this`) if there is one. A name of an inner layer hides that name in the outer ones;
     * the `private` declarations of opened modules are not visible.
     */
    fun visible(position: PsiElement, qualifier: String? = null): List<Visible> {
        val file = position.containingFile as? AlloyFile ?: return emptyList()
        val result = mutableListOf<Visible>()
        val hidden = mutableSetOf<String>()
        fun layer(layer: Layer, declarations: List<AlloyNamedDeclaration>, module: AlloyFile? = null) {
            val found = declarations.filter { it.name != null && it.name !in hidden }
            result += found.map { Visible(it, layer, module) }
            hidden += found.mapNotNull { it.name }
        }
        if (qualifier == null) {
            // An inner binder hides an outer one of the same name
            localScopes(position).forEach { layer(Layer.LOCAL, it) }
            layer(Layer.FIELD, enclosingFields(position))
        }
        if (qualifier == null || qualifier == "this") {
            layer(Layer.TOP_LEVEL, declarations(file))
            if (qualifier == "this") return result
        }
        // Names of different opened modules do not hide each other
        val outer = hidden.toSet()
        result += openedModules(file, qualifier).flatMap { module ->
            declarations(module).filter { it.name != null && it.name !in outer && !isPrivate(it) }.map { Visible(it, Layer.OPENED, module) }
        }.distinctBy { it.declaration }
        return result
    }

    /** The binders and parameters in scope at [reference], one list per enclosing scope, innermost first. */
    private fun localScopes(reference: PsiElement): Sequence<List<AlloyNamedDeclaration>> {
        val offset = reference.textRange.startOffset
        return generateSequence(reference.parent) { it.parent }.takeWhile { it !is AlloyFile }.map { scope ->
            when (scope) {
                is AlloyQuantifierExpr, is AlloyComprehension, is AlloyLetExpr ->
                    scope.children.filter { (it is AlloyBinderDecl || it is AlloyLetDecl) && offset >= scopeStart(it as AlloyNamedDeclaration) }
                        .map { it as AlloyNamedDeclaration }
                is AlloyPredDecl, is AlloyFunDecl, is AlloyMacroDecl -> scope.children.filterIsInstance<AlloyParamDecl>()
                else -> emptyList()
            }
        }
    }

    /**
     * Where a binder comes into scope: after its group, as in `all x: A, y: x.next | ...`, where the bound of `y`
     * already sees `x`; a `let` binding is in scope after its value. Nothing sees it before.
     */
    private fun scopeStart(binder: AlloyNamedDeclaration): Int = declarationNodes(binder).last().textRange.endOffset

    private fun enclosingFields(reference: PsiElement): List<AlloyNamedDeclaration> {
        val sig = PsiTreeUtil.getParentOfType(reference, AlloySigDecl::class.java) ?: return emptyList()
        return PsiTreeUtil.findChildrenOfType(sig, AlloyFieldDecl::class.java).toList()
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

    /** The modules that [file] opens, all of them, or those whose alias or path is [qualifier]. */
    private fun openedModules(file: AlloyFile, qualifier: String?): List<AlloyFile> {
        val service = AlloyModelService.getInstance(file.project)
        return file.children.filterIsInstance<AlloyOpenDecl>().mapNotNull { open ->
            val names = open.qualifiedNameList
            val path = names.firstOrNull()?.text ?: return@mapNotNull null
            val alias = names.getOrNull(1)?.text ?: path.substringAfterLast('/')
            if (qualifier != null && qualifier != alias && qualifier != path) return@mapNotNull null
            service.openedModule(file, open)
        }
    }

    /**
     * Whether [declaration] is `private` to its module: its paragraph, or its group of fields, starts with
     * `private`. The members of an enum are as private as the enum.
     */
    private fun isPrivate(declaration: AlloyNamedDeclaration): Boolean = when (declaration) {
        is AlloyFieldDecl -> {
            // `private a, b: A` is one group; the bound of the group before it ends the walk
            val group = generateSequence(declaration.prevSibling) { it.prevSibling }
                .filterNot { it is PsiWhiteSpace || it is PsiComment }
                .takeWhile { it is AlloyFieldDecl || it.node.elementType in AlloyTokenSets.FIELD_GROUP }
            group.any { it.node.elementType == AlloyTypes.PRIVATE }
        }
        is AlloySigName -> declaration.parent.node.findChildByType(AlloyTypes.PRIVATE) != null
        is AlloyEnumMember -> PsiTreeUtil.getParentOfType(declaration, AlloyEnumDecl::class.java)?.node?.findChildByType(AlloyTypes.PRIVATE) != null
        else -> declaration.node.findChildByType(AlloyTypes.PRIVATE) != null
    }

    private inline fun <T> List<T>.ifNotEmpty(action: (List<T>) -> Unit) {
        if (isNotEmpty()) action(this)
    }
}
