package com.serranofp.alloy.viewer

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.patterns.PlatformPatterns.psiElement
import com.intellij.util.ProcessingContext
import com.serranofp.alloy.AlloyLanguage
import edu.mit.csail.sdg.ast.Func
import edu.mit.csail.sdg.parser.CompModule
import javax.swing.Icon

/**
 * Completes the evaluator's field (see [ALLOY_EVALUATOR_FRAGMENT]) with the names its expressions can use: those
 * of the shown result's module and of the modules it opens, qualified by their alias when two modules declare
 * the same name, and the solution's atoms and skolems.
 */
class AlloyEvaluatorCompletionContributor : CompletionContributor() {
    init {
        extend(CompletionType.BASIC, psiElement().withLanguage(AlloyLanguage), NameProvider)
    }

    private object NameProvider : CompletionProvider<CompletionParameters>() {
        override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
            val model = parameters.originalFile.getUserData(ALLOY_EVALUATOR_FRAGMENT)?.invoke() ?: return
            // The fragment does not parse as a module, so the prefix is read from the text: a name, possibly qualified
            val text = parameters.editor.document.charsSequence
            var start = parameters.offset
            while (start > 0 && isNamePart(text[start - 1])) start--
            val names = result.withPrefixMatcher(text.substring(start, parameters.offset))
            for (name in NamesProvider.names(model)) names.addElement(name.lookup())
            names.stopHere()
        }

        private fun isNamePart(c: Char) = Character.isJavaIdentifierPart(c) || c == '\'' || c == '"' || c == '/'
    }

    internal object NamesProvider {
        fun names(model: AlloySolutionModel): List<Name> {
            val module = model.module ?: return emptyList()
            // Every model opens util/integer, also when it opens it itself
            val opens = module.opens.filter { it.realModule != null && it.realModule !== module }.distinctBy { it.realModule }
            val declared = declarations(module).map { it to null } +
                    opens.flatMap { open -> declarations(open.realModule).map { it to open.alias } }
            val counts = declared.groupingBy { it.first.text }.eachCount()
            return buildList {
                declared.mapTo(this) { (name, alias) ->
                    if (alias != null && counts.getValue(name.text) > 1) name.copy(text = "$alias/${name.text}") else name
                }
                model.solution.allAtoms.mapTo(this) { Name(it.label, "atom", AllIcons.Nodes.Constant) }
                model.solution.allSkolems.mapTo(this) { Name(it.label, "skolem", AllIcons.Nodes.Variable) }
            }.distinct()
        }

        /** The signatures, fields, predicates and functions declared in [module]. */
        private fun declarations(module: CompModule): List<Name> {
            val sigs = module.allSigs.flatMap { sig ->
                listOf(Name(simple(sig.label), "signature", AllIcons.Nodes.Class)) +
                        sig.fields.map { Name(it.label, "field", AllIcons.Nodes.Field) }
            }
            // Commands without a name get a function such as `run$1`
            val funcs = module.allFunc.filter { '$' !in it.label }.map(::func)
            // Overloads, such as the two `max` of util/integer, are one name
            return (sigs + funcs).distinctBy { it.text }
        }

        private fun func(func: Func) = Name(simple(func.label), if (func.isPred) "predicate" else "function", AllIcons.Nodes.Function)

        /** `this/Node` and `util/ordering/first` are `Node` and `first`. */
        private fun simple(label: String) = label.substringAfterLast('/')
    }

    /** A name an expression can use, with what it names. */
    internal data class Name(val text: String, val kind: String, val icon: Icon) {
        fun lookup(): LookupElement = LookupElementBuilder.create(text).withIcon(icon).withTypeText(kind)
    }
}
