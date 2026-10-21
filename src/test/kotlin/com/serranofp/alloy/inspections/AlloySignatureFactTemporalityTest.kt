package com.serranofp.alloy.inspections

import edu.mit.csail.sdg.alloy4.A4Reporter
import edu.mit.csail.sdg.parser.CompUtil
import edu.mit.csail.sdg.translator.A4Options
import edu.mit.csail.sdg.translator.TranslateAlloyToKodkod
import junit.framework.TestCase
import kodkod.engine.satlab.SATFactory

/**
 * Records how the analyzer reads facts over mutable state, which the fact checks rely on: a fact constrains
 * only the first state, while a signature fact over mutable state is put under `always`, so it is not checked.
 */
class AlloySignatureFactTemporalityTest : TestCase() {
    private fun satisfiable(model: String): Boolean {
        val module = CompUtil.parseEverything_fromString(A4Reporter.NOP, model)
        val options = A4Options().apply { solver = SATFactory.get("sat4j") }
        return TranslateAlloyToKodkod.execute_command(A4Reporter.NOP, module.allReachableSigs, module.allCommands.single(), options).satisfiable()
    }

    fun testSignatureFactsHoldInEveryState() {
        val model = "sig A { var f: set A } { lone f }\n"
        assertFalse(satisfiable(model + "run { some a: A | not lone a.f } for 3\n"))
        assertFalse(satisfiable(model + "run { after some a: A | not lone a.f } for 3\n"))
    }

    fun testFactsConstrainOnlyTheFirstState() {
        val model = "sig A { var f: set A }\nfact { all a: A | lone a.f }\n"
        assertFalse(satisfiable(model + "run { some a: A | not lone a.f } for 3\n"))
        assertTrue(satisfiable(model + "run { after some a: A | not lone a.f } for 3\n"))
    }
}
