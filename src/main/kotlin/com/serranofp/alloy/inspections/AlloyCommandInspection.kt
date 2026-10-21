package com.serranofp.alloy.inspections

import com.intellij.codeInspection.ProblemsHolder
import com.serranofp.alloy.psi.AlloyCommandDecl
import com.serranofp.alloy.semantics.AlloyPositions
import edu.mit.csail.sdg.ast.Command
import edu.mit.csail.sdg.ast.Sig
import edu.mit.csail.sdg.ast.Sig.PrimSig

/** An inspection of each command of the file, including every command of a `=>` chain, with its bounds. */
abstract class AlloyCommandInspection : AlloyModelInspection() {
    /** One command: the [part]th of the chain [decl]. */
    class CommandCheck(
        val context: AlloyInspectionContext,
        val holder: ProblemsHolder,
        val decl: AlloyCommandDecl,
        val part: Int,
        val command: Command,
        val bounds: AlloyCommandBounds,
    ) {
        /** Its written scope; null without `for`. */
        val scope: AlloyScopeText? get() = AlloyScopeText.of(decl, part)
    }

    override fun check(context: AlloyInspectionContext, holder: ProblemsHolder) {
        commands(context, holder).forEach(::check)
    }

    protected abstract fun check(command: CommandCheck)

    companion object {
        /** Every command of the file, including every command of a `=>` chain. */
        fun commands(context: AlloyInspectionContext, holder: ProblemsHolder): Sequence<CommandCheck> = sequence {
            val sigs = context.model.module.allReachableSigs.toList()
            for (command in context.model.module.allCommands) {
                if (!context.inFile(command.pos)) continue
                val decl = context.commandDecl(command.pos) ?: continue
                val parts = mutableSetOf<Int>()
                // The analyzer lists the last command of a chain, which leads to the others through its parents
                for (each in generateSequence(command) { it.parent }) {
                    if (!context.inFile(each.pos)) continue
                    val part = context.part(decl, each.pos)
                    if (parts.add(part)) yield(CommandCheck(context, holder, decl, part, each, AlloyCommandBounds(each, sigs)))
                }
            }
        }

        /** The top-level signature that [sig] extends, or [sig] itself. */
        fun topLevel(sig: PrimSig): PrimSig = generateSequence(sig) { it.parent }.takeWhile { it != Sig.UNIV }.lastOrNull() ?: sig

        /** User signatures, declared outside the bundled library; not the analyzer's meta signatures, such as `File$`. */
        fun isUser(sig: Sig): Boolean = !sig.builtin && sig.isMeta == null && !AlloyPositions.isLibrary(sig.pos.filename)

        /** How a message names [sig]: without `this/`. */
        fun name(sig: Sig): String = sig.label.removePrefix("this/")
    }
}
