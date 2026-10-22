package com.serranofp.alloy.inspections

import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.serranofp.alloy.AlloyBundle
import com.serranofp.alloy.inspections.AlloyCommandInspection.CommandCheck
import com.serranofp.alloy.psi.AlloyTypes
import edu.mit.csail.sdg.alloy4.Util
import edu.mit.csail.sdg.ast.Expr
import edu.mit.csail.sdg.ast.ExprCall
import edu.mit.csail.sdg.ast.ExprConstant
import edu.mit.csail.sdg.ast.Func
import edu.mit.csail.sdg.ast.VisitQuery
import java.util.IdentityHashMap

/**
 * An integer literal outside the integers of a command that depends on it, which then wraps around: the
 * translator does not check the range of literals. A command depends on its formula, which holds the facts, on
 * the signature facts, and on the funcs they call. Each literal gets one problem for all its commands.
 */
class AlloyIntLiteralRangeInspection : AlloyModelInspection() {
    /** A written literal of the file, `-20` included, which the analyzer reads as [value]. */
    private data class Literal(val range: TextRange, val token: PsiElement, val value: Int)

    /** The literals written in an expression, without those of the funcs it calls, which are in [calls]. */
    private class Direct(val literals: List<Literal>, val calls: List<Func>)

    /** A command for which a literal is out of range: it reads it as [wrapped]. */
    private class Affected(val command: CommandCheck, val bitwidth: Int, val wrapped: Int)

    override fun check(context: AlloyInspectionContext, holder: ProblemsHolder) {
        val module = context.model.module
        val sigFacts = module.allReachableSigs.flatMap { it.facts }
        // Facts, signature facts and funcs are shared by the commands, so each is walked once: a command's formula
        // is the conjunction of its body and of the facts, which the analyzer flattens
        val direct = IdentityHashMap<Expr, Direct>()
        val byLiteral = LinkedHashMap<TextRange, Pair<Literal, MutableList<Affected>>>()
        for (command in AlloyCommandInspection.commands(context, holder)) {
            val bounds = command.bounds
            // Without integers, every literal is meaningless
            if (bounds.bitwidth == 0) continue
            for (literal in literals(context, direct, AlloyFrameAnalysis.conjuncts(command.command.formula) + sigFacts)) {
                if (literal.value in bounds.minInt..bounds.maxInt) continue
                val affected = byLiteral.getOrPut(literal.range) { literal to ArrayList() }.second
                affected += Affected(command, bounds.bitwidth, wrap(literal.value, bounds.bitwidth))
            }
        }
        for ((range, entry) in byLiteral.entries.sortedBy { it.key.startOffset }) {
            val (literal, affected) = entry
            val n = literal.value
            // The wrapped value only depends on the bitwidth
            val groups = affected.groupBy { it.bitwidth }
            val message = groups.entries.joinToString("; ") { (bitwidth, group) ->
                val commands = names(context, group.map { it.command })
                val key = if (group.size == 1) "inspection.int.literal.message" else "inspection.int.literal.message.several"
                AlloyBundle.message(key, commands, bitwidth, Util.min(bitwidth).toString(), Util.max(bitwidth).toString(), n.toString(), group.first().wrapped.toString())
            }
            val needed = (1..AlloyCommandBounds.MAX_BITWIDTH).firstOrNull { n in Util.min(it)..Util.max(it) }
            val fixes = if (needed == null) {
                emptyList()
            } else {
                // One fix per bitwidth, for every command that has it
                groups.values.map { group ->
                    val first = group.first().command
                    val text = AlloyBundle.message("inspection.int.literal.fix", names(context, group.map { it.command }), needed)
                    val others = group.drop(1).map { it.command.decl to it.command.part }
                    AlloyScopeFix(first.decl, first.part, AlloyScopeEdit.SetBitwidth(needed), text, AlloyBundle.message("inspection.int.literal.fix.family"), others)
                }
            }
            val paragraph = generateSequence(literal.token) { it.parent }.first { it.parent === context.file }
            context.register(holder, paragraph, range, message, *fixes.toTypedArray())
        }
    }

    /** The written literals of the file in [roots] and the funcs they call, transitively. */
    private fun literals(context: AlloyInspectionContext, direct: IdentityHashMap<Expr, Direct>, roots: List<Expr>): Set<Literal> {
        val found = LinkedHashSet<Literal>()
        val visited = HashSet<Func>()
        val pending = ArrayDeque(roots)
        while (pending.isNotEmpty()) {
            val root = pending.removeFirst()
            val each = direct.getOrPut(root) {
                ProgressManager.checkCanceled()
                walk(context, root)
            }
            found += each.literals
            for (func in each.calls) if (visited.add(func)) pending += func.body
        }
        return found
    }

    private fun walk(context: AlloyInspectionContext, expr: Expr): Direct {
        val literals = ArrayList<Literal>()
        val calls = ArrayList<Func>()
        expr.accept(object : VisitQuery<Unit>() {
            override fun visit(x: ExprConstant): Unit? {
                if (x.op == ExprConstant.Op.NUMBER) literal(context, x)?.let(literals::add)
                return super.visit(x)
            }

            override fun visit(x: ExprCall): Unit? {
                calls += x.`fun`
                return super.visit(x)
            }
        })
        return Direct(literals, calls)
    }

    /**
     * The written literal of [x], if it has one: its position spans a number token, written in any of the forms
     * of the analyzer (`12`, `007`, `0x1F`, `0b1_01`), with the `-` that it folds into it. An
     * analyzer-built number has no token of its own.
     */
    private fun literal(context: AlloyInspectionContext, x: ExprConstant): Literal? {
        if (!context.inFile(x.pos)) return null
        val range = context.range(x.pos)
        if (range.isEmpty) return null
        val token = context.file.findElementAt(range.endOffset - 1)
            ?.takeIf { it.node.elementType == AlloyTypes.NUMBER && it.textRange.endOffset == range.endOffset } ?: return null
        val start = token.textRange.startOffset == range.startOffset ||
            context.file.findElementAt(range.startOffset)?.node?.elementType == AlloyTypes.MINUS
        return if (start) Literal(range, token, x.num) else null
    }

    /** How messages and fixes name [commands]: by their names, or as "the run at line 12". */
    private fun names(context: AlloyInspectionContext, commands: List<CommandCheck>): String {
        val names = commands.map { name(context, it) }
        if (names.size <= MAX_NAMED) return names.joinToString()
        return AlloyBundle.message("inspection.int.literal.more", names.take(MAX_NAMED).joinToString(), names.size - MAX_NAMED)
    }

    private fun name(context: AlloyInspectionContext, command: CommandCheck): String {
        val label = command.command.label
        if ('$' !in label) return label
        val line = context.document.getLineNumber(context.range(command.command.pos).startOffset) + 1
        return AlloyBundle.message(if (command.command.check) "inspection.int.literal.check" else "inspection.int.literal.run", line.toString())
    }

    private companion object {
        /** How many commands a message names before it only counts the rest. */
        const val MAX_NAMED = 3

        /** The integer that [n] stands for with [bitwidth] bits. */
        fun wrap(n: Int, bitwidth: Int): Int {
            val min = Util.min(bitwidth).toLong()
            val size = 1L shl bitwidth
            return (Math.floorMod(n - min, size) + min).toInt()
        }
    }
}
