package com.serranofp.alloy.inspections

import com.serranofp.alloy.AlloyBundle
import edu.mit.csail.sdg.ast.Expr
import edu.mit.csail.sdg.ast.ExprBinary
import edu.mit.csail.sdg.ast.Sig.Field
import edu.mit.csail.sdg.ast.Sig.PrimSig
import edu.mit.csail.sdg.ast.VisitQuery

/**
 * A command whose `seq` bound is smaller than the scope of the elements of a `var` field declared with
 * `seq T`, which then cannot hold one of each. Static `seq` fields are left out: their contents do not grow
 * along a trace, as a queue's do.
 */
class AlloySeqBoundTooSmallInspection : AlloyCommandInspection<List<Pair<Field, PrimSig>>>() {
    override fun prepare(context: AlloyInspectionContext): List<Pair<Field, PrimSig>> = sequenceFields(context)

    override fun check(command: CommandCheck, data: List<Pair<Field, PrimSig>>) {
        val scope = command.scope ?: return
        val seq = command.bounds.seq ?: return
        val short = data.mapNotNull { (field, element) ->
            val bound = command.bounds.bound(element) ?: return@mapNotNull null
            if (seq < bound.n) Triple(field, element, bound.n) else null
        }
        if (short.isEmpty()) return
        val message = short.joinToString("; ") { (field, element, n) ->
            AlloyBundle.message("inspection.seq.message", field.label, seq, n, name(element))
        }
        val range = scope.seqItem?.let(scope::itemRange) ?: scope.range
        val needed = short.maxOf { it.third }
        // Sequence indices are integers, so a longer bound needs a larger bitwidth
        val fixes = if (needed <= command.bounds.maxInt) {
            val text = AlloyBundle.message("inspection.seq.fix", needed)
            arrayOf(AlloyScopeFix(command.decl, command.part, AlloyScopeEdit.SetSeq(needed), text, AlloyBundle.message("inspection.seq.fix.family")))
        } else {
            emptyArray()
        }
        command.context.register(command.holder, command.decl, range, message, *fixes)
    }

    /** The user's `var` fields declared with `seq T`, with the top-level signatures of `T`. */
    private fun sequenceFields(context: AlloyInspectionContext): List<Pair<Field, PrimSig>> =
        context.model.module.allReachableSigs.filter(::isUser).flatMap { sig ->
            sig.fields.filter { it.isVariable != null }.flatMap { field ->
                elements(field.decl().expr).map { field to it }
            }
        }.distinct()

    private fun elements(bound: Expr): Sequence<PrimSig> {
        val sequences = mutableListOf<ExprBinary>()
        bound.accept(object : VisitQuery<Unit>() {
            override fun visit(x: ExprBinary): Unit? {
                if (x.op == ExprBinary.Op.ISSEQ_ARROW_LONE) sequences += x
                return super.visit(x)
            }
        })
        return sequences.asSequence()
            .flatMap { it.right.type().fold() }
            .mapNotNull { it.lastOrNull() }
            .map(::topLevel)
            .filter(::isUser)
            .distinct()
    }
}
