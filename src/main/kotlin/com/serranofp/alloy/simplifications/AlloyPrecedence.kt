package com.serranofp.alloy.simplifications

import com.serranofp.alloy.psi.AlloyTypes
import edu.mit.csail.sdg.ast.Expr
import edu.mit.csail.sdg.ast.ExprBinary
import edu.mit.csail.sdg.ast.ExprITE
import edu.mit.csail.sdg.ast.ExprLet
import edu.mit.csail.sdg.ast.ExprList
import edu.mit.csail.sdg.ast.ExprQt
import edu.mit.csail.sdg.ast.ExprUnary

/**
 * The precedence levels of `CompParser`, loosest first. Quantifiers and `let` ([BIND]) extend to the right,
 * and may end most binary expressions without parentheses (the grammar's `…A` variants).
 */
object AlloyPrecedence {
    const val SEQ = 0
    const val BIND = 1
    const val OR = 2
    const val IFF = 3
    const val IMPLIES = 4
    const val AND = 5
    const val TEMPORAL = 6
    const val UNARY = 7
    const val COMPARE = 8
    const val SHIFT = 9
    const val UNION = 10
    const val MUL = 11
    const val CARDINALITY = 12
    const val OVERRIDE = 13
    const val INTERSECT = 14
    const val ARROW = 15
    const val DOMAIN = 16
    const val RANGE = 17
    const val BRACKET = 18
    const val UNOP = 20
    const val ATOM = 21

    /** The level of [node] as written in [source], which must be [AlloySourceText.strip]ped. */
    fun level(node: Expr, source: AlloySourceText): Int = when (node) {
        is ExprUnary -> AlloyOperator.of(node)?.level ?: when (node.op) {
            ExprUnary.Op.NOOP -> ATOM
            ExprUnary.Op.SETOF, ExprUnary.Op.SOMEOF, ExprUnary.Op.LONEOF, ExprUnary.Op.ONEOF, ExprUnary.Op.EXACTLYOF -> COMPARE
            else -> UNARY
        }
        is ExprBinary -> AlloyOperator.of(node)?.level ?: when (node.op) {
            // A box join `a[b]`, or the implicit `this.f` of a signature fact
            ExprBinary.Op.JOIN -> BRACKET
            ExprBinary.Op.MUL, ExprBinary.Op.DIV, ExprBinary.Op.REM -> MUL
            ExprBinary.Op.IPLUS, ExprBinary.Op.IMINUS -> UNION
            else -> if (node.op.isArrow) ARROW else COMPARE
        }
        is ExprList -> when (node.op) {
            ExprList.Op.AND -> if (source.inFile(node.pos) && source.tokenAt(source.range(node.pos).startOffset)?.let { it.node.elementType == AlloyTypes.SEMICOLON } == true) SEQ else AND
            ExprList.Op.OR -> OR
            else -> ATOM
        }
        is ExprQt -> if (node.op == ExprQt.Op.COMPREHENSION) ATOM else BIND
        is ExprLet -> BIND
        is ExprITE -> IMPLIES
        else -> ATOM
    }

    /** Whether [node] is a quantifier or `let`, which extends to the right. */
    fun isBind(node: Expr): Boolean = (node is ExprQt && node.op != ExprQt.Op.COMPREHENSION) || node is ExprLet
}

/**
 * Where an operand sits under its operator: it is written without parentheses when its level is at least
 * [level]. It may be a quantifier or `let` when [bindAllowed], as the last operand of most operators. A
 * [rightmost] operand extends as far as its operator does, and a [bounded] one is enclosed by brackets.
 */
data class AlloyOperandSpec(val level: Int, val bindAllowed: Boolean, val rightmost: Boolean, val bounded: Boolean = false) {
    companion object {
        /** An operand in brackets, braces or a block line: anything goes. */
        val BOUNDED = AlloyOperandSpec(AlloyPrecedence.SEQ, bindAllowed = true, rightmost = false, bounded = true)

        /** An operand whose context is not known: only atoms go without parentheses. */
        val CONSERVATIVE = AlloyOperandSpec(AlloyPrecedence.ATOM, bindAllowed = false, rightmost = false)
    }
}

/**
 * Where a node or a replacement sits: [AlloyOperandSpec.level] and [bindAllowed] as for its operand spec, and
 * [rightOpen] when nothing follows it up to the end of what encloses it, so that it may end open.
 */
data class AlloyPosition(val level: Int, val bindAllowed: Boolean, val rightOpen: Boolean) {
    /** Whether something of [level] needs parentheses here: a quantifier or `let` if [isBind], or something ending in one if [endsOpen]. */
    fun needsParentheses(level: Int, isBind: Boolean, endsOpen: Boolean): Boolean =
        if (isBind) !(rightOpen && (bindAllowed || this.level <= AlloyPrecedence.BIND))
        else level < this.level || (endsOpen && !rightOpen)

    companion object {
        /** A whole block line, paragraph body, or quantifier or `let` body. */
        val TOP = AlloyPosition(AlloyPrecedence.SEQ, bindAllowed = true, rightOpen = true)
    }
}

