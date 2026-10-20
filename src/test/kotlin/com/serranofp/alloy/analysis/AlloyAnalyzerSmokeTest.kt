package com.serranofp.alloy.analysis

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import edu.mit.csail.sdg.alloy4.A4Reporter
import edu.mit.csail.sdg.parser.CompUtil
import edu.mit.csail.sdg.translator.A4Options
import edu.mit.csail.sdg.translator.TranslateAlloyToKodkod
import kodkod.engine.satlab.SATFactory

/** The bundled analyzer loads and runs inside the IDE process. */
class AlloyAnalyzerSmokeTest : BasePlatformTestCase() {
    private val model = """
        sig Node { next: lone Node }
        run someNodes { some Node } for 3
        check noSelfLoops { no n: Node | n.next = n } for 3
    """.trimIndent()

    private fun execute(commandName: String): Boolean {
        val module = CompUtil.parseEverything_fromString(A4Reporter.NOP, model)
        val command = module.allCommands.single { it.label == commandName }
        val options = A4Options().apply { solver = SATFactory.get("sat4j") }
        return TranslateAlloyToKodkod.execute_command(A4Reporter.NOP, module.allReachableSigs, command, options).satisfiable()
    }

    fun testRunFindsInstanceWithSat4j() {
        assertTrue(execute("someNodes"))
    }

    fun testCheckFindsCounterexampleWithSat4j() {
        assertTrue(execute("noSelfLoops"))
    }
}
