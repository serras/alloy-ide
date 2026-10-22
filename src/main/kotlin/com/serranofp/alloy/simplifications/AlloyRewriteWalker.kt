package com.serranofp.alloy.simplifications

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.elementType
import com.serranofp.alloy.psi.AlloyMacroDecl
import com.serranofp.alloy.psi.AlloyTypes
import edu.mit.csail.sdg.alloy4.Pos
import edu.mit.csail.sdg.ast.Expr
import edu.mit.csail.sdg.ast.ExprBinary
import edu.mit.csail.sdg.ast.ExprCall
import edu.mit.csail.sdg.ast.ExprITE
import edu.mit.csail.sdg.ast.ExprLet
import edu.mit.csail.sdg.ast.ExprList
import edu.mit.csail.sdg.ast.ExprQt
import edu.mit.csail.sdg.ast.ExprUnary
import edu.mit.csail.sdg.parser.CompModule

/** What a rule sees of the node it matches: the [paragraph] it is written in and its [position] under its parent. */
class AlloyRewriteContext internal constructor(
    val source: AlloySourceText,
    val paragraph: PsiElement,
    val position: AlloyPosition,
    private val walker: AlloyRewriteWalker,
) {
    fun strip(node: Expr): Expr = source.strip(node)

    /** Whether [node] is written in [paragraph] (see [AlloySourceText.isWritten]). */
    fun written(node: Expr): Boolean = source.isWritten(node, paragraph)

    /** The text of [node] in [paragraph], or null if it is not there. */
    fun source(node: Expr): AlloySourceExpr? =
        source.source(source.strip(node))?.takeIf { source.inParagraph(it.range, paragraph) }

    /** The text of [node] as a piece of a replacement, which drops the tokens of [uses]. */
    fun slice(node: Expr, uses: List<Expr> = emptyList()): AlloyPiece.Slice? {
        val e = source.strip(node)
        val text = source(e) ?: return null
        // Block lines are no expression outside their braces
        val lines = e is ExprList && e.op == ExprList.Op.AND && e.pos == Pos.UNKNOWN
        val bare = text.bare?.takeIf { !lines }?.let { AlloyPiece.Slice(text, AlloyPrecedence.level(e, source), AlloyPrecedence.isBind(e), opens(e), uses, it) }
        if (!text.parenthesized) return bare
        return AlloyPiece.Slice(text, AlloyPrecedence.ATOM, isBind = false, endsOpen = false, uses, bare = bare)
    }

    /**
     * The declarations of [quantifier] as written, from after its keyword to before its `|`, such as
     * `disj x, y: A`; null if it has a block as body.
     */
    fun decls(quantifier: ExprQt): TextRange? {
        if (!source.inFile(quantifier.pos)) return null
        val keyword = source.tokenAt(source.range(quantifier.pos).startOffset) ?: return null
        val body = source(quantifier.sub) ?: return null
        val bar = source.tokenBefore(body.range.startOffset)?.takeIf { it.elementType == AlloyTypes.BAR } ?: return null
        val first = source.tokenAfter(keyword.textRange.endOffset) ?: return null
        val last = source.tokenBefore(bar.textRange.startOffset) ?: return null
        return TextRange(first.textRange.startOffset, last.textRange.endOffset).takeIf { !it.isEmpty && source.inParagraph(it, paragraph) }
    }

    /** The rewrite that [rule] makes of [node], the node this context is for, ready to apply. */
    fun rewrite(rule: AlloyRewriteRule, node: Expr): AlloyRewriteResult? =
        rule.match(node, this)?.let { walker.finish(it, this) }

    /** Whether [rewrite], of the node this context is for, can be applied (see [AlloyRewriteWalker.finish]). */
    internal fun applicable(rewrite: AlloyRewrite): Boolean = walker.finish(rewrite, this) != null

    /** Where operands [from] to [to] of [list], the matched node, sit together. */
    fun operandsPosition(list: ExprList, from: Int, to: Int): AlloyPosition = walker.listPosition(list, from, to, position)

    /** Whether [node] as written ends in a quantifier or `let` without parentheses, which would extend over what follows it. */
    private fun endsOpen(node: Expr): Boolean {
        val e = source.strip(node)
        val text = source.source(e) ?: return true
        return !text.parenthesized && opens(e)
    }

    /**
     * Whether [e], stripped, without the parentheses around it, ends in a quantifier or `let`. One whose body
     * is a block still does: the grammar only allows a quantifier as the last operand of an operator.
     */
    private fun opens(e: Expr): Boolean = when (e) {
        is ExprQt -> e.op != ExprQt.Op.COMPREHENSION
        is ExprLet, is ExprITE -> true
        is ExprUnary -> e.op != ExprUnary.Op.PRIME && e.op != ExprUnary.Op.NOOP && endsOpen(e.sub)
        is ExprBinary -> (e.op != ExprBinary.Op.JOIN || walker.isDotJoin(e)) && endsOpen(e.right)
        is ExprList -> (e.op == ExprList.Op.AND || e.op == ExprList.Op.OR) && e.args.isNotEmpty() && endsOpen(e.args.last())
        else -> false
    }
}

