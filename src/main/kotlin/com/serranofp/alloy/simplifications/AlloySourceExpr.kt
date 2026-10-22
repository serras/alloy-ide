package com.serranofp.alloy.simplifications

import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import com.serranofp.alloy.psi.AlloyFile
import com.serranofp.alloy.psi.AlloyMacroDecl
import com.serranofp.alloy.psi.AlloyTypes
import com.serranofp.alloy.semantics.AlloyPositions
import edu.mit.csail.sdg.alloy4.Pos
import edu.mit.csail.sdg.ast.Expr
import edu.mit.csail.sdg.ast.ExprBinary
import edu.mit.csail.sdg.ast.ExprCall
import edu.mit.csail.sdg.ast.ExprConstant
import edu.mit.csail.sdg.ast.ExprITE
import edu.mit.csail.sdg.ast.ExprLet
import edu.mit.csail.sdg.ast.ExprList
import edu.mit.csail.sdg.ast.ExprQt
import edu.mit.csail.sdg.ast.ExprUnary
import edu.mit.csail.sdg.ast.ExprVar
import edu.mit.csail.sdg.ast.Sig
import java.util.IdentityHashMap

/**
 * The text of the analyzer's [node] in the editor: its [range], widened over the parentheses written around
 * it, which makes it [parenthesized].
 */
class AlloySourceExpr(val node: Expr, val range: TextRange, val parenthesized: Boolean, val text: String)

/**
 * How the analyzer's nodes of [file] map to its text. Parentheses leave no trace in the analyzer's AST, and a
 * node's `span()` may stop short of a closing bracket, so ranges are balanced and widened over the PSI tokens.
 */
class AlloySourceText(val file: AlloyFile, val document: Document) {
    /** The analyzer's name for [file]. */
    val filename: String = AlloyPositions.filename(file.virtualFile)

    private val sources = IdentityHashMap<Expr, AlloySourceExpr?>()

    /** Whether [pos] is a known position in [file], and not in a module it opens. */
    fun inFile(pos: Pos?): Boolean = pos != null && pos != Pos.UNKNOWN && pos.filename == filename

    fun range(pos: Pos): TextRange = AlloyPositions.textRange(pos, document)

    /** The top-level paragraph of [file] that contains [range], if one does. */
    fun paragraph(range: TextRange): PsiElement? {
        val paragraph = PsiTreeUtil.findFirstParent(file.findElementAt(range.startOffset), false) { it.parent is PsiFile }
        return paragraph?.takeIf { it.textRange.contains(range) && it !is PsiWhiteSpace && it !is PsiComment }
    }

    /** Whether [range] lies inside [paragraph], which is no macro declaration. */
    fun inParagraph(range: TextRange, paragraph: PsiElement): Boolean =
        paragraph !is AlloyMacroDecl && paragraph.textRange.contains(range)

    /**
     * [node] with what has no text of its own taken off: casts and `NOOP` wrappers, except those of a block
     * `{ … }`, which keep its braces, and of a name, whose position is the reference.
     */
    fun strip(node: Expr): Expr {
        var e = node
        while (e is ExprUnary) {
            when (e.op) {
                ExprUnary.Op.CAST2INT, ExprUnary.Op.CAST2SIGINT -> e = e.sub
                ExprUnary.Op.NOOP -> if (isBlock(e) || isName(e)) return e else e = e.sub
                else -> return e
            }
        }
        return e
    }

    /** A `NOOP` at the `{` of a block. */
    fun isBlock(node: ExprUnary): Boolean =
        node.op == ExprUnary.Op.NOOP && inFile(node.pos) && tokenAt(range(node.pos).startOffset)?.elementType == AlloyTypes.LBRACE

    private fun isName(node: ExprUnary): Boolean {
        val sub = node.sub.deNOP()
        return node.op == ExprUnary.Op.NOOP && node.pos != Pos.UNKNOWN && (sub is Sig || sub is Sig.Field || sub is ExprVar || sub is ExprConstant)
    }

    /** The text of [node], widened over parentheses; null if it cannot be mapped to [file]. */
    fun source(node: Expr): AlloySourceExpr? {
        if (sources.containsKey(node)) return sources[node]
        val own = ownRange(node)
        val source = own?.let {
            val widened = widen(it)
            AlloySourceExpr(node, widened, widened != it, document.getText(widened))
        }
        sources[node] = source
        return source
    }

