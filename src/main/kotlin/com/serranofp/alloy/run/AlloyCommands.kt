package com.serranofp.alloy.run

import com.intellij.psi.PsiFile
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import com.serranofp.alloy.AlloyBundle
import com.serranofp.alloy.psi.AlloyCommandDecl
import com.serranofp.alloy.psi.AlloyQualifiedName
import com.serranofp.alloy.psi.AlloyTypes

/**
 * How a result identifies a command of a file, in the native PSI: a labelled command by its label and its
 * [occurrence] among the commands with that label (0 unless the label repeats, as in `check a for 3` and
 * `check a for 6`); an unlabelled one by its [index] among the commands. Equality follows that identity, so
 * a labelled command keeps its result when commands are inserted above it, and the [index] of a labelled
 * command is only where it was. At launch the command is found in the analyzer's commands by the position
 * of its PSI element (see [AlloyCommandLauncher]).
 */
class AlloyCommandRef(val label: String?, val index: Int, val occurrence: Int = 0) {
    val displayName: String
        get() = label ?: AlloyBundle.message("run.command.unlabelled", index + 1)

    /** The label without `this/`, which names the same command. */
    private val key: Any = if (label != null) label.removePrefix("this/") to occurrence else index

    override fun equals(other: Any?): Boolean = other is AlloyCommandRef && other.key == key

    override fun hashCode(): Int = key.hashCode()

    override fun toString(): String = "AlloyCommandRef($label, $index, $occurrence)"
}

object AlloyCommands {
    private val END_OF_NAMES = TokenSet.create(AlloyTypes.FOR, AlloyTypes.EXPECT, AlloyTypes.IMPLIES, AlloyTypes.IMPLIES_ARROW)

    /** Top-level commands in source order. */
    fun commands(file: PsiFile): List<AlloyCommandDecl> = file.children.filterIsInstance<AlloyCommandDecl>()

    /** The command of [file] that [ref] names (see [AlloyCommandRef]). */
    fun find(file: PsiFile, ref: AlloyCommandRef): AlloyCommandDecl? = refs(file).entries.firstOrNull { it.value == ref }?.key

    fun ref(command: AlloyCommandDecl): AlloyCommandRef =
        refs(command.containingFile)[command] ?: AlloyCommandRef(label(command), -1)

    /**
     * The ref of every top-level command of [file], in source order. The line markers and the hints ask for
     * every command, so they are computed once per version of the file rather than by a walk per command.
     */
    private fun refs(file: PsiFile): Map<AlloyCommandDecl, AlloyCommandRef> = CachedValuesManager.getCachedValue(file) {
        val occurrences = mutableMapOf<String, Int>()
        val refs = commands(file).withIndex().associate { (index, command) ->
            val label = label(command)
            val occurrence = label?.let { occurrences.merge(it.removePrefix("this/"), 1, Int::plus)!! - 1 } ?: 0
            command to AlloyCommandRef(label, index, occurrence)
        }
        CachedValueProvider.Result.create(refs, file)
    }

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
}