/**
 * Finds the rewrites of [rules] in the formulas and expressions written in a file: its facts, assertions,
 * predicates and functions (the bodies of anonymous commands among them) and signature facts. It walks the
 * analyzer's nodes, looking through `NOOP`s and casts, and keeps where each node sits under its parent. It
 * does not descend into a node that a rule rewrites, so nested rewrites yield the outermost one only. Nodes
 * reached through a macro expansion are not rewritten, the arguments of the macro call included.
 */
class AlloyRewriteWalker(private val source: AlloySourceText, private val rules: List<AlloyRewriteRule>) {
    fun walk(module: CompModule): List<AlloyRewriteResult> {
        val results = ArrayList<AlloyRewriteResult>()
        for (root in roots(module)) {
            if (!source.inFile(root.pos)) continue
            val paragraph = source.paragraph(source.range(root.pos)) ?: continue
            if (paragraph is AlloyMacroDecl) continue
            visit(root, AlloyPosition.TOP, paragraph, foreign = false, results)
        }
        return results
    }

    private fun roots(module: CompModule): List<Expr> = buildList {
        module.allFacts.mapTo(this) { it.b }
        module.allAssertions.mapTo(this) { it.expr }
        module.allFunc.mapTo(this) { it.body }
        module.allSigs.flatMapTo(this) { it.facts }
    }

    private fun visit(node: Expr, position: AlloyPosition, paragraph: PsiElement, foreign: Boolean, results: MutableList<AlloyRewriteResult>) {
        // Every node is visited, and the ranges of its operands are scanned: large models take a while
        ProgressManager.checkCanceled()
        // A node of a macro's body, or of another file: what is under it is not written here
        val elsewhere = foreign || (node.pos != Pos.UNKNOWN && !(source.inFile(node.pos) && paragraph.textRange.contains(source.range(node.pos))))
        if (node is ExprUnary && node.op in TRANSPARENT) {
            val inner = if (source.isBlock(node)) AlloyPosition.TOP else position
            visit(node.sub, inner, paragraph, elsewhere, results)
            return
        }
        if (!elsewhere) {
            val context = AlloyRewriteContext(source, paragraph, position, this)
            for (rule in rules) {
                val rewrite = rule.match(node, context) ?: continue
                val result = finish(rewrite, context) ?: continue
                results += result
                // What the rewrite replaces is not looked into; the other operands of a list still are, and so
                // is an operand with no text of its own, such as the inner `let` of `let a = x, b = y | …`,
                // whose rewrites outside the replaced text are kept
                for ((operand, operandPosition) in operands(node, position)) {
                    val range = source.source(source.strip(operand))?.range
                    if (range != null && range.intersectsStrict(result.range)) continue
                    val from = results.size
                    visit(operand, operandPosition, paragraph, elsewhere, results)
                    if (range == null) results.subList(from, results.size).removeAll { it.range.intersectsStrict(result.range) }
                }
                return
            }
        }
        for ((operand, operandPosition) in operands(node, position)) {
            visit(operand, operandPosition, paragraph, elsewhere, results)
        }
    }

