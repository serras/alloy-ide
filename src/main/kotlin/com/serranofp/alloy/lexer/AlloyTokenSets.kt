package com.serranofp.alloy.lexer

import com.intellij.psi.tree.TokenSet
import com.serranofp.alloy.psi.AlloyTypes.*

object AlloyTokenSets {
    @JvmField
    val KEYWORDS: TokenSet = TokenSet.create(
        ABSTRACT, ALL, AND, ASSERT, AS, BUT, CHECK, DISJOINT, DISJ, ELSE, ENUM, EXACTLY, EXPECT, EXTENDS,
        FACT, FOR, FUN, IDEN, IFF, IMPLIES, SIGINT, INT, IN, LET, LONE, MODULE, NONE, NOT, NO, ONE, OPEN, OR,
        PRED, PRIVATE, RUN, SEQ, SET, SIG, SOME, STRING, SUM, THIS, UNIV, VAR, STEPS,
    )

    /** Temporal keywords, the prime and the trace sequencing operator `;`. */
    @JvmField
    val TEMPORAL_OPERATORS: TokenSet = TokenSet.create(
        ALWAYS, AFTER, EVENTUALLY, HISTORICALLY, BEFORE, ONCE, RELEASES, UNTIL, SINCE, TRIGGERED,
        PRIME, SEMICOLON,
    )

    @JvmField
    val OPERATORS: TokenSet = TokenSet.create(
        EXCL, HASH, AMPAMP, AMPERSAND, STAR, PLUSPLUS, PLUS, ARROW, MINUS, SLASH, COLONCOLON, RANGE, COLON,
        IFF_ARROW, LTE, DOMAIN, SHL, LT, EQLT, IMPLIES_ARROW, EQUALS, SHR, SHA, GTE, GT, AT, CARET, BARBAR,
        BAR, TILDE,
    )

    @JvmField
    val BRACES: TokenSet = TokenSet.create(LBRACE, RBRACE)

    @JvmField
    val PARENTHESES: TokenSet = TokenSet.create(LPAREN, RPAREN)

    @JvmField
    val BRACKETS: TokenSet = TokenSet.create(LBRACKET, RBRACKET)

    @JvmField
    val COMMENTS: TokenSet = TokenSet.create(LINE_COMMENT, BLOCK_COMMENT, DOC_COMMENT)

    @JvmField
    val STRINGS: TokenSet = TokenSet.create(STRING_LITERAL)
}
