package com.serranofp.alloy.inspections

import com.intellij.codeInsight.intention.FileModifier
import com.intellij.codeInsight.intention.preview.IntentionPreviewUtils
import com.intellij.codeInsight.template.TemplateBuilderImpl
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.util.PsiTreeUtil
import com.serranofp.alloy.psi.AlloyCommandDecl
import com.serranofp.alloy.psi.AlloyTypes

/**
 * The scope of one command of a `=>` chain, as the flat tokens of the PSI: [tokens] follow its `for` up to its
 * `expect` or the next command. It has an overall scope (`for 3`, `for 3 but ...`) and [items] separated by
 * commas, which come after `but` if there is one.
 */
class AlloyScopeText private constructor(private val forToken: PsiElement, private val tokens: List<PsiElement>) {
    val range: TextRange get() = TextRange(forToken.textRange.startOffset, (tokens.lastOrNull() ?: forToken).textRange.endOffset)

    private val but: PsiElement? = tokens.firstOrNull { it.node.elementType == AlloyTypes.BUT }

    /** `for N` and nothing else. */
    val isOverallOnly: Boolean = tokens.size == 1 && tokens.single().node.elementType == AlloyTypes.NUMBER

    val items: List<List<PsiElement>> = when {
        isOverallOnly -> emptyList()
        else -> {
            val listed = if (but != null) tokens.dropWhile { it !== but }.drop(1) else tokens
            listed.fold(mutableListOf(mutableListOf<PsiElement>())) { items, token ->
                if (token.node.elementType == AlloyTypes.COMMA) items += mutableListOf<PsiElement>() else items.last() += token
                items
            }.filter { it.isNotEmpty() }
        }
    }

    fun itemRange(item: List<PsiElement>): TextRange = TextRange(item.first().textRange.startOffset, item.last().textRange.endOffset)

    /** The index of the item that starts at [offset]. */
    fun itemAt(offset: Int): Int = items.indexOfFirst { itemRange(it).startOffset == offset }

    /** The item that bounds sequences, `N seq`. */
    val seqItem: List<PsiElement>? get() = items.firstOrNull { item -> item.any { it.node.elementType == AlloyTypes.SEQ } }

    /** The item that sets the bitwidth, `N int` or `N Int`. */
    val intItem: List<PsiElement>?
        get() = items.firstOrNull { item -> item.any { it.node.elementType == AlloyTypes.INT || it.node.elementType == AlloyTypes.SIGINT } }

    companion object {
        private val ENDS = setOf(AlloyTypes.EXPECT, AlloyTypes.IMPLIES, AlloyTypes.IMPLIES_ARROW, AlloyTypes.RUN, AlloyTypes.CHECK)

        /** The commands of [decl] are numbered from 0 by their `run` or `check`: this is the one at [offset]. */
        fun partAt(decl: AlloyCommandDecl, offset: Int): Int =
            keywords(decl).indexOfLast { it.textRange.startOffset <= offset }.coerceAtLeast(0)

        /** The scope of the [part]th command of [decl]; null if it has no `for`. */
        fun of(decl: AlloyCommandDecl, part: Int): AlloyScopeText? {
            val rest = rest(decl, part)
            val forToken = rest.firstOrNull { it.node.elementType == AlloyTypes.FOR } ?: return null
            return AlloyScopeText(forToken, rest.dropWhile { it !== forToken }.drop(1))
        }

        /** Where the [part]th command of [decl] ends, before any `expect`: after its scope, body or name. */
        fun endOf(decl: AlloyCommandDecl, part: Int): Int? = rest(decl, part).lastOrNull()?.textRange?.endOffset

        /** The tokens of the [part]th command of [decl] after its `run` or `check`, up to its `expect` or the next command. */
        private fun rest(decl: AlloyCommandDecl, part: Int): List<PsiElement> {
            val keyword = keywords(decl).getOrNull(part) ?: return emptyList()
            return generateSequence(keyword.nextSibling) { it.nextSibling }
                .filter { it !is PsiWhiteSpace && it !is PsiComment }
                .takeWhile { it.node.elementType !in ENDS }
                .toList()
        }

        private fun keywords(decl: AlloyCommandDecl): List<PsiElement> =
            generateSequence(decl.firstChild) { it.nextSibling }
                .filter { it.node.elementType == AlloyTypes.RUN || it.node.elementType == AlloyTypes.CHECK }
                .toList()
    }
}

/** A change to the scope of a command, which returns the number it writes, if any, for the user to edit. */
sealed interface AlloyScopeEdit {
    fun apply(scope: AlloyScopeText, document: Document): TextRange?

    /** The edit for a command without `for`, which ends at [end]; most edits need a scope. */
    fun applyWithoutScope(end: Int, document: Document): TextRange? = null

    /** Adds `N S` (after [prefix], such as `exactly `) as the last item; `for N` becomes `for N but N S`. */
    data class AddItem(val n: Int, val name: String, val prefix: String = "") : AlloyScopeEdit {
        override fun apply(scope: AlloyScopeText, document: Document): TextRange {
            val separator = if (scope.isOverallOnly) " but " else ", "
            val offset = scope.range.endOffset
            document.insertString(offset, "$separator$prefix$n $name")
            return TextRange.from(offset + separator.length + prefix.length, n.toString().length)
        }
    }