    /**
     * [rewrite] rendered, or null if it drops more than operator tokens: every node it uses must be written,
     * and the text it replaces may hold, outside the slices it keeps and the operands it discards, only the
     * tokens of those nodes and parentheses. Nothing it drops may be a comment.
     */
    internal fun finish(rewrite: AlloyRewrite, context: AlloyRewriteContext): AlloyRewriteResult? {
        val uses = rewrite.uses + rewrite.replacement.allUses()
        if (!uses.all(context::written)) return null
        val range = rewrite.range
        if (!source.inParagraph(range, context.paragraph)) return null
        val kept = rewrite.replacement.keptRanges().sortedBy { it.startOffset }
        if (kept.any { !range.contains(it) }) return null
        if (kept.zipWithNext().any { (a, b) -> a.endOffset > b.startOffset }) return null
        val discarded = rewrite.discards.map { context.source(it)?.range ?: return null }
        if (discarded.any { !range.contains(it) }) return null
        val dropped = source.tokens(range).filter { token -> kept.none { it.contains(token.textRange) } }
        if (dropped.any { it is PsiComment }) return null
        val allowed = TokenSet.orSet(PARENTHESES, *uses.map { source.operatorTokens(it) }.toTypedArray())
        val operators = dropped.filter { token -> discarded.none { it.contains(token.textRange) } }
        if (operators.any { it.elementType !in allowed }) return null
        // The `!` of `!=` or `!in` is no logical operator
        val types = operators.filter { !(it.elementType == AlloyTypes.EXCL && source.tokenAfter(it.textRange.endOffset)?.elementType in COMPARISONS) }
            .map { it.elementType }
        val symbolic = types.any { it in SYMBOLIC } && types.none { it in KEYWORDS }
        val renderer = AlloyRewriteRenderer(symbolic)
        val text = renderer.renderReplacement(rewrite.replacement, rewrite.position ?: context.position)
        // The name shows the replacement without the parentheses that its place needs
        val name = rewrite.fixName(renderer.renderReplacement(rewrite.replacement, AlloyPosition.TOP))
        return AlloyRewriteResult(context.paragraph, range, text, rewrite.message, name)
    }

    /** The operands of [node], at [position], and where each of them sits. */
    private fun operands(node: Expr, position: AlloyPosition): List<Pair<Expr, AlloyPosition>> {
        // Inside parentheses, the last operand may extend to the closing one
        val inside = source.source(node)?.parenthesized == true || position.rightOpen
        fun at(spec: AlloyOperandSpec) = AlloyPosition(spec.level, spec.bindAllowed, spec.bounded || (spec.rightmost && inside))
        val conservative = at(AlloyOperandSpec.CONSERVATIVE)
        val body = at(AlloyOperandSpec(AlloyPrecedence.SEQ, bindAllowed = true, rightmost = true))
        return when (node) {
            is ExprUnary -> listOf(node.sub to (AlloyOperator.of(node)?.let { at(it.operand(0, 1)) } ?: conservative))
            is ExprBinary -> when {
                node.op == ExprBinary.Op.JOIN && isDotJoin(node) ->
                    listOf(node.left to at(AlloyOperator.JOIN.operand(0, 2)), node.right to at(AlloyOperator.JOIN.operand(1, 2)))
                // `a[b]` is `b.a`
                node.op == ExprBinary.Op.JOIN ->
                    listOf(node.left to at(AlloyOperandSpec.BOUNDED), node.right to at(AlloyOperandSpec(AlloyPrecedence.BRACKET, false, rightmost = false)))
                else -> AlloyOperator.of(node)?.let { listOf(node.left to at(it.operand(0, 2)), node.right to at(it.operand(1, 2))) }
                    ?: listOf(node.left to conservative, node.right to conservative)
            }
            is ExprList -> when (node.op) {
                ExprList.Op.AND, ExprList.Op.OR -> node.args.indices.map { node.args[it] to listPosition(node, it, it, position) }
                else -> node.args.map { it to at(AlloyOperandSpec.BOUNDED) }
            }
            is ExprQt -> node.decls.map { it.expr to conservative } +
                (node.sub to if (node.op == ExprQt.Op.COMPREHENSION) at(AlloyOperandSpec.BOUNDED) else body)
            is ExprLet -> listOf(node.expr to conservative, node.sub to body)
            is ExprITE -> listOf(
                node.cond to at(AlloyOperandSpec(AlloyPrecedence.AND, false, rightmost = false)),
                node.left to at(AlloyOperandSpec(AlloyPrecedence.AND, false, rightmost = false)),
                node.right to at(AlloyOperandSpec(AlloyPrecedence.IMPLIES, true, rightmost = true)),
            )
            is ExprCall -> {
                // `e.f` is `f[e]`: an argument written before the name is the left of a `.`, not in brackets
                val name = node.pos.takeIf { source.inFile(it) }?.let { source.range(it).startOffset }
                node.args.map { arg ->
                    val start = source.source(source.strip(arg))?.range?.startOffset
                    val bracketed = name != null && start != null && start > name
                    arg to at(if (bracketed) AlloyOperandSpec.BOUNDED else AlloyOperator.JOIN.operand(0, 2))
                }
            }
            else -> emptyList()
        }
    }

