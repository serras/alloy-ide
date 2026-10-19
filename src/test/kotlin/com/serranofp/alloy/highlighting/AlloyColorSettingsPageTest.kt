package com.serranofp.alloy.highlighting

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.options.colors.ColorSettingsPages
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.serranofp.alloy.AlloyLanguage

class AlloyColorSettingsPageTest : BasePlatformTestCase() {
    fun testEveryCategoryIsReachableFromDemoText() {
        val page = AlloyColorSettingsPage()
        val highlighter = page.highlighter
        val lexer = highlighter.highlightingLexer
        val reached = mutableSetOf<TextAttributesKey>()
        lexer.start(page.demoText)
        while (lexer.tokenType != null) {
            reached += highlighter.getTokenHighlights(lexer.tokenType)
            lexer.advance()
        }
        val missing = page.attributeDescriptors.filter { it.key !in reached }.map { it.displayName }
        assertEmpty("Categories not used by the demo text", missing)
    }

    fun testExtensionsAreRegistered() {
        assertInstanceOf(
            SyntaxHighlighterFactory.getSyntaxHighlighter(AlloyLanguage, project, null),
            AlloySyntaxHighlighter::class.java,
        )
        assertTrue(ColorSettingsPages.getInstance().registeredPages.any { it is AlloyColorSettingsPage })
    }

    fun testTemporalOperatorsUseTheirOwnKey() {
        val highlighter = AlloySyntaxHighlighter()
        val lexer = highlighter.highlightingLexer
        val text = "always x' ; y"
        lexer.start(text)
        val keys = mutableMapOf<String, List<TextAttributesKey>>()
        while (lexer.tokenType != null) {
            keys[text.substring(lexer.tokenStart, lexer.tokenEnd)] = highlighter.getTokenHighlights(lexer.tokenType).toList()
            lexer.advance()
        }
        for (token in listOf("always", "'", ";")) {
            assertEquals(token, listOf(AlloyHighlighterColors.TEMPORAL_OPERATOR), keys[token])
        }
    }
}
