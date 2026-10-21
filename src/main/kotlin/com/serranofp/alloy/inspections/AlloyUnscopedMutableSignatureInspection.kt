package com.serranofp.alloy.inspections

import com.serranofp.alloy.inspections.AlloyCommandBounds.Source
import edu.mit.csail.sdg.ast.Sig.PrimSig

/**
 * A command that scopes some signatures but leaves one used by the mutable state at the overall or default
 * scope. A signature is used by the mutable state when it occurs in the type of a `var` field, other than as
 * its owner, or when it or one of its descendants is a `var sig`.
 */
class AlloyUnscopedMutableSignatureInspection : AlloyCommandInspection() {
    override fun check(command: CommandCheck) {
        if (command.command.scope.isEmpty()) return
        val scope = command.scope ?: return
        // Through an ancestor or descendant
        val scoped = command.command.scope.mapNotNull { (it.sig as? PrimSig)?.let(::topLevel) }.toSet()
        val written = command.command.scope.joinToString { name(it.sig) }
        for ((sig, uses) in mutableUses(command.context)) {
            if (sig in scoped) continue
            val bound = command.bounds.bound(sig) ?: continue
            if (bound.source != Source.Overall && bound.source != Source.Default) continue
            val message = "${name(sig)} gets the command's default scope of ${bound.n}, although the command sets " +
                "the scope of $written, and ${name(sig)} is used by ${uses.joinToString()}"
            val fix = AlloyScopeFix(command.decl, command.part, AlloyScopeEdit.AddItem(bound.n, name(sig)), "Add a scope for ${name(sig)}")
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
                for (tuple in sig.type().fold()) use(tuple.first(), "the mutable signature ${name(sig)}")
            }
            for (field in sig.fields) {
                if (field.isVariable == null) continue
                for (tuple in field.type().fold()) for (column in tuple.drop(1)) use(column, "the mutable field ${field.label}")
            }
        }
        return uses
    }
}