    /**
     * Where operands [from] to [to] of an `and` or `or` [list] at [position] sit together. The analyzer
     * flattens nested lists and block lines into one list, so this looks at the tokens around them: an
     * operand between block lines, braces or parentheses needs no parentheses, one next to an `and` does.
     * `p ; q` is the list of `p` and `after q`, at the `;`: what comes before a `;` is ended by it, but may
     * not end open, since a quantifier's body takes in the `;` that follows it (`BAR ExprNoSeq TRCSEQ Expr`).
     */
    internal fun listPosition(list: ExprList, from: Int, to: Int, position: AlloyPosition): AlloyPosition {
        val level = AlloyOperator.of(list)?.level ?: return AlloyPosition(AlloyPrecedence.ATOM, false, false)
        val separators = source.operatorTokens(list)
        val first = source.source(source.strip(list.args[from]))
        val last = source.source(source.strip(list.args[to]))
        if (first == null || last == null) return AlloyPosition(level, bindAllowed = false, rightOpen = false)
        val before = source.tokenBefore(first.range.startOffset)?.elementType
        val after = source.tokenAfter(last.range.endOffset)?.elementType
        val lines = list.op == ExprList.Op.AND
        val isLast = to == list.args.size - 1
        val sequenced = after == AlloyTypes.SEMICOLON
        val closedRight = after == null || after in AlloySourceText.CLOSERS
        val lineBefore = lines && from > 0 && before !in separators
        val lineAfter = lines && !isLast && after !in separators && !closedRight && !sequenced
        val freeLeft = before == null || before in AlloySourceText.OPENERS || lineBefore
        val freeRight = closedRight || lineAfter
        val rightOpen = freeRight || (isLast && after !in separators && !sequenced && (source.source(list)?.parenthesized == true || position.rightOpen))
        val operandLevel = when {
            !freeLeft -> level
            freeRight -> AlloyPrecedence.SEQ
            // Anything but another `;`, which would group to the right
            sequenced -> AlloyPrecedence.BIND
            else -> level
        }
        return AlloyPosition(operandLevel, bindAllowed = true, rightOpen = rightOpen)
    }

    /** Whether [join] is written `a.b`, rather than `b[a]` or as the implicit `this.f` of a signature fact. */
    internal fun isDotJoin(join: ExprBinary): Boolean =
        source.inFile(join.pos) && source.tokenAt(source.range(join.pos).startOffset)?.elementType == AlloyTypes.DOT

    private companion object {
        val TRANSPARENT = setOf(ExprUnary.Op.NOOP, ExprUnary.Op.CAST2INT, ExprUnary.Op.CAST2SIGINT)

        val PARENTHESES: TokenSet = TokenSet.create(AlloyTypes.LPAREN, AlloyTypes.RPAREN)

        val SYMBOLIC: TokenSet = TokenSet.create(AlloyTypes.EXCL, AlloyTypes.AMPAMP, AlloyTypes.BARBAR, AlloyTypes.IMPLIES_ARROW, AlloyTypes.IFF_ARROW)

        val COMPARISONS: TokenSet = TokenSet.create(
            AlloyTypes.EQUALS, AlloyTypes.IN, AlloyTypes.LT, AlloyTypes.GT, AlloyTypes.EQLT, AlloyTypes.LTE, AlloyTypes.GTE,
        )

        val KEYWORDS: TokenSet = TokenSet.create(AlloyTypes.NOT, AlloyTypes.AND, AlloyTypes.OR, AlloyTypes.IMPLIES, AlloyTypes.IFF)
    }
}