    /** Adds `N S` as the first item after `but`, or as the last one when there is no `but`. */
    data class AddFirstItem(val n: Int, val name: String, val prefix: String = "") : AlloyScopeEdit {
        override fun apply(scope: AlloyScopeText, document: Document): TextRange {
            val first = scope.items.firstOrNull()
            if (scope.isOverallOnly || first == null) return AddItem(n, name, prefix).apply(scope, document)
            val offset = scope.itemRange(first).startOffset
            document.insertString(offset, "$prefix$n $name, ")
            return TextRange.from(offset + prefix.length, n.toString().length)
        }
    }

    /** Writes `exactly` before the [index]th item. */
    data class MakeExact(val index: Int) : AlloyScopeEdit {
        override fun apply(scope: AlloyScopeText, document: Document): TextRange? {
            val item = scope.items.getOrNull(index) ?: return null
            document.insertString(scope.itemRange(item).startOffset, "exactly ")
            return null
        }
    }

    /** Sets the number of the `seq` item, or adds one. */
    data class SetSeq(val n: Int) : AlloyScopeEdit {
        override fun apply(scope: AlloyScopeText, document: Document): TextRange {
            val number = scope.seqItem?.firstOrNull { it.node.elementType == AlloyTypes.NUMBER }
                ?: return AddItem(n, "seq").apply(scope, document)
            document.replaceString(number.textRange.startOffset, number.textRange.endOffset, n.toString())
            return TextRange.from(number.textRange.startOffset, n.toString().length)
        }
    }

    /** Sets the number of the `int` item, or adds one; a command without `for` gets ` for N int`. */
    data class SetBitwidth(val n: Int) : AlloyScopeEdit {
        override fun apply(scope: AlloyScopeText, document: Document): TextRange {
            val number = scope.intItem?.firstOrNull { it.node.elementType == AlloyTypes.NUMBER }
                ?: return AddItem(n, "int").apply(scope, document)
            document.replaceString(number.textRange.startOffset, number.textRange.endOffset, n.toString())
            return TextRange.from(number.textRange.startOffset, n.toString().length)
        }

        override fun applyWithoutScope(end: Int, document: Document): TextRange {
            document.insertString(end, " for $n int")
            return TextRange.from(end + " for ".length, n.toString().length)
        }
    }
}

/**
 * Applies [edit] to the scope of the [part]th command of [decl], and of each of [others] (other commands, as
 * their `=>` chain and part), found again in the PSI when applied. When there is only one, the number it writes
 * is then selected by a template, for the user to change it at once.
 */
class AlloyScopeFix(
    decl: AlloyCommandDecl,
    private val part: Int,
    // Plain data, which the intention preview can use as it is
    @FileModifier.SafeFieldForPreview private val edit: AlloyScopeEdit,
    private val text: String,
    private val family: String,
    others: List<Pair<AlloyCommandDecl, Int>> = emptyList(),
) : LocalQuickFixAndIntentionActionOnPsiElement(decl) {
    // The preview finds them again in its copy of the file
    @FileModifier.SafeFieldForPreview
    private val others = others.map { (decl, part) -> SmartPointerManager.createPointer(decl) to part }

    override fun getText(): String = text

    override fun getFamilyName(): String = family

    override fun invoke(project: Project, file: PsiFile, editor: Editor?, startElement: PsiElement, endElement: PsiElement) {
        val decl = startElement as? AlloyCommandDecl ?: return
        val documents = PsiDocumentManager.getInstance(project)
        // Not PsiDocumentManager.getDocument, which has no document for the copy an intention preview edits
        val document = file.viewProvider.document ?: return
        // In an intention preview, [file] is a copy
        val targets = listOf(decl to part) + others.mapNotNull { (pointer, part) ->
            val other = pointer.element ?: return@mapNotNull null
            (if (other.containingFile == file) other else PsiTreeUtil.findSameElementInCopy(other, file)) to part
        }
        // Each edit is placed before it is applied, last one first, so that none moves the place of the next
        val edits = targets.mapNotNull { (decl, part) ->
            val scope = AlloyScopeText.of(decl, part)
            when {
                scope != null -> scope.range.startOffset to { edit.apply(scope, document) }
                else -> AlloyScopeText.endOf(decl, part)?.let { end -> end to { edit.applyWithoutScope(end, document) } }
            }
        }.sortedByDescending { it.first }
        val numbers = edits.map { it.second() }
        documents.commitDocument(document)
        val number = numbers.singleOrNull() ?: return
        if (editor == null || IntentionPreviewUtils.isIntentionPreviewActive()) return
        val container = PsiTreeUtil.getParentOfType(file.findElementAt(number.startOffset), AlloyCommandDecl::class.java) ?: return
        val builder = TemplateBuilderImpl(container)
        builder.replaceRange(number.shiftLeft(container.textRange.startOffset), document.getText(number))
        builder.run(editor, true)
    }
}
