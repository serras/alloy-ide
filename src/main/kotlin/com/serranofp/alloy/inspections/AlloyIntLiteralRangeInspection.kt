package com.serranofp.alloy.inspections

import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.serranofp.alloy.inspections.AlloyCommandInspection.CommandCheck
import com.serranofp.alloy.psi.AlloyTypes
import edu.mit.csail.sdg.alloy4.Util
import edu.mit.csail.sdg.ast.Expr
import edu.mit.csail.sdg.ast.ExprCall
import edu.mit.csail.sdg.ast.ExprConstant
import edu.mit.csail.sdg.ast.Func
import edu.mit.csail.sdg.ast.VisitQuery

/**
 * An integer literal larger than the largest integer of a command that depends on it, which then wraps around:
 * the translator does not check the range of literals. A command depends on its formula, which holds the
 * facts, on the signature facts, and on the funcs they call. Each literal gets one problem for all its commands.
 */
class AlloyIntLiteralRangeInspection : AlloyModelInspection() {
    /** A command for which a literal is out of range: it reads it as [wrapped]. */
    private class Affected(val command: CommandCheck, val bitwidth: Int, val wrapped: Int)

    override fun check(context: AlloyInspectionContext, holder: ProblemsHolder) {
        val module = context.model.module
        val sigFacts = module.allReachableSigs.flatMap { it.facts }
        val byLiteral = LinkedHashMap<TextRange, Pair<PsiElement, MutableList<Affected>>>()
        for (command in AlloyCommandInspection.commands(context, holder)) {
            val bitwidth = command.command.bitwidth.takeIf { it >= 0 } ?: DEFAULT_BITWIDTH
            // Without integers, every literal is meaningless
            if (bitwidth == 0) continue
            val max = Util.max(bitwidth)
            for ((range, literal) in literals(context, listOf(command.command.formula) + sigFacts, max)) {
                val affected = byLiteral.getOrPut(range) { literal to ArrayList() }.second
                if (affected.none { it.command.decl == command.decl && it.command.part == command.part }) {
                    affected += Affected(command, bitwidth, wrap(literal.text.toInt(), bitwidth))
                }
            }
        }
        for ((range, entry) in byLiteral.entries.sortedBy { it.key.startOffset }) {
            val (literal, affected) = entry
            val n = literal.text.toInt()
            val message = affected.joinToString("; ") {
                "${name(context, it.command)} uses ${it.bitwidth}-bit integers (${Util.min(it.bitwidth)}..${Util.max(it.bitwidth)}), " +
                    "so $n is read as ${it.wrapped}"
            }
            val needed = (1..MAX_BITWIDTH).firstOrNull { Util.max(it) >= n }
            val fixes = if (needed == null) {
                emptyList()
            } else {
                affected.map {
                    val text = "Set the bitwidth of ${name(context, it.command)} to $needed int"
                    AlloyScopeFix(it.command.decl, it.command.part, AlloyScopeEdit.SetBitwidth(needed), text)
                }
            }
            val paragraph = generateSequence(literal) { it.parent }.first { it.parent === context.file }
            context.register(holder, paragraph, range, message, *fixes.toTypedArray())
        }
    }

    /** The written literals of the file larger than [max] in [roots] and the funcs they call, transitively. */
    private fun literals(context: AlloyInspectionContext, roots: List<Expr>, max: Int): Map<TextRange, PsiElement> {
        val found = LinkedHashMap<TextRange, PsiElement>()
        val visited = HashSet<Func>()
        val pending = ArrayDeque(roots)
        val visitor = object : VisitQuery<Unit>() {
            override fun visit(x: ExprConstant): Unit? {
                if (x.op == ExprConstant.Op.NUMBER && x.num > max && context.inFile(x.pos)) {
                    val offset = context.range(x.pos).startOffset
                    // An analyzer-built number has no token of its own
                    val token = context.file.findElementAt(offset)?.takeIf { it.node.elementType == AlloyTypes.NUMBER && it.text == x.num.toString() }
                    if (token != null) found[token.textRange] = token
                }
                return super.visit(x)
            }

            override fun visit(x: ExprCall): Unit? {
                if (visited.add(x.`fun`)) pending += x.`fun`.body
                return super.visit(x)
            }
        }
        while (pending.isNotEmpty()) pending.removeFirst().accept(visitor)
        return found
    }

    /** How messages and fixes name a command: by its name, or as "the run at line 12". */
    private fun name(context: AlloyInspectionContext, command: CommandCheck): String {
        val label = command.command.label
        if ('$' !in label) return label
        val keyword = if (command.command.check) "check" else "run"
        return "the $keyword at line ${context.document.getLineNumber(context.range(command.command.pos).startOffset) + 1}"
    }

    /** The integer that [n] stands for with [bitwidth] bits. */
    fun wrap(n: Int, bitwidth: Int): Int {
        val min = Util.min(bitwidth).toLong()
        val size = 1L shl bitwidth
        return (Math.floorMod(n - min, size) + min).toInt()
    }

    private companion object {
        /** The bitwidth of a command without `int` scope. */
        const val DEFAULT_BITWIDTH = 4

        /** The largest bitwidth the analyzer accepts. */
        const val MAX_BITWIDTH = 30
    }
}
