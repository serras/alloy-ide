package com.serranofp.alloy.viewer

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.serranofp.alloy.viewer.EvaluationResult.Bool
import com.serranofp.alloy.viewer.EvaluationResult.Error
import com.serranofp.alloy.viewer.EvaluationResult.IntValue
import com.serranofp.alloy.viewer.EvaluationResult.Tuples
import edu.mit.csail.sdg.alloy4.Err
import edu.mit.csail.sdg.parser.CompUtil
import edu.mit.csail.sdg.translator.A4Solution

class AlloyEvaluatorTest : BasePlatformTestCase() {
    private fun model(text: String): AlloySolutionModel = link(text).let { AlloySolutionModel(it.solution, module = it.module) }

    private fun AlloySolutionModel.tuples(text: String): List<String> = (evaluate(text) as Tuples).tuples

    private fun AlloySolutionModel.error(text: String): String = (evaluate(text) as Error).message

    fun testRelationInTheAnalyzersOrder() {
        val model = model(STATIC)
        val succ = model.solution.allReachableSigs.flatMap { it.fields }.single { it.label == "succ" }
        val expected = model.solution.eval(succ).map { it.toString() }
        assertFalse(expected.isEmpty())
        assertEquals(expected, model.tuples("succ"))
    }

    fun testFormulaIntegerAndEmptySet() {
        val model = model(STATIC)
        assertEquals(Bool(true), model.evaluate("some succ"))
        assertEquals(IntValue(model.tuples("Node").size.toString()), model.evaluate("#Node"))
        assertEquals(Tuples(emptyList()), model.evaluate("none"))
    }

    fun testUnknownName() {
        val message = model(STATIC).error("Undefined")
        assertTrue(message, message.contains("\"Undefined\"") && message.contains("cannot be found"))
    }

    fun testSyntaxErrorIsTheAnalyzers() {
        val link = link(STATIC)
        val expected = try {
            CompUtil.parseOneExpression_fromString(link.module, "some (succ")
            null
        } catch (e: Err) {
            e.msg
        }
        assertNotNull(expected)
        assertEquals(expected, AlloySolutionModel(link.solution, module = link.module).error("some (succ"))
    }

    fun testHigherOrderQuantification() {
        assertEquals(AlloySolutionModel.HIGHER_ORDER, model(STATIC).error("some r: Node -> Node | r = succ"))
    }

    fun testNoModule() {
        assertEquals(Error(AlloySolutionModel.EVALUATION_UNAVAILABLE), AlloySolutionModel(solve(STATIC)).evaluate("succ"))
    }

    fun testTemporalAtTheSelectedState() {
        val model = model(LASSO)
        assertEquals(listOf("V1$0"), model.tuples("Cur"))
        assertEquals(listOf("V2$0"), model.tuples("Cur'"))
        assertEquals(Bool(true), model.evaluate("after Cur = V2"))
        assertEquals(Bool(false), model.evaluate("always Cur in V2 + V3"))
        model.goTo(1)
        assertEquals(listOf("V2$0"), model.tuples("Cur"))
        assertEquals(Bool(true), model.evaluate("always Cur in V2 + V3"))
        assertEquals(Bool(false), model.evaluate("always Cur in V2 + V3", state = 0))
    }

    fun testPredicateCallAndSkolem() {
        val model = model("sig Node { succ: set Node }\npred linked[a, b: Node] { b in a.succ }\nrun show { some n: Node | some n.succ } for 2")
        val nodes = model.tuples("Node")
        val (a, b) = nodes.first() to nodes.last()
        assertEquals(model.evaluate("$b in $a.succ"), model.evaluate("linked[$a, $b]"))
        assertInstanceOf(model.evaluate("linked[$a, $b]"), Bool::class.java)
        val successors = model.tuples("\$show_n.succ")
        assertFalse(successors.isEmpty())
        assertEquals(model.tuples("\$show_n").single().let { n -> model.tuples("succ").filter { it.startsWith("$n->") }.map { it.removePrefix("$n->") } }, successors)
    }

    fun testAtomOfAnotherSolutionIsNotFound() {
        val first = link("sig A {}\nrun {} for 3")
        val solutions = generateSequence(first.solution) { it.fork(-3).takeIf { next -> next !== it && next.satisfiable() } }.take(20).toList()
        fun atoms(solution: A4Solution) = solution.allAtoms.map { it.label }.toSet()
        val larger = solutions.maxBy { atoms(it).size }
        val smaller = solutions.minBy { atoms(it).size }
        val atom = (atoms(larger) - atoms(smaller)).first()
        assertEquals(Tuples(listOf(atom)), AlloySolutionModel(larger, module = first.module).evaluate(atom))
        val message = AlloySolutionModel(smaller, module = first.module).error(atom)
        assertTrue(message, message.contains("cannot be found"))
    }
}
