package com.serranofp.alloy.inspections

import edu.mit.csail.sdg.alloy4.Util
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
class AlloySeqBoundTooSmallInspection : AlloyCommandInspection() {
    override fun check(command: CommandCheck) {
        val scope = command.scope ?: return
        val seq = command.bounds.seq
        val short = sequenceFields(command.context).mapNotNull { (field, element) ->
            val bound = command.bounds.bound(element) ?: return@mapNotNull null
            if (seq < bound.n) Triple(field, element, bound.n) else null
        }
        if (short.isEmpty()) return
        val message = short.joinToString("; ") { (field, element, n) ->
            "Sequences in ${field.label} hold at most $seq elements, fewer than the $n ${name(element)} atoms"
        }
        val range = scope.seqItem?.let(scope::itemRange) ?: scope.range
        val needed = short.maxOf { it.third }
        // Sequence indices are integers, so a longer bound needs a larger bitwidth
        val bitwidth = if (command.command.bitwidth < 0) 4 else command.command.bitwidth
        val fixes = if (needed <= Util.max(bitwidth)) {
            arrayOf(AlloyScopeFix(command.decl, command.part, AlloyScopeEdit.SetSeq(needed), "Raise the seq bound to $needed"))
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

    private fun elements(bound: Expr): List<PrimSig> {
        val sequences = mutableListOf<ExprBinary>()
        bound.accept(object : VisitQuery<Unit>() {
            override fun visit(x: ExprBinary): Unit? {
                if (x.op == ExprBinary.Op.ISSEQ_ARROW_LONE) sequences += x
                return super.visit(x)
            }
        })
        return sequences.flatMap { it.right.type().fold() }.mapNotNull { it.lastOrNull() }.map(::topLevel).filter(::isUser).distinct()
    }
}
