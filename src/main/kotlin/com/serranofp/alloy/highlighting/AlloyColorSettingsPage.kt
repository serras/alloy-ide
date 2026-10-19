package com.serranofp.alloy.highlighting

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import com.serranofp.alloy.AlloyIcons
import javax.swing.Icon

class AlloyColorSettingsPage : ColorSettingsPage {
    override fun getDisplayName(): String = "Alloy"

    override fun getIcon(): Icon = AlloyIcons.FILE

    override fun getHighlighter(): SyntaxHighlighter = AlloySyntaxHighlighter()

    override fun getDemoText(): String = DEMO_TEXT

    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey>? = null

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = DESCRIPTORS

    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY

    companion object {
        val DESCRIPTORS = arrayOf(
            AttributesDescriptor("Keyword", AlloyHighlighterColors.KEYWORD),
            AttributesDescriptor("Temporal operator", AlloyHighlighterColors.TEMPORAL_OPERATOR),
            AttributesDescriptor("Operator", AlloyHighlighterColors.OPERATOR),
            AttributesDescriptor("Identifier", AlloyHighlighterColors.IDENTIFIER),
            AttributesDescriptor("Number", AlloyHighlighterColors.NUMBER),
            AttributesDescriptor("String", AlloyHighlighterColors.STRING),
            AttributesDescriptor("Comments//Line comment", AlloyHighlighterColors.LINE_COMMENT),
            AttributesDescriptor("Comments//Block comment", AlloyHighlighterColors.BLOCK_COMMENT),
            AttributesDescriptor("Comments//Doc comment", AlloyHighlighterColors.DOC_COMMENT),
            AttributesDescriptor("Braces and Operators//Braces", AlloyHighlighterColors.BRACES),
            AttributesDescriptor("Braces and Operators//Parentheses", AlloyHighlighterColors.PARENTHESES),
            AttributesDescriptor("Braces and Operators//Brackets", AlloyHighlighterColors.BRACKETS),
            AttributesDescriptor("Braces and Operators//Comma", AlloyHighlighterColors.COMMA),
            AttributesDescriptor("Braces and Operators//Dot", AlloyHighlighterColors.DOT),
            AttributesDescriptor("Bad character", AlloyHighlighterColors.BAD_CHARACTER),
        )

        val DEMO_TEXT = """
            /** A simple file system */
            module filesystem
            open util/ordering[State]

            abstract sig Object {}
            sig Dir extends Object { var contents: set Object, label: one String }
            one sig Root extends Dir {}
            fact named { Root.label = "root" }

            /* Every object is reachable from the root */
            fact reachable { always Object in Root.*contents }

            pred move[d, d2: Dir] {
              d.contents' = d.contents - d2 and d2.contents' = d2.contents + d
            }

            // Nothing is lost
            assert noLoss { always (some Root releases no Object) ; after Root in Dir }

            check noLoss for 5 but 10 steps, 4 Int expect 0
            run move for 3 -- small scope
            run { #Object > 2 } for 4 ¶
        """.trimIndent()
    }
}
