package com.serranofp.alloy.lexer;

import com.intellij.lexer.FlexLexer;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;

%%

%class _AlloyLexer
%implements FlexLexer
%unicode
%function advance
%type IElementType

%%

// Placeholder: replaced by the port of upstream Alloy.lex.
[^] { return TokenType.BAD_CHARACTER; }