/** How an operator is written around its operands. */
enum class AlloyFixity { PREFIX, MULTIPLICITY, POSTFIX, NARY, LEFT, RIGHT, COMPARISON, DOT }

/**
 * The operators that a replacement can write, with their [level], [fixity] and spelling: [keyword], or
 * [symbol] for the symbolic style of `!`, `&&`, `||`, `=>` and `<=>`.
 */
enum class AlloyOperator(val level: Int, val fixity: AlloyFixity, val keyword: String, val symbol: String = keyword) {
    NOT(AlloyPrecedence.UNARY, AlloyFixity.PREFIX, "not", "!"),
    AFTER(AlloyPrecedence.UNARY, AlloyFixity.PREFIX, "after"),
    ALWAYS(AlloyPrecedence.UNARY, AlloyFixity.PREFIX, "always"),
    EVENTUALLY(AlloyPrecedence.UNARY, AlloyFixity.PREFIX, "eventually"),
    BEFORE(AlloyPrecedence.UNARY, AlloyFixity.PREFIX, "before"),
    HISTORICALLY(AlloyPrecedence.UNARY, AlloyFixity.PREFIX, "historically"),
    ONCE(AlloyPrecedence.UNARY, AlloyFixity.PREFIX, "once"),
    NO(AlloyPrecedence.COMPARE, AlloyFixity.MULTIPLICITY, "no"),
    SOME(AlloyPrecedence.COMPARE, AlloyFixity.MULTIPLICITY, "some"),
    LONE(AlloyPrecedence.COMPARE, AlloyFixity.MULTIPLICITY, "lone"),
    ONE(AlloyPrecedence.COMPARE, AlloyFixity.MULTIPLICITY, "one"),
    AND(AlloyPrecedence.AND, AlloyFixity.NARY, "and", "&&"),
    OR(AlloyPrecedence.OR, AlloyFixity.NARY, "or", "||"),
    IFF(AlloyPrecedence.IFF, AlloyFixity.LEFT, "iff", "<=>"),
    IMPLIES(AlloyPrecedence.IMPLIES, AlloyFixity.RIGHT, "implies", "=>"),
    UNTIL(AlloyPrecedence.TEMPORAL, AlloyFixity.LEFT, "until"),
    RELEASES(AlloyPrecedence.TEMPORAL, AlloyFixity.LEFT, "releases"),
    SINCE(AlloyPrecedence.TEMPORAL, AlloyFixity.LEFT, "since"),
    TRIGGERED(AlloyPrecedence.TEMPORAL, AlloyFixity.LEFT, "triggered"),
    EQUALS(AlloyPrecedence.COMPARE, AlloyFixity.COMPARISON, "="),
    NOT_EQUALS(AlloyPrecedence.COMPARE, AlloyFixity.COMPARISON, "!="),
    IN(AlloyPrecedence.COMPARE, AlloyFixity.COMPARISON, "in"),
    // As the spec's `!(x in y)` → `x not in y`: `!in` is not among the symbolic operators
    NOT_IN(AlloyPrecedence.COMPARE, AlloyFixity.COMPARISON, "not in"),
    LT(AlloyPrecedence.COMPARE, AlloyFixity.COMPARISON, "<"),
    LTE(AlloyPrecedence.COMPARE, AlloyFixity.COMPARISON, "=<"),
    GT(AlloyPrecedence.COMPARE, AlloyFixity.COMPARISON, ">"),
    GTE(AlloyPrecedence.COMPARE, AlloyFixity.COMPARISON, ">="),
    NOT_LT(AlloyPrecedence.COMPARE, AlloyFixity.COMPARISON, "!<"),
    NOT_LTE(AlloyPrecedence.COMPARE, AlloyFixity.COMPARISON, "!=<"),
    NOT_GT(AlloyPrecedence.COMPARE, AlloyFixity.COMPARISON, "!>"),
    NOT_GTE(AlloyPrecedence.COMPARE, AlloyFixity.COMPARISON, "!>="),
    PLUS(AlloyPrecedence.UNION, AlloyFixity.LEFT, "+"),
    MINUS(AlloyPrecedence.UNION, AlloyFixity.LEFT, "-"),
    CARDINALITY(AlloyPrecedence.CARDINALITY, AlloyFixity.PREFIX, "#"),
    OVERRIDE(AlloyPrecedence.OVERRIDE, AlloyFixity.LEFT, "++"),
    INTERSECT(AlloyPrecedence.INTERSECT, AlloyFixity.LEFT, "&"),
    ARROW(AlloyPrecedence.ARROW, AlloyFixity.RIGHT, "->"),
    DOMAIN(AlloyPrecedence.DOMAIN, AlloyFixity.LEFT, "<:"),
    RANGE(AlloyPrecedence.RANGE, AlloyFixity.LEFT, ":>"),
    JOIN(AlloyPrecedence.BRACKET, AlloyFixity.DOT, "."),
    TRANSPOSE(AlloyPrecedence.UNOP, AlloyFixity.PREFIX, "~"),
    CLOSURE(AlloyPrecedence.UNOP, AlloyFixity.PREFIX, "^"),
    RCLOSURE(AlloyPrecedence.UNOP, AlloyFixity.PREFIX, "*"),
    PRIME(AlloyPrecedence.UNOP, AlloyFixity.POSTFIX, "'");

