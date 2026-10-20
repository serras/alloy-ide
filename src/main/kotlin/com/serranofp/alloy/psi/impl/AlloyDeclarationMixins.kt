package com.serranofp.alloy.psi.impl

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.extapi.psi.StubBasedPsiElementBase
import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.ide.projectView.PresentationData
import com.intellij.navigation.ItemPresentation
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.stubs.IStubElementType
import com.serranofp.alloy.editor.AlloyPresentation
import com.serranofp.alloy.psi.AlloyElementFactory
import com.serranofp.alloy.psi.AlloyNamedDeclaration
import com.serranofp.alloy.psi.AlloyTypes
import com.serranofp.alloy.psi.stubs.AlloyNamedStub

/**
 * The identifier that names [node]: its own `IDENTIFIER` child (signature names, fields, parameters, binders),
 * or for a paragraph the last identifier of its last direct name, which follows the receiver of `pred A.p`.
 */
private fun nameIdentifier(node: ASTNode): PsiElement? {
    node.findChildByType(AlloyTypes.IDENTIFIER)?.let { return it.psi }
    val name = node.getChildren(null).lastOrNull { it.elementType == AlloyTypes.QUALIFIED_NAME } ?: return null
    return name.getChildren(null).lastOrNull { it.elementType == AlloyTypes.IDENTIFIER }?.psi
}

private fun rename(declaration: AlloyNamedDeclaration, name: String) {
    val identifier = declaration.nameIdentifier ?: return
    identifier.replace(AlloyElementFactory.identifier(declaration.project, name))
}

/** Declarations that live only inside a paragraph: parameters and binders. */
abstract class AlloyLocalDeclarationMixin(node: ASTNode) : ASTWrapperPsiElement(node), AlloyNamedDeclaration {
    override fun getNameIdentifier(): PsiElement? = nameIdentifier(node)

    override fun getName(): String? = nameIdentifier?.text

    override fun setName(name: String): PsiElement = apply { rename(this, name) }

    override fun getTextOffset(): Int = nameIdentifier?.textOffset ?: super.getTextOffset()

    /** Only the paragraph declares them, so only it can use them; this also makes their rename in place. */
    override fun getUseScope(): SearchScope = LocalSearchScope(scopeOf(this))
}

/** The element whose text can use [declaration]: its quantifier, `let` or comprehension, or its paragraph. */
private fun scopeOf(declaration: PsiElement): PsiElement =
    declaration.parent?.takeUnless { it is PsiFile } ?: declaration

/** Declarations visible from other paragraphs and files, kept in the stub index (see `AlloyNameIndex`). */
abstract class AlloyNamedStubMixin : StubBasedPsiElementBase<AlloyNamedStub>, AlloyNamedDeclaration {
    constructor(node: ASTNode) : super(node)

    constructor(stub: AlloyNamedStub, type: IStubElementType<*, *>) : super(stub, type)

    override fun getNameIdentifier(): PsiElement? = nameIdentifier(node)

    /** From the stub when there is one, so that the file is not parsed. */
    override fun getName(): String? = greenStub?.name ?: nameIdentifier?.text

    override fun setName(name: String): PsiElement = apply { rename(this, name) }

    override fun getTextOffset(): Int = nameIdentifier?.textOffset ?: super.getTextOffset()

    /** For Go to Symbol: the name, the file it is declared in, and its kind's icon. */
    override fun getPresentation(): ItemPresentation = PresentationData(
        AlloyPresentation.text(this) ?: name,
        containingFile.name,
        AlloyPresentation.icon(this),
        null,
    )

    /** As for the other PSI elements, which tests compare in tree dumps. */
    override fun toString(): String = "${javaClass.simpleName}($elementTypeImpl)"
}

/**
 * A command is named by its label, in `run name { ... }`; in `run show for 3` the name is the target, a
 * reference to the predicate `show`, and the command has no name of its own.
 */
abstract class AlloyCommandMixin : AlloyNamedStubMixin {
    constructor(node: ASTNode) : super(node)

    constructor(stub: AlloyNamedStub, type: IStubElementType<*, *>) : super(stub, type)

    override fun getNameIdentifier(): PsiElement? {
        val label = node.getChildren(null).firstOrNull { it.elementType == AlloyTypes.QUALIFIED_NAME } ?: return null
        val next = generateSequence(label.treeNext) { it.treeNext }.firstOrNull { it.psi !is PsiWhiteSpace && it.psi !is PsiComment }
        if (next?.elementType != AlloyTypes.BLOCK) return null
        return label.getChildren(null).lastOrNull { it.elementType == AlloyTypes.IDENTIFIER }?.psi
    }
}
