package com.serranofp.alloy.inspections

import com.serranofp.alloy.AlloyBundle
import com.serranofp.alloy.inspections.AlloyCommandBounds.Source
import edu.mit.csail.sdg.ast.Sig.PrimSig

/**
 * A command that scopes some signatures but leaves one used by the mutable state at the overall scope. A
 * signature is used by the mutable state when it occurs in the type of a `var` field, other than as its owner,
 * or when it or one of its descendants is a `var sig`. (The default scope only applies to commands that scope
 * nothing.)
 */
class AlloyUnscopedMutableSignatureInspection : AlloyCommandInspection<Map<PrimSig, List<String>>>() {
    override fun prepare(context: AlloyInspectionContext): Map<PrimSig, List<String>> = mutableUses(context)

    override fun check(command: CommandCheck, data: Map<PrimSig, List<String>>) {
        if (command.command.scope.isEmpty()) return
        val scope = command.scope ?: return
        // Through an ancestor or descendant
        val scoped = command.command.scope.mapNotNull { (it.sig as? PrimSig)?.let(::topLevel) }.toSet()
        val written = command.command.scope.joinToString { name(it.sig) }
        for ((sig, uses) in data) {
            if (sig in scoped) continue
            val bound = command.bounds.bound(sig) ?: continue
            if (bound.source != Source.Overall) continue
            val message = AlloyBundle.message("inspection.unscoped.message", name(sig), bound.n, written, uses.joinToString())
            val fix = AlloyScopeFix(
                command.decl, command.part, AlloyScopeEdit.AddItem(bound.n, name(sig)),
                AlloyBundle.message("inspection.unscoped.fix", name(sig)), AlloyBundle.message("inspection.unscoped.fix.family"),
            )
            command.context.register(command.holder, command.decl, scope.range, message, fix)
        }
    }

    /** The user's top-level signatures that the mutable state uses, each with how it uses them. */
    private fun mutableUses(context: AlloyInspectionContext): Map<PrimSig, List<String>> {
        val uses = LinkedHashMap<PrimSig, MutableList<String>>()
        fun use(sig: PrimSig, how: String) {
            val top = topLevel(sig)
            if (isUser(top)) uses.getOrPut(top) { mutableListOf() }.let { if (how !in it) it += how }
        }
        for (sig in context.model.module.allReachableSigs) {
            if (!isUser(sig)) continue
            if (sig.isVariable != null) {
                val how = AlloyBundle.message("inspection.unscoped.use.signature", name(sig))
                for (tuple in sig.type().fold()) use(tuple.first(), how)
            }
            for (field in sig.fields) {
                if (field.isVariable == null) continue
                val how = AlloyBundle.message("inspection.unscoped.use.field", field.label)
                for (tuple in field.type().fold()) for (column in tuple.drop(1)) use(column, how)
            }
        }
        return uses
    }
}
