package com.serranofp.alloy.semantics

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import edu.mit.csail.sdg.alloy4.A4Reporter
import edu.mit.csail.sdg.alloy4.Pos
import edu.mit.csail.sdg.parser.CompUtil

class AlloyExprIndexTest : BasePlatformTestCase() {
    /** The index answers as `CompModule.find` at every position of [model], which spans lines and nests. */
    private fun assertFindsAsTheAnalyzer(model: String) {
        val filename = "/m.als"
        val module = CompUtil.parseEverything_fromFile(A4Reporter.NOP, linkedMapOf(filename to model), filename)
        val index = AlloyExprIndex(module)
        val lines = AlloyLines(model)
        var referenced = 0
        for (offset in model.indices) {
            val line = lines.lineOf(offset)
            val pos = Pos(filename, offset - lines.lineStart(line) + 1, line + 1)
            val expected = module.find(pos)
            assertSame("at $pos", expected, index.find(pos))
            if (expected?.referenced() != null) referenced++
        }
        assertTrue(referenced > 0)
    }

    fun testSameAsTheAnalyzer() = assertFindsAsTheAnalyzer(
        """
        sig Node { next: lone Node, val: one Int }
        one sig Root extends Node {}
        fact acyclic { no n: Node | n in n.^next }
        pred reach[n: Node, m: Node] {
            m in n.*next
            let k = n.next | some k implies
                k.val > 0
        }
        fun succs[n: Node]: set Node { n.^next }
        assert rooted { all n: Node | reach[Root, n] or n = Root }
        run reach for 3
        check rooted for 4 but 3 Int
        """.trimIndent(),
    )

    fun testLongLines() = assertFindsAsTheAnalyzer("sig A { f: set A }\nfact { " + (1..80).joinToString(" and ") { "some A.f" } + " }")
}
