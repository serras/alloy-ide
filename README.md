# Alloy 6 for IntelliJ

> [Alloy](https://alloytools.org/) 6 in your favorite IDE

## Features

- **File type**: `.als` files are recognized as Alloy models and shown with the Alloy icon. Alloy models embedded in Markdown (`.md`) files are not supported.
- **Syntax highlighting**: keywords, temporal operators (including the prime `'`), operators, identifiers, numbers, strings, comments (line, block and documentation), braces, parentheses, brackets, commas and dots, and invalid characters. Every category can be customized in *Settings | Editor | Color Scheme | Alloy*.

## Attribution

The lexer (`src/main/grammars/Alloy.flex`) is a port of `Alloy.lex` from the [Alloy Analyzer](https://github.com/AlloyTools/org.alloytools.alloy) (tag `v6.2.0`, `org.alloytools.alloy.core/parser/Alloy.lex`), Copyright (c) 2006-2008 Felix Chang, distributed under the MIT license. The ported file keeps the original license header.