    fun spelling(symbolic: Boolean): String = if (symbolic) symbol else keyword

    /** Where operand [index] of [count] sits. */
    fun operand(index: Int, count: Int): AlloyOperandSpec = when (fixity) {
        AlloyFixity.PREFIX -> AlloyOperandSpec(level, bindAllowed = true, rightmost = true)
        // `some e` takes an expression above the comparisons, which cannot be a quantifier
        AlloyFixity.MULTIPLICITY -> AlloyOperandSpec(AlloyPrecedence.SHIFT, bindAllowed = false, rightmost = true)
        AlloyFixity.POSTFIX -> AlloyOperandSpec(AlloyPrecedence.UNOP, bindAllowed = false, rightmost = false)
        AlloyFixity.NARY -> (index == count - 1).let { AlloyOperandSpec(level, bindAllowed = it, rightmost = it) }
        AlloyFixity.LEFT ->
            if (index == 0) AlloyOperandSpec(level, bindAllowed = false, rightmost = false)
            else AlloyOperandSpec(level + 1, bindAllowed = true, rightmost = true)
        AlloyFixity.RIGHT ->
            if (index == 0) AlloyOperandSpec(level + 1, bindAllowed = false, rightmost = false)
            else AlloyOperandSpec(level, bindAllowed = true, rightmost = true)
        AlloyFixity.COMPARISON ->
            if (index == 0) AlloyOperandSpec(level, bindAllowed = false, rightmost = false)
            else AlloyOperandSpec(AlloyPrecedence.SHIFT, bindAllowed = false, rightmost = true)
        AlloyFixity.DOT ->
            if (index == 0) AlloyOperandSpec(AlloyPrecedence.BRACKET, bindAllowed = false, rightmost = false)
            else AlloyOperandSpec(AlloyPrecedence.UNOP, bindAllowed = true, rightmost = true)
    }

    companion object {
        private val UNARY = mapOf(
            ExprUnary.Op.NOT to NOT, ExprUnary.Op.AFTER to AFTER, ExprUnary.Op.ALWAYS to ALWAYS,
            ExprUnary.Op.EVENTUALLY to EVENTUALLY, ExprUnary.Op.BEFORE to BEFORE,
            ExprUnary.Op.HISTORICALLY to HISTORICALLY, ExprUnary.Op.ONCE to ONCE,
            ExprUnary.Op.NO to NO, ExprUnary.Op.SOME to SOME, ExprUnary.Op.LONE to LONE, ExprUnary.Op.ONE to ONE,
            ExprUnary.Op.CARDINALITY to CARDINALITY, ExprUnary.Op.TRANSPOSE to TRANSPOSE,
            ExprUnary.Op.CLOSURE to CLOSURE, ExprUnary.Op.RCLOSURE to RCLOSURE, ExprUnary.Op.PRIME to PRIME,
        )

        private val BINARY = mapOf(
            ExprBinary.Op.IFF to IFF, ExprBinary.Op.IMPLIES to IMPLIES,
            ExprBinary.Op.UNTIL to UNTIL, ExprBinary.Op.RELEASES to RELEASES,
            ExprBinary.Op.SINCE to SINCE, ExprBinary.Op.TRIGGERED to TRIGGERED,
            ExprBinary.Op.EQUALS to EQUALS, ExprBinary.Op.NOT_EQUALS to NOT_EQUALS,
            ExprBinary.Op.IN to IN, ExprBinary.Op.NOT_IN to NOT_IN,
            ExprBinary.Op.LT to LT, ExprBinary.Op.LTE to LTE, ExprBinary.Op.GT to GT, ExprBinary.Op.GTE to GTE,
            ExprBinary.Op.NOT_LT to NOT_LT, ExprBinary.Op.NOT_LTE to NOT_LTE,
            ExprBinary.Op.NOT_GT to NOT_GT, ExprBinary.Op.NOT_GTE to NOT_GTE,
            ExprBinary.Op.PLUS to PLUS, ExprBinary.Op.MINUS to MINUS, ExprBinary.Op.PLUSPLUS to OVERRIDE,
            ExprBinary.Op.INTERSECT to INTERSECT, ExprBinary.Op.ARROW to ARROW,
            ExprBinary.Op.DOMAIN to DOMAIN, ExprBinary.Op.RANGE to RANGE,
        )

        /** The operator of [node]; a join only when it is written with a `.`, which the caller checks. */
        fun of(node: Expr): AlloyOperator? = when (node) {
            is ExprUnary -> UNARY[node.op]
            is ExprBinary -> BINARY[node.op]
            is ExprList -> when (node.op) {
                ExprList.Op.AND -> AND
                ExprList.Op.OR -> OR
                else -> null
            }
            else -> null
        }
    }
}
