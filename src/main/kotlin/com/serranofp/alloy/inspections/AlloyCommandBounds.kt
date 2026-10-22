package com.serranofp.alloy.inspections

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Key
import com.serranofp.alloy.semantics.AlloyModelResult
import edu.mit.csail.sdg.alloy4.Err
import edu.mit.csail.sdg.alloy4.Util
import edu.mit.csail.sdg.ast.Command
import edu.mit.csail.sdg.ast.CommandScope
import edu.mit.csail.sdg.ast.Sig
import edu.mit.csail.sdg.ast.Sig.PrimSig
import java.util.Collections
import java.util.IdentityHashMap

/**
 * The bounds that the analyzer's `ScopeComputer` gives to the signatures of a [command] over [sigs] (the
 * module's `getAllReachableSigs()`) and to sequences, without building a solution. Only the bounds of
 * top-level signatures are offered: [bound] is null for others, and [bound] and [seq] are null for every
 * signature if the analyzer would reject the command's scope.
 */
class AlloyCommandBounds(private val command: Command, private val sigs: List<Sig>) {
    /** Where a bound comes from. */
    sealed interface Source {
        /** Written in the command for this signature. */
        data class Explicit(val scope: CommandScope) : Source

        /**
         * Derived from the scopes of its descendants, as for an abstract signature, or raised to the atoms its
         * descendants must have, such as its `one` subsignatures.
         */
        data object Inherited : Source

        /** The overall scope, `for N`. */
        data object Overall : Source

        /** The default scope of 3, for a command without any scope. */
        data object Default : Source

        /** Fixed by the declaration: a `one`, `lone` or enum signature. */
        data object Fixed : Source
    }

    data class Bound(val n: Int, val exact: Boolean, val source: Source)

    /** The integer bitwidth: the written `int` scope, or the analyzer's default. */
    val bitwidth: Int = if (command.bitwidth < 0) DEFAULT_BITWIDTH else command.bitwidth

    /** The largest integer of the [bitwidth]. */
    val maxInt: Int get() = Util.max(bitwidth)

    /** The smallest integer of the [bitwidth]. */
    val minInt: Int get() = Util.min(bitwidth)

    private val primSigs: List<PrimSig> = sigs.filterIsInstance<PrimSig>().filter { !it.builtin }
    private val scopes = IdentityHashMap<PrimSig, Int>()
    private val sources = IdentityHashMap<PrimSig, Source>()
    private val exact: MutableSet<PrimSig> = Collections.newSetFromMap(IdentityHashMap())

    // The analyzer reports a rejected scope as an `Err`; anything else, such as a cancellation, goes on
    private val valid: Boolean = try {
        compute()
    } catch (_: Err) {
        false
    }

    /** The bound of a top-level, non-builtin [sig]. */
    fun bound(sig: Sig): Bound? {
        if (!valid || sig !is PrimSig || sig.builtin || !sig.isTopLevel) return null
        val n = scopes[sig] ?: return null
        return Bound(n, sig in exact, sources.getValue(sig))
    }

    /**
     * The maximum length of sequences: the written `seq` bound, or else the overall scope or 4, at most the
     * largest integer of the bitwidth.
     */
    val seq: Int?
        get() {
            if (!valid) return null
            if (command.maxseq >= 0) return command.maxseq
            return minOf(if (command.overall >= 0) command.overall else 4, maxInt).coerceAtLeast(0)
        }

    /** `ScopeComputer`'s rules for user signatures, the bitwidth and the sequence bound; false where it reports an error. */
    private fun compute(): Boolean {
        var string = false
        for (entry in command.scope) {
            val sig = entry.sig
            if (sig == Sig.STRING) {
                if (string || !entry.isExact) return false
                string = true
                continue
            }
            if (sig !is PrimSig || sig.builtin || sig.isEnum != null) return false
            if (sig.isSome != null && entry.startingScope < 1) return false
            if (sig.isOne != null && sig.isVariable != null && entry.startingScope < 1) return false
            if (!set(sig, entry.startingScope, Source.Explicit(entry))) return false
            if (entry.isExact) exact += sig
        }
        // Mutable `one` and `lone` sigs may change their atom along a trace, so they are not fixed
        for (sig in primSigs) {
            if (sig.isOne != null && sig.isVariable == null) {
                exact += sig
                if (!set(sig, 1, Source.Fixed)) return false
            } else if (sig.isLone != null && sig.isVariable == null && scopes[sig] != 0) {
                if (!set(sig, 1, Source.Fixed)) return false
            }
        }
        while (true) {
            if (deriveAbstract()) {
                while (deriveAbstract()) continue
                continue
            }
            val overall = deriveOverall() ?: return false
            if (overall) {
                while (deriveOverall() ?: return false) continue
                continue
            }
            val parent = deriveFromParent() ?: return false
            if (parent) {
                while (deriveFromParent() ?: return false) continue
                continue
            }
            break
        }
        // `setBitwidth` and `setMaxSeq`
        if (bitwidth > MAX_BITWIDTH || command.maxseq > maxInt) return false
        for (sig in primSigs) if (sig.isTopLevel) lowerBound(sig)
        return true
    }

