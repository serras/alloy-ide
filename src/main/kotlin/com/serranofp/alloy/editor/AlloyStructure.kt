package com.serranofp.alloy.editor

import com.intellij.icons.AllIcons
import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.navigation.ItemPresentation
import com.intellij.pom.Navigatable
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.openapi.editor.Editor
import com.intellij.platform.backend.navigation.NavigationRequest
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.breadcrumbs.BreadcrumbsProvider
import com.serranofp.alloy.AlloyLanguage
import com.serranofp.alloy.psi.AlloyAssertDecl
import com.serranofp.alloy.psi.AlloyBlock
import com.serranofp.alloy.psi.AlloyCommandDecl
import com.serranofp.alloy.psi.AlloyEnumDecl
import com.serranofp.alloy.psi.AlloyEnumMember
import com.serranofp.alloy.psi.AlloyFactDecl
import com.serranofp.alloy.psi.AlloyFieldDecl
import com.serranofp.alloy.psi.AlloyFile
import com.serranofp.alloy.psi.AlloyFunDecl
import com.serranofp.alloy.psi.AlloyMacroDecl
import com.serranofp.alloy.psi.AlloyModuleDecl
import com.serranofp.alloy.psi.AlloyNamedDeclaration
import com.serranofp.alloy.psi.AlloyPredDecl
import com.serranofp.alloy.psi.AlloySigDecl
import com.serranofp.alloy.psi.AlloySigName
import javax.swing.Icon

/** How the structure view, breadcrumbs and Go to Symbol show Alloy paragraphs, fields and enum members. */
object AlloyPresentation {
    /** The elements listed, in source order: the file's paragraphs, then their fields or members. */
    fun children(element: PsiElement): List<PsiElement> = when (element) {
        is AlloyFile -> element.children.filter { it is AlloyModuleDecl || text(it) != null }
        is AlloySigDecl -> PsiTreeUtil.findChildrenOfType(element, AlloyFieldDecl::class.java).toList()
        is AlloyEnumDecl -> PsiTreeUtil.findChildrenOfType(element, AlloyEnumMember::class.java).toList()
        else -> emptyList()
    }

    /** What an element is called, or null for an element that is not listed. */
    fun text(element: PsiElement): String? = when (element) {
        is AlloyModuleDecl -> element.text.replace(Regex("\\s+"), " ")
        is AlloySigDecl -> element.children.filterIsInstance<AlloySigName>().joinToString(", ") { it.name.orEmpty() }
        is AlloyFactDecl -> element.name ?: "fact"
        is AlloyAssertDecl -> element.name ?: "assert"
        is AlloyCommandDecl -> commandText(element)
        is AlloyNamedDeclaration -> element.name
        else -> null
    }

    fun icon(element: PsiElement): Icon? = when (element) {
        is AlloyModuleDecl -> AllIcons.Nodes.Module
        is AlloySigDecl, is AlloySigName -> AllIcons.Nodes.Class
        is AlloyFieldDecl -> AllIcons.Nodes.Field
        is AlloyEnumDecl -> AllIcons.Nodes.Enum
        is AlloyEnumMember -> AllIcons.Nodes.Constant
        is AlloyFactDecl -> AllIcons.Nodes.Static
        is AlloyPredDecl -> AllIcons.Nodes.Method
        is AlloyFunDecl -> AllIcons.Nodes.Function
        is AlloyAssertDecl -> AllIcons.Nodes.Test
        is AlloyMacroDecl -> AllIcons.Nodes.Lambda
        is AlloyCommandDecl -> AllIcons.RunConfigurations.TestState.Run
        else -> null
    }

    /** `run show for 3`, or `run { ... } for 3`: the command as written, with its blocks folded. */
    private fun commandText(command: AlloyCommandDecl): String =
        command.node.getChildren(null).joinToString("") { if (it.psi is AlloyBlock) "{...}" else it.text }
            .replace(Regex("\\s+"), " ").trim()
}

class AlloyStructureViewFactory : PsiStructureViewFactory {
    override fun getStructureViewBuilder(psiFile: PsiFile): StructureViewBuilder? {
        if (psiFile !is AlloyFile) return null
        return object : TreeBasedStructureViewBuilder() {
            override fun createStructureViewModel(editor: Editor?): StructureViewModel =
                StructureViewModelBase(psiFile, editor, AlloyStructureElement(psiFile))
                    .withSuitableClasses(AlloyNamedDeclaration::class.java, AlloySigDecl::class.java, AlloyModuleDecl::class.java)
        }
    }
}

/** A node of the structure view: the file, a paragraph, or a field or enum member under its paragraph. */
class AlloyStructureElement(private val element: PsiElement) : StructureViewTreeElement {
    override fun getValue(): PsiElement = element

    override fun getPresentation(): ItemPresentation =
        PresentationData(if (element is AlloyFile) element.name else AlloyPresentation.text(element), null, AlloyPresentation.icon(element), null)

    override fun getChildren(): Array<TreeElement> =
        AlloyPresentation.children(element).map(::AlloyStructureElement).toTypedArray()

    // override fun navigationRequest(): NavigationRequest? = (element as? Navigatable)?.navigationRequest()

    override fun navigate(requestFocus: Boolean) {
        (element as? Navigatable)?.navigate(requestFocus)
    }

    override fun canNavigate(): Boolean = (element as? Navigatable)?.canNavigate() == true

    override fun canNavigateToSource(): Boolean = (element as? Navigatable)?.canNavigateToSource() == true
}

/** The paragraph, and the field, around the caret. */
class AlloyBreadcrumbsProvider : BreadcrumbsProvider {
    override fun getLanguages() = arrayOf(AlloyLanguage)

    override fun acceptElement(element: PsiElement): Boolean =
        (element.parent is AlloyFile && AlloyPresentation.text(element) != null) || element is AlloyFieldDecl

    override fun getElementInfo(element: PsiElement): String = AlloyPresentation.text(element).orEmpty()

    override fun getElementIcon(element: PsiElement): Icon? = AlloyPresentation.icon(element)
}
