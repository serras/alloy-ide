package com.serranofp.alloy.run

import com.intellij.psi.PsiFile
import com.intellij.psi.tree.TokenSet
import com.serranofp.alloy.psi.AlloyCommandDecl
import com.serranofp.alloy.psi.AlloyQualifiedName
import com.serranofp.alloy.psi.AlloyTypes

/**
 * How a run configuration identifies a command: by its label when it has one, otherwise by its position
 * among the commands of the file. Positions come from the native PSI; the server's own positions are only
 * used at launch (see [AlloyCommandLauncher]).
 */
data class AlloyCommandRef(val label: String?, val index: Int) {
    val displayName: String
        get() = label ?: "command ${index + 1}"
}

object AlloyCommands {
    private val END_OF_NAMES = TokenSet.create(AlloyTypes.FOR, AlloyTypes.EXPECT, AlloyTypes.IMPLIES, AlloyTypes.IMPLIES_ARROW)

    /** Top-level commands in source order, the same order in which the server lists them. */
    fun commands(file: PsiFile): List<AlloyCommandDecl> = file.children.filterIsInstance<AlloyCommandDecl>()

    fun ref(command: AlloyCommandDecl): AlloyCommandRef =
        AlloyCommandRef(label(command), commands(command.containingFile).indexOf(command))

    /**
     * `run show {...}`, `run show for 3` and `run show target` are labelled `show`; `run {...}` has no label.
     * Only the first command of a `=>` chain is considered, and names in the scope are not labels.
     */
    fun label(command: AlloyCommandDecl): String? {
        var child = command.firstChild
        while (child != null) {
            if (child.node.elementType in END_OF_NAMES) return null
            if (child is AlloyQualifiedName) return child.text
            child = child.nextSibling
        }
        return null
    }

    /** "Run show for 3 expect 1" and "Check noCycles for 3" are labelled `show` and `noCycles`. */
    fun labelOfServerTitle(title: String): String? =
        title.split(' ').takeIf { it.size >= 2 && (it[0] == "Run" || it[0] == "Check") }?.get(1)

    fun sameLabel(a: String, b: String): Boolean = a.removePrefix("this/") == b.removePrefix("this/")
}
