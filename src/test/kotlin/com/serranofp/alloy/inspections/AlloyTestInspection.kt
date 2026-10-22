package com.serranofp.alloy.inspections

import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder

/** Reports what the analyzer's positions of a file map to: commands, their scopes and scope items, and predicates. */
class AlloyTestInspection : AlloyModelInspection() {
    override fun getShortName(): String = "AlloyTest"

    override fun getDisplayName(): String = "Alloy test"

    override fun getGroupDisplayName(): String = "Alloy"

    override fun check(context: AlloyInspectionContext, holder: ProblemsHolder) {
        for (command in context.model.module.allCommands) {
            if (!context.inFile(command.pos)) continue
            val decl = context.commandDecl(command.pos) ?: continue
            val scope = AlloyScopeText.of(decl, context.part(decl, command.pos))
            context.register(holder, decl, scope?.range ?: decl.textRange, "command", type = WEAK)
            for (scope in command.scope) {
                context.scopeItemRange(decl, scope.pos)?.let { context.register(holder, decl, it, "scope item", type = WEAK) }
            }
        }
        for (func in context.model.module.allFunc) {
            val decl = context.predDecl(func.pos) ?: continue
            val name = decl.nameIdentifier ?: continue
            context.register(holder, decl, name.textRange, "pred", type = WEAK)
        }
    }

    private companion object {
        val WEAK = ProblemHighlightType.WEAK_WARNING
    }
}
