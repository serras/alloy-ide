package com.serranofp.alloy.highlighting

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import com.serranofp.alloy.AlloyBundle
import com.serranofp.alloy.AlloyIcons
import javax.swing.Icon

class AlloyColorSettingsPage : ColorSettingsPage {
    override fun getDisplayName(): String = "Alloy"

    override fun getIcon(): Icon = AlloyIcons.FILE

    override fun getHighlighter(): SyntaxHighlighter = AlloySyntaxHighlighter()

    override fun getDemoText(): String = """
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

    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey>? = null

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = arrayOf(
        AttributesDescriptor(AlloyBundle.message("color.keyword"), AlloyHighlighterColors.KEYWORD),
        AttributesDescriptor(AlloyBundle.message("color.temporal.operator"), AlloyHighlighterColors.TEMPORAL_OPERATOR),
        AttributesDescriptor(AlloyBundle.message("color.operator"), AlloyHighlighterColors.OPERATOR),
        AttributesDescriptor(AlloyBundle.message("color.identifier"), AlloyHighlighterColors.IDENTIFIER),
        AttributesDescriptor(AlloyBundle.message("color.number"), AlloyHighlighterColors.NUMBER),
        AttributesDescriptor(AlloyBundle.message("color.string"), AlloyHighlighterColors.STRING),
        AttributesDescriptor(AlloyBundle.message("color.line.comment"), AlloyHighlighterColors.LINE_COMMENT),
        AttributesDescriptor(AlloyBundle.message("color.block.comment"), AlloyHighlighterColors.BLOCK_COMMENT),
        AttributesDescriptor(AlloyBundle.message("color.doc.comment"), AlloyHighlighterColors.DOC_COMMENT),
        AttributesDescriptor(AlloyBundle.message("color.braces"), AlloyHighlighterColors.BRACES),
        AttributesDescriptor(AlloyBundle.message("color.parentheses"), AlloyHighlighterColors.PARENTHESES),
        AttributesDescriptor(AlloyBundle.message("color.brackets"), AlloyHighlighterColors.BRACKETS),
        AttributesDescriptor(AlloyBundle.message("color.comma"), AlloyHighlighterColors.COMMA),
        AttributesDescriptor(AlloyBundle.message("color.dot"), AlloyHighlighterColors.DOT),
        AttributesDescriptor(AlloyBundle.message("color.bad.character"), AlloyHighlighterColors.BAD_CHARACTER),
    )

    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY
}
