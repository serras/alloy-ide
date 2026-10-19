// Alloy Analyzer 4 -- Copyright (c) 2006-2008, Felix Chang
//
// Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files
// (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify,
// merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
// OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
// LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF
// OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

// Ported from org.alloytools.alloy.core/parser/Alloy.lex (Alloy 6.2.0, tag v6.2.0) to IntelliJ's FlexLexer.
// Differences from upstream:
// - every token class returns an IElementType from AlloyTypes; word and symbolic spellings of the same
//   operator (`and`/`&&`, `::`/`.`, ...) are kept apart so they can be highlighted differently;
// - comments and whitespace are returned as tokens instead of being skipped;
// - inputs that upstream rejects with a syntax error never fail: unterminated block comments and string
//   literals extend to the end of the file, and any other invalid character is a BAD_CHARACTER token.

package com.serranofp.alloy.lexer;

import com.intellij.lexer.FlexLexer;
import com.intellij.psi.tree.IElementType;

import static com.intellij.psi.TokenType.BAD_CHARACTER;
import static com.intellij.psi.TokenType.WHITE_SPACE;
import static com.serranofp.alloy.psi.AlloyTypes.*;

%%

%class _AlloyLexer
%implements FlexLexer
%public
%final
%unicode
%function advance
%type IElementType

%{
  /** A block comment is a doc comment when it starts with slash-star-star, except for the empty one. */
  private IElementType blockComment() {
    return yylength() > 2 && yycharat(2) == '*' && !yytext().toString().equals("/**/") ? DOC_COMMENT : BLOCK_COMMENT;
  }
%}

WHITE_SPACE=[ \t\f\r\n]+
STRING_CHAR=[^\\\"] | "\\" [^]

%%

"!"                   { return EXCL;          }
"#"                   { return HASH;          }
"&&"                  { return AMPAMP;        }
"&"                   { return AMPERSAND;     }
"("                   { return LPAREN;        }
")"                   { return RPAREN;        }
"*"                   { return STAR;          }
"++"                  { return PLUSPLUS;      }
"+"                   { return PLUS;          }
","                   { return COMMA;         }
"->"                  { return ARROW;         }
"-"                   { return MINUS;         }
"."                   { return DOT;           }
"/"                   { return SLASH;         }
"::"                  { return COLONCOLON;    }
":>"                  { return RANGE;         }
":"                   { return COLON;         }
"<=>"                 { return IFF_ARROW;     }
"<="                  { return LTE;           }
"<:"                  { return DOMAIN;        }
"<<"                  { return SHL;           }
"<"                   { return LT;            }
"=<"                  { return EQLT;          }
"=>"                  { return IMPLIES_ARROW; }
"="                   { return EQUALS;        }
">>>"                 { return SHR;           }
">>"                  { return SHA;           }
">="                  { return GTE;           }
">"                   { return GT;            }
"@"                   { return AT;            }
"["                   { return LBRACKET;      }
"]"                   { return RBRACKET;      }
"^"                   { return CARET;         }
"{"                   { return LBRACE;        }
"||"                  { return BARBAR;        }
"|"                   { return BAR;           }
"}"                   { return RBRACE;        }
"~"                   { return TILDE;         }
"abstract"            { return ABSTRACT;      }
"all"                 { return ALL;           }
"and"                 { return AND;           }
"assert"              { return ASSERT;        }
"as"                  { return AS;            }
"but"                 { return BUT;           }
"check"               { return CHECK;         }
"disjoint"            { return DISJOINT;      }
"disj"                { return DISJ;          }
"else"                { return ELSE;          }
"enum"                { return ENUM;          }
"exactly"             { return EXACTLY;       }
"expect"              { return EXPECT;        }
"extends"             { return EXTENDS;       }
"fact"                { return FACT;          }
"for"                 { return FOR;           }
"fun"                 { return FUN;           }
"iden"                { return IDEN;          }
"iff"                 { return IFF;           }
"implies"             { return IMPLIES;       }
"Int"                 { return SIGINT;        }
"int"                 { return INT;           }
"in"                  { return IN;            }
"let"                 { return LET;           }
"lone"                { return LONE;          }
"module"              { return MODULE;        }
"none"                { return NONE;          }
"not"                 { return NOT;           }
"no"                  { return NO;            }
"one"                 { return ONE;           }
"open"                { return OPEN;          }
"or"                  { return OR;            }
"pred"                { return PRED;          }
"private"             { return PRIVATE;       }
"run"                 { return RUN;           }
"seq"                 { return SEQ;           }
"set"                 { return SET;           }
"sig"                 { return SIG;           }
"some"                { return SOME;          }
"String"              { return STRING;        }
"sum"                 { return SUM;           }
"this"                { return THIS;          }
"univ"                { return UNIV;          }
"always"              { return ALWAYS;        }
"after"               { return AFTER;         }
"eventually"          { return EVENTUALLY;    }
"historically"        { return HISTORICALLY;  }
"before"              { return BEFORE;        }
"once"                { return ONCE;          }
"releases"            { return RELEASES;      }
"until"               { return UNTIL;         }
"since"               { return SINCE;         }
"triggered"           { return TRIGGERED;     }
";"                   { return SEMICOLON;     }
"var"                 { return VAR;           }
"steps"               { return STEPS;         }
"'"                   { return PRIME;         }
"‘"              { return PRIME;         }
"’"              { return PRIME;         }

// Upstream rejects a string followed by an identifier character and a name starting with a digit;
// here they lex as two tokens and the language server reports the error.
[\"] {STRING_CHAR}* [\"]                                               { return STRING_LITERAL; }
[\"] {STRING_CHAR}* "\\"?                                              { return STRING_LITERAL; }
[0]"x"([_]|([0-9A-Fa-f][0-9A-Fa-f]))+                                  { return NUMBER; }
[0]"b"[01_]+                                                           { return NUMBER; }
[0-9][0-9_]*                                                           { return NUMBER; }
[:jletter:][[:jletterdigit:]\"]*                                       { return IDENTIFIER; }

"/*" ~"*/"                   { return blockComment(); }

// Unterminated: never contains "*/", so it is shorter than the rule above whenever that one matches
"/*" !([^]* "*/" [^]*)       { return blockComment(); }

("//"|"--") [^\r\n]*         { return LINE_COMMENT; }

{WHITE_SPACE}                { return WHITE_SPACE; }

[^]                          { return BAD_CHARACTER; }