    /** False if [sig] already has another scope, which the analyzer rejects. */
    private fun set(sig: PrimSig, n: Int, source: Source): Boolean {
        if (n < 0) return false
        val old = scopes[sig]
        if (old != null) return old == n
        scopes[sig] = n
        sources[sig] = source
        return true
    }

    /** An unscoped abstract sig with every child scoped gets their sum; a scoped one gives a missing child the rest. */
    private fun deriveAbstract(): Boolean {
        var changed = false
        for (sig in primSigs) {
            if (sig.isAbstract == null) continue
            val children = sig.children().toList()
            if (children.isEmpty()) continue
            val missing = children.filter { it !in scopes }
            if (missing.size > 1) continue
            val sum = children.sumOf { scopes[it] ?: 0 }
            val n = scopes[sig]
            val source = if (sig.isEnum != null) Source.Fixed else Source.Inherited
            if (n == null && missing.isEmpty()) {
                set(sig, sum, source)
                changed = true
            } else if (n != null && missing.size == 1) {
                set(missing.single(), maxOf(0, n - sum), Source.Inherited)
                changed = true
            }
        }
        return changed
    }

    /** Null where the analyzer asks for a scope: a top-level sig left unscoped by a command without an overall scope. */
    private fun deriveOverall(): Boolean? {
        val default = command.overall < 0 && command.scope.isEmpty()
        val overall = if (default) 3 else command.overall
        var changed = false
        for (sig in primSigs) {
            if (!sig.isTopLevel || sig in scopes) continue
            if (sig.isEnum != null) {
                set(sig, 0, Source.Fixed)
                continue
            }
            if (overall < 0) return null
            set(sig, overall, if (default) Source.Default else Source.Overall)
            changed = true
        }
        return changed
    }

    /** A sig that is still unscoped gets its parent's scope; null where the parent has none either. */
    private fun deriveFromParent(): Boolean? {
        var changed = false
        var trouble = false
        for (sig in primSigs) {
            if (sig.isTopLevel || sig in scopes) continue
            val parent = scopes[sig.parent]
            if (parent != null) {
                set(sig, parent, Source.Inherited)
                changed = true
            } else {
                trouble = true
            }
        }
        return if (!changed && trouble) null else changed
    }

    /**
     * The atoms that [sig] must have, raising its scope to the sum of its children's; a signature made exact
     * by a module parameter, such as `util/ordering`'s, becomes exact here. A raised scope no longer comes
     * from the overall or default scope, but a written one is still where the user can change it.
     */
    private fun lowerBound(sig: PrimSig): Int {
        var lower = sig.children().toList().sumOf { lowerBound(it) }
        var n = scopes[sig] ?: 0
        if (n < lower) {
            n = lower
            scopes[sig] = n
            if (sources[sig] !is Source.Explicit) sources[sig] = Source.Inherited
        }
        if (sig in command.additionalExactScopes) exact += sig
        if (n > lower && (sig in exact || sig.isTopLevel)) lower = n
        return lower
    }

    companion object {
        /** The bitwidth of a command without `int` scope. */
        const val DEFAULT_BITWIDTH = 4

        /** The largest bitwidth the analyzer accepts. */
        const val MAX_BITWIDTH = 30

        private val KEY = Key.create<Map<Command, AlloyCommandBounds>>("alloy.commandBounds")

        /**
         * The bounds of [command], a command of [model] or of one of its `=>` chains. They are computed once per
         * model for every command, and shared by the inspections of each highlighting pass.
         */
        fun of(model: AlloyModelResult.Model, command: Command): AlloyCommandBounds {
            val all = model.derived(KEY) {
                val sigs = it.module.allReachableSigs.toList()
                it.module.allCommands.flatMap { last -> generateSequence(last) { each -> each.parent } }
                    .associateWithTo(IdentityHashMap()) { each ->
                        ProgressManager.checkCanceled()
                        AlloyCommandBounds(each, sigs)
                    }
            }
            return all[command] ?: AlloyCommandBounds(command, model.module.allReachableSigs.toList())
        }
    }
}
