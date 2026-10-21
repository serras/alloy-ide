package com.serranofp.alloy.run

import com.intellij.psi.PsiFile
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiTreeUtil
import com.serranofp.alloy.psi.AlloyCommandDecl
import com.serranofp.alloy.psi.AlloyQualifiedName
import com.serranofp.alloy.psi.AlloyTypes

/**
 * How a result identifies a command: by its label when it has one, otherwise by its position among the
 * commands of the file, in the native PSI. At launch it is found in the analyzer's commands by the
 * position of its PSI element (see [AlloyCommandLauncher]).
 */
data class AlloyCommandRef(val label: String?, val index: Int) {
    val displayName: String
        get() = label ?: "command ${index + 1}"
}

object AlloyCommands {
    private val END_OF_NAMES = TokenSet.create(AlloyTypes.FOR, AlloyTypes.EXPECT, AlloyTypes.IMPLIES, AlloyTypes.IMPLIES_ARROW)

    /** Top-level commands in source order. */
    fun commands(file: PsiFile): List<AlloyCommandDecl> = file.children.filterIsInstance<AlloyCommandDecl>()

    /** The command of [file] that [ref] names: by label if it has one (its position may be stale), else by position. */
    fun find(file: PsiFile, ref: AlloyCommandRef): AlloyCommandDecl? =
        if (ref.label != null) commands(file).firstOrNull { label(it)?.let { label -> sameLabel(label, ref.label) } == true }
        else commands(file).getOrNull(ref.index)

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

    /**
     * The `expect` of the command that a `=>` chain executes, its last part, as the analyzer reads it:
     * 1 for a positive number, 0 for zero, and -1 without `expect`.
     */
    fun expects(command: AlloyCommandDecl): Int {
        var expects = -1
        var child = command.firstChild
        while (child != null) {
            when (child.node.elementType) {
                AlloyTypes.RUN, AlloyTypes.CHECK -> expects = -1
                AlloyTypes.NUMBER -> if (PsiTreeUtil.skipWhitespacesAndCommentsBackward(child)?.node?.elementType == AlloyTypes.EXPECT) {
                    expects = if (child.text.any { it in "123456789" }) 1 else 0
                }
            }
            child = child.nextSibling
        }
        return expects
    }

    fun sameLabel(a: String, b: String): Boolean = a.removePrefix("this/") == b.removePrefix("this/")
}
