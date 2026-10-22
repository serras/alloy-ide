package com.serranofp.alloy.run

import edu.mit.csail.sdg.ast.Assert
import edu.mit.csail.sdg.ast.Command
import edu.mit.csail.sdg.ast.Expr
import edu.mit.csail.sdg.ast.ExprBinary
import edu.mit.csail.sdg.ast.ExprList
import edu.mit.csail.sdg.ast.ExprQt
import edu.mit.csail.sdg.ast.ExprUnary
import edu.mit.csail.sdg.parser.CompModule

/**
 * What the vacuity check of a check without counterexample solves: [withPremise], the facts and the
 * assertion's [premise], when the assertion has one; and [factsOnly], the facts alone. Both are copies of the
 * check (`Command.change`), with its scope, steps, `expect` and `=>` parent.
 *
 * Each is weaker than the check's own formula, the facts and the negated assertion, so finding no instance
 * proves that the check passed vacuously within its scope.
 */
class AlloyVacuityQueries(val premise: Expr?, val withPremise: Command?, val factsOnly: Command) {
    companion object {
        /**
         * The premise of an assertion: `A` for `A implies B` (without `else`), `some x: T | A` under
         * `all x: T |`, `eventually A` under `always`, also inside a one-element block. Null otherwise.
         */
        fun premiseOf(assertion: Expr): Expr? = when (val e = assertion.deNOP()) {
            is ExprList if e.op == ExprList.Op.AND && e.args.size == 1 -> premiseOf(e.args[0])
            is ExprQt if e.op == ExprQt.Op.ALL -> premiseOf(e.sub)?.let { ExprQt.Op.SOME.make(e.pos, null, e.decls, it) }
            is ExprUnary if e.op == ExprUnary.Op.ALWAYS -> premiseOf(e.sub)?.let { ExprUnary.Op.EVENTUALLY.make(e.pos, it) }
            is ExprBinary if e.op == ExprBinary.Op.IMPLIES -> e.left
            else -> null
        }

        /**
         * The assertion that the resolved [check] checks: the referenced [Assert], or else the negated right
         * operand of its formula, the facts and the negated assertion. Null when neither is found.
         */
        fun assertionOf(check: Command): Expr? {
            (check.nameExpr?.referenced() as? Assert)?.let { return it.expr }
            val formula = check.formula.deNOP()
            val last = if (formula is ExprList && formula.op == ExprList.Op.AND) formula.args.lastOrNull()?.deNOP() else formula
            return (last as? ExprUnary)?.takeIf { it.op == ExprUnary.Op.NOT }?.sub
        }

        /** The queries for the resolved [check] of [module], the module the check's analysis parsed. */
        fun of(check: Command, module: CompModule): AlloyVacuityQueries {
            val facts = module.allReachableFacts
            val premise = assertionOf(check)?.let(::premiseOf)
            return AlloyVacuityQueries(premise, premise?.let { check.change(facts.and(it)) }, check.change(facts))
        }
    }
}
