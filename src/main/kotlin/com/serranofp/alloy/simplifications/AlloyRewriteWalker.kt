package com.serranofp.alloy.simplifications

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.TokenSet
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

/**
 * What a rule sees of the node it matches: the [paragraph] it is written in, its [ancestors] (stripped, the
 * parent last) and its [position] under its parent.
 */
class AlloyRewriteContext internal constructor(
    val source: AlloySourceText,
    val paragraph: PsiElement,
    val ancestors: List<Expr>,
    val position: AlloyPosition,
    private val walker: AlloyRewriteWalker,
) {
    val parent: Expr? get() = ancestors.lastOrNull()

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
        if (text.parenthesized) return AlloyPiece.Slice(text, AlloyPrecedence.ATOM, isBind = false, endsOpen = false, uses)
        // Block lines are no expression outside their braces
        if (e is ExprList && e.op == ExprList.Op.AND && e.pos == Pos.UNKNOWN) return null
        return AlloyPiece.Slice(text, AlloyPrecedence.level(e, source), AlloyPrecedence.isBind(e), endsOpen(e), uses)
    }

    /**
     * The declarations of [quantifier] as written, from after its keyword to before its `|`, such as
     * `disj x, y: A`; null if it has a block as body.
     */
    fun decls(quantifier: ExprQt): TextRange? {
        if (!source.inFile(quantifier.pos)) return null
        val keyword = source.tokenAt(source.range(quantifier.pos).startOffset) ?: return null
        val body = source(quantifier.sub) ?: return null
        val bar = source.tokenBefore(body.range.startOffset)?.takeIf { it.node.elementType == AlloyTypes.BAR } ?: return null
        val first = source.tokenAfter(keyword.textRange.endOffset) ?: return null
        val last = source.tokenBefore(bar.textRange.startOffset) ?: return null
        return TextRange(first.textRange.startOffset, last.textRange.endOffset).takeIf { !it.isEmpty && source.inParagraph(it, paragraph) }
    }

    /** The rewrite that [rule] makes of [node], the node this context is for, ready to apply. */
    fun rewrite(rule: AlloyRewriteRule, node: Expr): AlloyRewriteResult? =
        rule.match(node, this)?.let { walker.finish(rule, node, it, this) }

    /** Where operands [from] to [to] of [list], the matched node, sit together. */
    fun operandsPosition(list: ExprList, from: Int, to: Int): AlloyPosition = walker.listPosition(list, from, to, position)

    /** Whether [node] as written ends in a quantifier or `let` without parentheses, which would extend over what follows it. */
    private fun endsOpen(node: Expr): Boolean {
        val e = source.strip(node)
        val text = source.source(e) ?: return true
        if (text.parenthesized) return false
        return when (e) {
            is ExprQt -> e.op != ExprQt.Op.COMPREHENSION
            is ExprLet, is ExprITE -> true
            is ExprUnary -> e.op != ExprUnary.Op.PRIME && e.op != ExprUnary.Op.NOOP && endsOpen(e.sub)
            is ExprBinary -> (e.op != ExprBinary.Op.JOIN || walker.isDotJoin(e)) && endsOpen(e.right)
            is ExprList -> (e.op == ExprList.Op.AND || e.op == ExprList.Op.OR) && e.args.isNotEmpty() && endsOpen(e.args.last())
            else -> false
        }
    }
}

/**
 * Finds the rewrites of [rules] in the formulas and expressions written in a file: its facts, assertions,
 * predicates and functions (the bodies of anonymous commands among them) and signature facts. It walks the
 * analyzer's nodes, looking through `NOOP`s and casts, and keeps the ancestors of each node and where it sits
 * under its parent. It does not descend into a node that a rule rewrites, so nested rewrites yield the
 * outermost one only. Nodes reached through a macro expansion are not rewritten, the arguments of the macro
 * call included.
 */
class AlloyRewriteWalker(private val source: AlloySourceText, private val rules: List<AlloyRewriteRule>) {
    fun walk(module: CompModule): List<AlloyRewriteResult> {
        val results = ArrayList<AlloyRewriteResult>()
        for (root in roots(module)) {
            if (!source.inFile(root.pos)) continue
            val paragraph = source.paragraph(source.range(root.pos)) ?: continue
            if (paragraph is AlloyMacroDecl) continue
            visit(root, AlloyPosition.TOP, emptyList(), paragraph, foreign = false, results)
        }
        return results
    }

    private fun roots(module: CompModule): List<Expr> =
        module.allFacts.map { it.b } +
            module.allAssertions.map { it.expr } +
            module.allFunc.map { it.body } +
            module.allSigs.flatMap { it.facts }

