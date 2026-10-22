package com.serranofp.alloy.simplifications

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import edu.mit.csail.sdg.ast.Expr

/** The inspection or intention that a rule belongs to. */
data class AlloyRewriteFamily(val name: String) {
    companion object {
        val BOOLEAN = AlloyRewriteFamily("BOOLEAN")
        val CARDINALITY = AlloyRewriteFamily("CARDINALITY")
        val TEMPORAL = AlloyRewriteFamily("TEMPORAL")
        val QUANTIFIER = AlloyRewriteFamily("QUANTIFIER")
        val RELATIONAL = AlloyRewriteFamily("RELATIONAL")
    }
}

enum class AlloyRewriteKind {
    /** The result is simpler: an inspection reports it. */
    SIMPLIFICATION,

    /** The result is as simple: an intention offers it. */
    EQUIVALENCE,
}

/** A rewrite of the analyzer's nodes, found on a type-checked model. */
interface AlloyRewriteRule {
    val family: AlloyRewriteFamily
    val kind: AlloyRewriteKind

    /** The rewrite of [node], which is [AlloySourceText.strip]ped, or null if this rule does not apply to it. */
    fun match(node: Expr, context: AlloyRewriteContext): AlloyRewrite?
}

/**
 * A rewrite that replaces [range] by [replacement]. The operator tokens of [uses] and of the pieces'
 * [AlloyPiece.uses] are dropped: they must be written, and the text of [range] outside the replacement's
 * slices may only hold them, parentheses, whitespace and the text of the operands in [discards], such as the
 * second `r` of `r + r`. The replacement sits at [position], by default the matched node's. [fixName] is
 * given the replacement's text, without the parentheses its place needs.
 */
class AlloyRewrite(
    val range: TextRange,
    val replacement: AlloyPiece,
    val message: String,
    val uses: List<Expr> = emptyList(),
    val discards: List<Expr> = emptyList(),
    val position: AlloyPosition? = null,
    val fixName: (String) -> String,
)

/** A rewrite of [node] that can be applied: [range] of the file is to be replaced by [text]. */
class AlloyRewriteResult(
    val rule: AlloyRewriteRule,
    val node: Expr,
    val paragraph: PsiElement,
    val range: TextRange,
    val text: String,
    val message: String,
    val fixName: String,
)

/** A piece of a replacement, written as it is rendered by [AlloyRewriteRenderer]. */
sealed interface AlloyPiece {
    /** Nodes whose operator tokens this piece drops. */
    val uses: List<Expr>

    /**
     * The text of a node, kept as written, comments and line breaks included. Written in parentheses, it
     * keeps them and is an atom; otherwise it has the [level] of its top node, which may be a quantifier or
     * `let` ([isBind]) or end in one ([endsOpen]).
     */
    class Slice(val source: AlloySourceExpr, val level: Int, val isBind: Boolean, val endsOpen: Boolean, override val uses: List<Expr> = emptyList()) : AlloyPiece

    /** [operator] applied to [operands]. */
    class Op(val operator: AlloyOperator, val operands: List<AlloyPiece>, override val uses: List<Expr> = emptyList()) : AlloyPiece {
        constructor(operator: AlloyOperator, vararg operands: AlloyPiece, uses: List<Expr> = emptyList()) : this(operator, operands.toList(), uses)
    }

    /**
     * A quantifier, `[quantifier] [decls] | [body]`, whose declarations are the text of [declsRange] (with
     * `disj` added or dropped as [decls] says).
     */
    class Bind(val quantifier: String, val decls: String, val declsRange: TextRange, val body: AlloyPiece, override val uses: List<Expr> = emptyList()) : AlloyPiece

    /**
     * [piece] in parentheses, wherever it is: for a result that should read as the text it came from, such
     * as `not (all x: A | p)`, where the parentheses are not needed.
     */
    class Group(val piece: AlloyPiece, override val uses: List<Expr> = emptyList()) : AlloyPiece
}

/** The slices of [this] and of its operands. */
internal fun AlloyPiece.slices(): List<AlloyPiece.Slice> = when (this) {
    is AlloyPiece.Slice -> listOf(this)
    is AlloyPiece.Op -> operands.flatMap { it.slices() }
    is AlloyPiece.Bind -> body.slices()
    is AlloyPiece.Group -> piece.slices()
}

/** The ranges of the source text that [this] keeps. */
internal fun AlloyPiece.keptRanges(): List<TextRange> = when (this) {
    is AlloyPiece.Slice -> listOf(source.range)
    is AlloyPiece.Op -> operands.flatMap { it.keptRanges() }
    is AlloyPiece.Bind -> listOf(declsRange) + body.keptRanges()
    is AlloyPiece.Group -> piece.keptRanges()
}

/** The nodes whose tokens [this] and its operands drop. */
internal fun AlloyPiece.allUses(): List<Expr> = uses + when (this) {
    is AlloyPiece.Slice -> emptyList()
    is AlloyPiece.Op -> operands.flatMap { it.allUses() }
    is AlloyPiece.Bind -> body.allUses()
    is AlloyPiece.Group -> piece.allUses()
}

/**
 * Writes a replacement, with the [symbolic] or keyword spelling of the logical operators. An operand is put
 * in parentheses only where its level is looser than its position allows, or where it would extend further
 * to the right than it should.
 */
class AlloyRewriteRenderer(private val symbolic: Boolean) {
    fun render(piece: AlloyPiece, position: AlloyPosition): String {
        if (piece is AlloyPiece.Group) return "(${render(piece.piece, AlloyPosition.TOP)})"
        val parenthesized = when (piece) {
            is AlloyPiece.Slice -> position.needsParentheses(piece.level, piece.isBind, piece.endsOpen)
            // An operator ending in a quantifier gets the quantifier parenthesized, rather than itself
            is AlloyPiece.Op -> position.needsParentheses(piece.operator.level, isBind = false, endsOpen = false)
            is AlloyPiece.Bind -> position.needsParentheses(AlloyPrecedence.BIND, isBind = true, endsOpen = true)
        }
        val rightOpen = parenthesized || position.rightOpen
        val text = when (piece) {
            is AlloyPiece.Slice -> piece.source.text
            is AlloyPiece.Op -> renderOp(piece, rightOpen)
            is AlloyPiece.Bind -> "${piece.quantifier} ${piece.decls} | ${render(piece.body, AlloyPosition(AlloyPrecedence.SEQ, true, rightOpen))}"
        }
        return if (parenthesized) "($text)" else text
    }

    private fun renderOp(op: AlloyPiece.Op, rightOpen: Boolean): String {
        val operator = op.operator
        val operands = op.operands.mapIndexed { i, operand ->
            val spec = operator.operand(i, op.operands.size)
            render(operand, AlloyPosition(spec.level, spec.bindAllowed, spec.bounded || (spec.rightmost && rightOpen)))
        }
        val spelling = operator.spelling(symbolic)
        return when (operator.fixity) {
            AlloyFixity.PREFIX, AlloyFixity.MULTIPLICITY ->
                if (spelling.first().isLetter()) "$spelling ${operands.single()}" else "$spelling${operands.single()}"
            AlloyFixity.POSTFIX -> operands.single() + spelling
            AlloyFixity.DOT -> operands.joinToString(spelling)
            else -> operands.joinToString(" $spelling ")
        }
    }
}
