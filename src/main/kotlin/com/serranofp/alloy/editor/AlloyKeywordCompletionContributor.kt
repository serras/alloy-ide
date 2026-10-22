package com.serranofp.alloy.editor

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.patterns.PlatformPatterns.psiElement
import com.intellij.util.ProcessingContext
import com.serranofp.alloy.AlloyLanguage
import com.serranofp.alloy.lexer.AlloyTokenSets
import com.serranofp.alloy.viewer.isAlloyEvaluatorFragment

class AlloyKeywordCompletionContributor : CompletionContributor() {
    init {
        extend(CompletionType.BASIC, psiElement().withLanguage(AlloyLanguage), KeywordProvider)
    }

    private object KeywordProvider : CompletionProvider<CompletionParameters>() {
        override fun addCompletions(
            parameters: CompletionParameters,
            context: ProcessingContext,
            result: CompletionResultSet,
        ) {
            // The evaluator offers the names of its model only
            if (parameters.originalFile.isAlloyEvaluatorFragment) return
            // Keywords start paragraphs and expressions, not names
            when (AlloyCompletionContext.of(parameters.position)) {
                AlloyCompletionContext.NotAReference, AlloyCompletionContext.Names -> {}
                else -> return
            }
            for (keyword in KEYWORDS) {
                result.addElement(LookupElementBuilder.create(keyword).bold())
            }
        }
    }

}

/** Word keywords, taken from the token types (whose debug names are their spelling). */
internal val KEYWORDS: List<String> =
    (AlloyTokenSets.KEYWORDS.types + AlloyTokenSets.TEMPORAL_OPERATORS.types)
        .map { it.toString() }
        .filter { it.first().isLetter() }