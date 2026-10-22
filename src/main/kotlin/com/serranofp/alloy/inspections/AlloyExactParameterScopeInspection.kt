package com.serranofp.alloy.inspections

import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiWhiteSpace
import com.serranofp.alloy.AlloyBundle
import com.serranofp.alloy.inspections.AlloyCommandBounds.Source
import com.serranofp.alloy.psi.AlloyModuleDecl
import com.serranofp.alloy.psi.AlloyTypes
import com.serranofp.alloy.semantics.AlloyPositions
import edu.mit.csail.sdg.alloy4.Util
import edu.mit.csail.sdg.ast.Sig
import edu.mit.csail.sdg.parser.CompModule

/**
 * A command scope that reads as an upper bound for a signature that an `exactly` module parameter, such as
 * `util/ordering`'s, makes exact. A written scope gets a weak warning; the overall scope only an
 * information-level problem, since every command of a model with an ordering would be highlighted otherwise.
 */
class AlloyExactParameterScopeInspection : AlloyCommandInspection<Unit>() {
    override fun prepare(context: AlloyInspectionContext) {}

    override fun check(command: CommandCheck, data: Unit) {
        val scope = command.scope ?: return
        for (sig in command.command.additionalExactScopes) {
            if (!isUser(sig)) continue
            val bound = command.bounds.bound(sig) ?: continue
            val module = moduleOf(command.context, sig) ?: AlloyBundle.message("inspection.exact.parameter.unknown")
            val fixText = AlloyBundle.message("inspection.exact.parameter.fix", name(sig))
            val family = AlloyBundle.message("inspection.exact.parameter.fix.family")
            when (val source = bound.source) {
                is Source.Explicit -> {
                    if (source.scope.isExact) continue
                    val range = command.context.scopeItemRange(command.decl, source.scope.pos) ?: continue
                    val index = scope.itemAt(range.startOffset).takeIf { it >= 0 } ?: continue
                    val message = AlloyBundle.message("inspection.exact.parameter.written", module, name(sig), bound.n)
                    val fix = AlloyScopeFix(command.decl, command.part, AlloyScopeEdit.MakeExact(index), fixText, family)
                    command.context.register(command.holder, command.decl, range, message, fix)
                }
                Source.Overall -> {
                    val message = AlloyBundle.message("inspection.exact.parameter.overall", module, name(sig), bound.n)
                    val edit = AlloyScopeEdit.AddFirstItem(bound.n, name(sig), "exactly ")
                    val fix = AlloyScopeFix(command.decl, command.part, edit, fixText, family)
                    command.context.register(command.holder, command.decl, scope.range, message, fix, type = ProblemHighlightType.INFORMATION)
                }
                else -> {}
            }
        }
    }

    /**
     * The module that an `open` instantiates with [sig] for a parameter it declares `exactly`, such as
     * `util/ordering`; the analyzer only says that some module makes [sig] exact. An argument names [sig] when it
     * names a signature of the opening module, or of another one when the opening module has none of that name.
     */
    private fun moduleOf(context: AlloyInspectionContext, sig: Sig): String? {
        val tail = Util.tailThis(sig.label).substringAfterLast('/')
        for (opener in context.model.module.allReachableModules) {
            val own = opener.allSigs.filter { Util.tailThis(it.label).substringAfterLast('/') == tail }
            for (open in opener.opens) {
                val exact = exactParameters(context, open.realModule ?: continue)
                val names = open.args.withIndex().any { (i, arg) ->
                    exact.getOrElse(i) { false } && arg.substringAfterLast('/') == tail && (own.isEmpty() || sig in own)
                }
                if (names) return open.filename
            }
        }
        return null
    }

    /** Whether each parameter of [module] is declared `exactly`, as its `module` declaration reads. */
    private fun exactParameters(context: AlloyInspectionContext, module: CompModule): List<Boolean> {
        val file = AlloyPositions.file(module.pos().filename, context.model.modules.files) ?: return emptyList()
        val decl = PsiManager.getInstance(context.file.project).findFile(file)?.children?.filterIsInstance<AlloyModuleDecl>()?.firstOrNull()
            ?: return emptyList()
        val tokens = generateSequence(decl.firstChild) { it.nextSibling }
            .filter { it !is PsiWhiteSpace && it !is PsiComment }
            .dropWhile { it.node.elementType != AlloyTypes.LBRACKET }.drop(1)
            .takeWhile { it.node.elementType != AlloyTypes.RBRACKET }
            .toList()
        val parameters = mutableListOf(false)
        for (token in tokens) {
            when (token.node.elementType) {
                AlloyTypes.COMMA -> parameters += false
                AlloyTypes.EXACTLY -> parameters[parameters.lastIndex] = true
            }
        }
        return parameters
    }
}