    /** The text of [node] without the parentheses around it. */
    private fun ownRange(node: Expr): TextRange? {
        val span = if (node is ExprUnary && (isBlock(node) || isName(node))) node.pos else node.span()
        if (!inFile(span)) return null
        var range = range(span)
        if (range.isEmpty) return null
        if (node is ExprLet) {
            // Its span starts at the bound name; a `let` with several names nests one node per name
            val let = tokenBefore(range.startOffset)?.takeIf { it.elementType == AlloyTypes.LET } ?: return null
            range = TextRange(let.textRange.startOffset, range.endOffset)
        }
        return balance(range)
    }

    /**
     * [range] extended until its brackets are balanced: `not (a` is `not (a)`, and the `or` of `(a) or (b)`,
     * whose span is `a) or (b`, is `(a) or (b)`. Null if the brackets do not match.
     */
    private fun balance(range: TextRange): TextRange? {
        val unclosed = ArrayList<IElementType>()
        val unopened = ArrayList<IElementType>()
        for (token in tokens(range)) {
            when (val type = token.elementType ?: continue) {
                in OPENERS -> unclosed += type
                in CLOSERS -> when {
                    unclosed.isEmpty() -> unopened += type
                    closerOf(unclosed.last()) == type -> unclosed.removeLast()
                    else -> return null
                }
            }
        }
        var start = range.startOffset
        var end = range.endOffset
        if (unopened.isNotEmpty()) {
            // The nearest unmatched opener before the range matches the first unmatched closer in it
            var next = 0
            var nested = 0
            var token = previousToken(start)
            while (next < unopened.size) {
                when (val type = token?.elementType ?: return null) {
                    in CLOSERS -> nested++
                    in OPENERS if nested > 0 -> nested--
                    in OPENERS -> {
                        if (closerOf(type) != unopened[next]) return null
                        next++
                        start = token.textRange.startOffset
                    }
                }
                token = previousToken(token.textRange.startOffset)
            }
        }
        if (unclosed.isNotEmpty()) {
            var next = unclosed.size - 1
            var nested = 0
            var token = nextToken(end)
            while (next >= 0) {
                when (val type = token?.elementType ?: return null) {
                    in OPENERS -> nested++
                    in CLOSERS if nested > 0 -> nested--
                    in CLOSERS -> {
                        if (closerOf(unclosed[next]) != type) return null
                        next--
                        end = token.textRange.endOffset
                    }
                }
                token = nextToken(token.textRange.endOffset)
            }
        }
        return TextRange(start, end)
    }

    /** [range] widened over the pairs of parentheses written right around it. */
    private fun widen(range: TextRange): TextRange {
        var result = range
        while (true) {
            val before = tokenBefore(result.startOffset) ?: return result
            val after = tokenAfter(result.endOffset) ?: return result
            // A matching pair, since the text between them is balanced
            if (before.elementType != AlloyTypes.LPAREN || after.elementType != AlloyTypes.RPAREN) return result
            result = TextRange(before.textRange.startOffset, after.textRange.endOffset)
        }
    }

    /**
     * Whether [node] is written in [paragraph]: the token at its position is its operator, as `not` or `!` is
     * for a negation, and its text lies in [paragraph], which is no macro declaration. Desugared nodes, such
     * as the `after` of `;`, and macro expansions fail this.
     */
    fun isWritten(node: Expr, paragraph: PsiElement): Boolean {
        if (!inFile(node.pos)) {
            // The `and` of block lines has no position; its lines are checked on their own
            return node is ExprList && node.op == ExprList.Op.AND && node.pos == Pos.UNKNOWN
        }
        val token = tokenAt(range(node.pos).startOffset)?.elementType ?: return false
        if (token !in operatorTokens(node, written = true)) return false
        val source = source(node) ?: return false
        return inParagraph(source.range, paragraph)
    }