    private fun visit(node: Expr, position: AlloyPosition, ancestors: List<Expr>, paragraph: PsiElement, foreign: Boolean, results: MutableList<AlloyRewriteResult>) {
        // A node of a macro's body, or of another file: what is under it is not written here
        val elsewhere = foreign || (node.pos != Pos.UNKNOWN && !(source.inFile(node.pos) && paragraph.textRange.contains(source.range(node.pos))))
        if (node is ExprUnary && node.op in TRANSPARENT) {
            val inner = if (source.isBlock(node)) AlloyPosition.TOP else position
            visit(node.sub, inner, ancestors, paragraph, elsewhere, results)
            return
        }
        if (!elsewhere) {
            val context = AlloyRewriteContext(source, paragraph, ancestors, position, this)
            for (rule in rules) {
                val rewrite = rule.match(node, context) ?: continue
                val result = finish(rule, node, rewrite, context) ?: continue
                results += result
                // What the rewrite replaces is not looked into; the other operands of a list still are
                for ((operand, operandPosition) in operands(node, position)) {
                    val range = source.source(source.strip(operand))?.range ?: continue
                    if (!range.intersectsStrict(result.range)) visit(operand, operandPosition, ancestors + node, paragraph, elsewhere, results)
                }
                return
            }
        }
        for ((operand, operandPosition) in operands(node, position)) {
            visit(operand, operandPosition, ancestors + node, paragraph, elsewhere, results)
        }
    }

    /**
     * [rewrite] rendered, or null if it drops more than operator tokens: every node it uses must be written,
     * and the text it replaces may hold, outside the slices it keeps and the operands it discards, only the
     * tokens of those nodes and parentheses. Nothing it drops may be a comment.
     */
    internal fun finish(rule: AlloyRewriteRule, node: Expr, rewrite: AlloyRewrite, context: AlloyRewriteContext): AlloyRewriteResult? {
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
        if (operators.any { it.node.elementType !in allowed }) return null
        // The `!` of `!=` or `!in` is no logical operator
        val types = operators.filter { !(it.node.elementType == AlloyTypes.EXCL && source.tokenAfter(it.textRange.endOffset)?.node?.elementType in COMPARISONS) }
            .map { it.node.elementType }
        val symbolic = types.any { it in SYMBOLIC } && types.none { it in KEYWORDS }
        val renderer = AlloyRewriteRenderer(symbolic)
        val text = renderer.render(rewrite.replacement, rewrite.position ?: context.position)
        // The name shows the replacement without the parentheses that its place needs
        val name = rewrite.fixName(renderer.render(rewrite.replacement, AlloyPosition.TOP))
        return AlloyRewriteResult(rule, node, context.paragraph, range, text, rewrite.message, name)
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
            is ExprCall -> node.args.map { it to at(AlloyOperandSpec.BOUNDED) }
            else -> emptyList()
        }
    }

    /**
     * Where operands [from] to [to] of an `and` or `or` [list] at [position] sit together. The analyzer
     * flattens nested lists and block lines into one list, so this looks at the tokens around them: an
     * operand between block lines, braces or parentheses needs no parentheses, one next to an `and` does.
     */
    internal fun listPosition(list: ExprList, from: Int, to: Int, position: AlloyPosition): AlloyPosition {
        val level = AlloyOperator.of(list)?.level ?: return AlloyPosition(AlloyPrecedence.ATOM, false, false)
        val separators = source.operatorTokens(list)
        val first = source.source(source.strip(list.args[from]))
        val last = source.source(source.strip(list.args[to]))
        if (first == null || last == null) return AlloyPosition(level, bindAllowed = false, rightOpen = false)
        val before = source.tokenBefore(first.range.startOffset)?.node?.elementType
        val after = source.tokenAfter(last.range.endOffset)?.node?.elementType
        val lines = list.op == ExprList.Op.AND
        val isLast = to == list.args.size - 1
        val closedRight = after == null || after in AlloySourceText.CLOSERS
        val lineBefore = lines && from > 0 && before !in separators
        val lineAfter = lines && !isLast && after !in separators && !closedRight
        val freeLeft = before == null || before in AlloySourceText.OPENERS || lineBefore
        val freeRight = closedRight || lineAfter
        val rightOpen = freeRight || (isLast && after !in separators && (source.source(list)?.parenthesized == true || position.rightOpen))
        return AlloyPosition(if (freeLeft && freeRight) AlloyPrecedence.SEQ else level, bindAllowed = true, rightOpen = rightOpen)
    }

    /** Whether [join] is written `a.b`, rather than `b[a]` or as the implicit `this.f` of a signature fact. */
    internal fun isDotJoin(join: ExprBinary): Boolean =
        source.inFile(join.pos) && source.tokenAt(source.range(join.pos).startOffset)?.node?.elementType == AlloyTypes.DOT

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
