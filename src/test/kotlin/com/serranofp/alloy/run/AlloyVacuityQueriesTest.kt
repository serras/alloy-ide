package com.serranofp.alloy.run

import edu.mit.csail.sdg.alloy4.A4Reporter
import edu.mit.csail.sdg.ast.Command
import edu.mit.csail.sdg.ast.ExprQt
import edu.mit.csail.sdg.ast.ExprUnary
import edu.mit.csail.sdg.parser.CompModule
import edu.mit.csail.sdg.parser.CompUtil
import edu.mit.csail.sdg.translator.A4Options
import edu.mit.csail.sdg.translator.TranslateAlloyToKodkod
import junit.framework.TestCase

class AlloyVacuityQueriesTest : TestCase() {
    private val model = """
        var sig Node { var succ: lone Node }
        var sig E in Node {}
        pred A { some E }
        pred B { no succ }
        pred C {}
        assert implication { A implies B }
        assert quantified { all n: Node | n in E implies B }
        assert temporal { always (A implies B) }
        assert disjunction { A or B }
        assert conditional { A implies B else C }
        assert block { { A implies B } }
        assert conjunction { A implies B and C }
        assert nested { all n: Node | all m: Node | n in E implies m in E }
        assert alwaysAll { always all n: Node | n in E implies B }
        assert twoFormulas { A implies B
          C }
    """.trimIndent()

    private fun parse(commands: String): CompModule = CompUtil.parseEverything_fromString(A4Reporter.NOP, "$model\n$commands")

    private fun check(name: String): Pair<Command, CompModule> {
        val module = parse("check $name for 3")
        return module.allCommands.single() to module
    }

    private fun premise(name: String): String? {
        val (command, _) = check(name)
        return AlloyVacuityQueries.premiseOf(AlloyVacuityQueries.assertionOf(command)!!)?.toString()
    }

    fun testImplicationGivesItsLeftSide() {
        assertEquals("this/A", premise("implication"))
    }

    fun testUniversalQuantifierGivesAnExistential() {
        val (command, _) = check("quantified")
        val premise = AlloyVacuityQueries.premiseOf(AlloyVacuityQueries.assertionOf(command)!!)!!.deNOP()
        assertEquals(ExprQt.Op.SOME, (premise as ExprQt).op)
        assertEquals(listOf("n"), premise.decls.flatMap { decl -> decl.names.map { it.label } })
        assertEquals("one this/Node", premise.decls.single().expr.deNOP().toString())
        assertEquals("n in this/E", premise.sub.toString())
    }

    fun testAlwaysGivesEventually() {
        val (command, _) = check("temporal")
        val premise = AlloyVacuityQueries.premiseOf(AlloyVacuityQueries.assertionOf(command)!!)!!.deNOP()
        assertEquals(ExprUnary.Op.EVENTUALLY, (premise as ExprUnary).op)
        assertEquals("this/A", premise.sub.toString())
    }

    fun testOneElementBlock() {
        assertEquals("this/A", premise("block"))
    }

    fun testNoPremise() {
        assertNull(premise("disjunction"))
        assertNull(premise("conditional"))
        // A block of several formulas is their conjunction, which has no premise
        assertNull(premise("twoFormulas"))
    }

    fun testNestedQuantifiersGiveNestedExistentials() {
        val (command, _) = check("nested")
        val outer = AlloyVacuityQueries.premiseOf(AlloyVacuityQueries.assertionOf(command)!!)!!.deNOP() as ExprQt
        assertEquals(ExprQt.Op.SOME, outer.op)
        assertEquals("n", outer.decls.single().names.single().label)
        val inner = outer.sub.deNOP() as ExprQt
        assertEquals(ExprQt.Op.SOME, inner.op)
        assertEquals("m", inner.decls.single().names.single().label)
        assertEquals("n in this/E", inner.sub.toString())
    }

    fun testAlwaysOfAQuantifierGivesEventuallyAnExistential() {
        val (command, _) = check("alwaysAll")
        val premise = AlloyVacuityQueries.premiseOf(AlloyVacuityQueries.assertionOf(command)!!)!!.deNOP() as ExprUnary
        assertEquals(ExprUnary.Op.EVENTUALLY, premise.op)
        val quantified = premise.sub.deNOP() as ExprQt
        assertEquals(ExprQt.Op.SOME, quantified.op)
        assertEquals("n in this/E", quantified.sub.toString())
    }

    /** `implies` binds looser than `and`: the premise is `A`, the conclusion `B and C`. */
    fun testImplicationWithAConjunction() {
        assertEquals("this/A", premise("conjunction"))
    }

    fun testQueriesKeepTheScopeStepsAndExpect() {
        val module = parse("check implication for 4 but 2 Node, 1..7 steps expect 0")
        val command = module.allCommands.single()
        val queries = AlloyVacuityQueries.of(command, module)
        for (query in listOf(queries.withPremise!!, queries.factsOnly)) {
            assertTrue(query.check)
            assertEquals(4, query.overall)
            assertEquals(1, query.minprefix)
            assertEquals(7, query.maxprefix)
            assertEquals(0, query.expects)
            assertEquals(command.scope.map { it.toString() }, query.scope.map { it.toString() })
            assertSame(command.parent, query.parent)
        }
        assertEquals(module.allReachableFacts.toString(), queries.factsOnly.formula.toString())
    }

    fun testInlineCheckYieldsItsPremise() {
        val module = parse("check { A implies B } for 3")
        val queries = AlloyVacuityQueries.of(module.allCommands.single(), module)
        assertEquals("this/A", queries.premise.toString())
        assertNotNull(queries.withPremise)
    }

    fun testWithoutPremiseOnlyTheFactsAreSolved() {
        val module = parse("check disjunction for 3")
        val queries = AlloyVacuityQueries.of(module.allCommands.single(), module)
        assertNull(queries.premise)
        assertNull(queries.withPremise)
    }

    /** Without a referenced assertion, the negated right operand of the formula is the assertion. */
    fun testAssertionFromTheFormula() {
        val module = parse("check implication for 3")
        val command = module.allCommands.single()
        val anonymous = Command(
            command.pos, null, command.label, command.check, command.overall, command.bitwidth, command.maxseq,
            command.minprefix, command.maxprefix, command.expects, command.scope, command.additionalExactScopes,
            command.commandKeyword, command.formula, command.parent,
        )
        assertEquals("this/A", AlloyVacuityQueries.premiseOf(AlloyVacuityQueries.assertionOf(anonymous)!!).toString())
    }

    /** The queries solve as the vacuity check expects: no node is ever in `E`, so the premise has no instance. */
    fun testQueriesSolve() {
        val module = CompUtil.parseEverything_fromString(
            A4Reporter.NOP,
            "sig Node {}\nsig E in Node {}\nfact { no E }\nassert a { all n: Node | n in E implies no Node }\ncheck a for 3",
        )
        val queries = AlloyVacuityQueries.of(module.allCommands.single(), module)
        fun sat(command: Command) = TranslateAlloyToKodkod.execute_command(A4Reporter.NOP, module.allReachableSigs, command, A4Options()).satisfiable()
        assertFalse(sat(queries.withPremise!!))
        assertTrue(sat(queries.factsOnly))
    }
}