    /**
     * The tokens of [node]'s operator. When it is [written], only the token at its position: the `!` of `!=`
     * or the `not` of `not in`; otherwise every token of the operator, which a rewrite may drop.
     */
    fun operatorTokens(node: Expr, written: Boolean = false): TokenSet {
        val tokens: TokenSet? = when (node) {
            // `none` is a signature, referred to by a `NOOP`
            is ExprUnary -> if (node.op == ExprUnary.Op.NOOP && node.sub.deNOP() == Sig.NONE) set(AlloyTypes.NONE) else UNARY_TOKENS[node.op]
            is ExprBinary -> BINARY_TOKENS[node.op]?.let { if (written) it.first else it.second }
            is ExprList -> when (node.op) {
                ExprList.Op.AND -> TokenSet.create(AlloyTypes.AND, AlloyTypes.AMPAMP)
                ExprList.Op.OR -> TokenSet.create(AlloyTypes.OR, AlloyTypes.BARBAR)
                else -> null
            }
            is ExprQt -> when (node.op) {
                ExprQt.Op.COMPREHENSION -> TokenSet.create(AlloyTypes.LBRACE).takeIf { written }
                else -> QUANTIFIER_TOKENS[node.op]?.let { if (written) it else TokenSet.orSet(it, TokenSet.create(AlloyTypes.DISJ, AlloyTypes.COLON, AlloyTypes.COMMA, AlloyTypes.BAR)) }
            }
            is ExprITE -> TokenSet.create(AlloyTypes.IMPLIES, AlloyTypes.IMPLIES_ARROW).let {
                if (written) it else TokenSet.orSet(it, TokenSet.create(AlloyTypes.ELSE))
            }
            is ExprLet -> TokenSet.create(AlloyTypes.EQUALS).let { if (written) it else TokenSet.orSet(it, TokenSet.create(AlloyTypes.LET, AlloyTypes.BAR)) }
            is ExprCall -> TokenSet.create(AlloyTypes.IDENTIFIER).takeIf { written }
            is ExprConstant -> CONSTANT_TOKENS[node.op]
            else -> null
        }
        return tokens ?: TokenSet.EMPTY
    }

    /** The leaf at [offset]. */
    fun tokenAt(offset: Int): PsiElement? = file.findElementAt(offset)

    /** The tokens that start inside [range], comments included, and without whitespace. */
    fun tokens(range: TextRange): List<PsiElement> {
        val tokens = ArrayList<PsiElement>()
        var leaf = tokenAt(range.startOffset)
        while (leaf != null && leaf.textRange.startOffset < range.endOffset) {
            if (leaf !is PsiWhiteSpace && leaf.textRange.startOffset >= range.startOffset) tokens += leaf
            leaf = PsiTreeUtil.nextLeaf(leaf)
        }
        return tokens
    }

    /** The last token before [offset], skipping whitespace and comments. */
    fun tokenBefore(offset: Int): PsiElement? = previousToken(offset)

    /** The first token from [offset] on, skipping whitespace and comments. */
    fun tokenAfter(offset: Int): PsiElement? = nextToken(offset)

    private fun previousToken(offset: Int): PsiElement? {
        if (offset <= 0) return null
        var leaf = tokenAt(offset - 1)
        while (leaf != null && (leaf is PsiWhiteSpace || leaf is PsiComment)) leaf = PsiTreeUtil.prevLeaf(leaf)
        return leaf
    }

    private fun nextToken(offset: Int): PsiElement? {
        var leaf = tokenAt(offset)
        while (leaf != null && (leaf is PsiWhiteSpace || leaf is PsiComment)) leaf = PsiTreeUtil.nextLeaf(leaf)
        return leaf
    }

