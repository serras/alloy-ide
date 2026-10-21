package com.serranofp.alloy.inspections

import com.intellij.codeInspection.ProblemHighlightType
import com.serranofp.alloy.inspections.AlloyCommandBounds.Source
import edu.mit.csail.sdg.ast.Sig

/**
 * A command scope that reads as an upper bound for a signature that an `exactly` module parameter, such as
 * `util/ordering`'s, makes exact. A written scope gets a weak warning; the overall scope only an
 * information-level problem, since every command of a model with an ordering would be highlighted otherwise.
 */
class AlloyExactParameterScopeInspection : AlloyCommandInspection() {
    override fun check(command: CommandCheck) {
        val scope = command.scope ?: return
        for (sig in command.command.additionalExactScopes) {
            if (!isUser(sig)) continue
            val bound = command.bounds.bound(sig) ?: continue
            val module = moduleOf(command.context, sig)
            val consequence = "so configurations with fewer ${name(sig)} atoms are not analyzed"
            val fixText = "Write the scope of ${name(sig)} as exact"
            when (val source = bound.source) {
                is Source.Explicit -> {
                    if (source.scope.isExact) continue
                    val range = command.context.scopeItemRange(command.decl, source.scope.pos) ?: continue
                    val index = scope.itemAt(range.startOffset).takeIf { it >= 0 } ?: continue
                    val message = "$module makes the scope of ${name(sig)} exactly ${bound.n}, $consequence"
                    val fix = AlloyScopeFix(command.decl, command.part, AlloyScopeEdit.MakeExact(index), fixText)
                    command.context.register(command.holder, command.decl, range, message, fix)
                }
                Source.Overall -> {
                    val message = "$module makes the scope of ${name(sig)} exactly ${bound.n}, the overall scope, $consequence"
                    val fix = AlloyScopeFix(command.decl, command.part, AlloyScopeEdit.AddFirstItem(bound.n, name(sig), "exactly "), fixText)
                    command.context.register(command.holder, command.decl, scope.range, message, fix, type = ProblemHighlightType.INFORMATION)
                }
                else -> {}
            }
        }
    }

    /** The module that an `open` instantiates with [sig], such as `util/ordering`; the analyzer does not say which one forces it. */
    private fun moduleOf(context: AlloyInspectionContext, sig: Sig): String {
        val opens = context.model.module.allReachableModules.flatMap { it.opens }
        val open = opens.firstOrNull { open -> open.args.any { it.substringAfterLast('/') == name(sig).substringAfterLast('/') } }
        return open?.filename ?: "A module parameter"
    }
}