    companion object {
        val OPENERS: TokenSet = TokenSet.create(AlloyTypes.LPAREN, AlloyTypes.LBRACKET, AlloyTypes.LBRACE)
        val CLOSERS: TokenSet = TokenSet.create(AlloyTypes.RPAREN, AlloyTypes.RBRACKET, AlloyTypes.RBRACE)

        private fun closerOf(opener: IElementType): IElementType = when (opener) {
            AlloyTypes.LPAREN -> AlloyTypes.RPAREN
            AlloyTypes.LBRACKET -> AlloyTypes.RBRACKET
            else -> AlloyTypes.RBRACE
        }

        private fun set(vararg types: IElementType) = TokenSet.create(*types)

        private val NEGATED = set(AlloyTypes.EXCL, AlloyTypes.NOT)

        private val UNARY_TOKENS = mapOf(
            ExprUnary.Op.NOT to NEGATED,
            ExprUnary.Op.AFTER to set(AlloyTypes.AFTER),
            ExprUnary.Op.ALWAYS to set(AlloyTypes.ALWAYS),
            ExprUnary.Op.EVENTUALLY to set(AlloyTypes.EVENTUALLY),
            ExprUnary.Op.BEFORE to set(AlloyTypes.BEFORE),
            ExprUnary.Op.HISTORICALLY to set(AlloyTypes.HISTORICALLY),
            ExprUnary.Op.ONCE to set(AlloyTypes.ONCE),
            ExprUnary.Op.NO to set(AlloyTypes.NO),
            ExprUnary.Op.SOME to set(AlloyTypes.SOME),
            ExprUnary.Op.LONE to set(AlloyTypes.LONE),
            ExprUnary.Op.ONE to set(AlloyTypes.ONE),
            // The `one` of a declaration `x: one A`; an implicit one is at the bound
            ExprUnary.Op.ONEOF to set(AlloyTypes.ONE),
            ExprUnary.Op.TRANSPOSE to set(AlloyTypes.TILDE),
            ExprUnary.Op.CLOSURE to set(AlloyTypes.CARET),
            ExprUnary.Op.RCLOSURE to set(AlloyTypes.STAR),
            ExprUnary.Op.CARDINALITY to set(AlloyTypes.HASH),
            ExprUnary.Op.PRIME to set(AlloyTypes.PRIME),
        )

        /** The token at the operator's position, and all of its tokens. */
        private val BINARY_TOKENS: Map<ExprBinary.Op, Pair<TokenSet, TokenSet>> = buildMap {
            fun simple(op: ExprBinary.Op, vararg types: IElementType) = put(op, set(*types) to set(*types))
            fun negated(op: ExprBinary.Op, vararg types: IElementType) = put(op, NEGATED to TokenSet.orSet(NEGATED, set(*types)))
            simple(ExprBinary.Op.JOIN, AlloyTypes.DOT)
            simple(ExprBinary.Op.DOMAIN, AlloyTypes.DOMAIN)
            simple(ExprBinary.Op.RANGE, AlloyTypes.RANGE)
            simple(ExprBinary.Op.INTERSECT, AlloyTypes.AMPERSAND)
            simple(ExprBinary.Op.PLUSPLUS, AlloyTypes.PLUSPLUS)
            simple(ExprBinary.Op.PLUS, AlloyTypes.PLUS)
            simple(ExprBinary.Op.MINUS, AlloyTypes.MINUS)
            simple(ExprBinary.Op.ARROW, AlloyTypes.ARROW)
            simple(ExprBinary.Op.EQUALS, AlloyTypes.EQUALS)
            simple(ExprBinary.Op.IN, AlloyTypes.IN)
            simple(ExprBinary.Op.LT, AlloyTypes.LT)
            simple(ExprBinary.Op.LTE, AlloyTypes.EQLT, AlloyTypes.LTE)
            simple(ExprBinary.Op.GT, AlloyTypes.GT)
            simple(ExprBinary.Op.GTE, AlloyTypes.GTE)
            simple(ExprBinary.Op.IMPLIES, AlloyTypes.IMPLIES, AlloyTypes.IMPLIES_ARROW)
            simple(ExprBinary.Op.IFF, AlloyTypes.IFF, AlloyTypes.IFF_ARROW)
            simple(ExprBinary.Op.UNTIL, AlloyTypes.UNTIL)
            simple(ExprBinary.Op.RELEASES, AlloyTypes.RELEASES)
            simple(ExprBinary.Op.SINCE, AlloyTypes.SINCE)
            simple(ExprBinary.Op.TRIGGERED, AlloyTypes.TRIGGERED)
            simple(ExprBinary.Op.SHL, AlloyTypes.SHL)
            simple(ExprBinary.Op.SHA, AlloyTypes.SHA)
            simple(ExprBinary.Op.SHR, AlloyTypes.SHR)
            negated(ExprBinary.Op.NOT_EQUALS, AlloyTypes.EQUALS)
            negated(ExprBinary.Op.NOT_IN, AlloyTypes.IN)
            negated(ExprBinary.Op.NOT_LT, AlloyTypes.LT)
            negated(ExprBinary.Op.NOT_LTE, AlloyTypes.EQLT, AlloyTypes.LTE)
            negated(ExprBinary.Op.NOT_GT, AlloyTypes.GT)
            negated(ExprBinary.Op.NOT_GTE, AlloyTypes.GTE)
        }

        private val QUANTIFIER_TOKENS = mapOf(
            ExprQt.Op.ALL to set(AlloyTypes.ALL),
            ExprQt.Op.SOME to set(AlloyTypes.SOME),
            ExprQt.Op.NO to set(AlloyTypes.NO),
            ExprQt.Op.ONE to set(AlloyTypes.ONE),
            ExprQt.Op.LONE to set(AlloyTypes.LONE),
            ExprQt.Op.SUM to set(AlloyTypes.SUM),
        )

        private val CONSTANT_TOKENS = mapOf(
            ExprConstant.Op.NUMBER to set(AlloyTypes.NUMBER),
            ExprConstant.Op.IDEN to set(AlloyTypes.IDEN),
            ExprConstant.Op.EMPTYNESS to set(AlloyTypes.NONE),
        )
    }
}
