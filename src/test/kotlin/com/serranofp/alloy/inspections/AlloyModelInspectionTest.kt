package com.serranofp.alloy.inspections

import com.serranofp.alloy.PracticalAlloyCorpus
import java.io.File

class AlloyModelInspectionTest : AlloyInspectionTestCase() {
    override val inspection = AlloyTestInspection()

    override fun getTestDataPath(): String = PracticalAlloyCorpus.TEST_DATA

    /** The message and text of each problem: what the position maps to, and where. */
    private fun problems(): List<Pair<String, String>> = problems(inspection).map { (text, message) -> message to text }

    /**
     * A tab counts as one column for the analyzer, as for the document, and a character outside the BMP as
     * two, its UTF-16 length: the analyzer reads the text as Java chars.
     */
    fun testPositionsAfterTabsAndSupplementaryCharacters() {
        myFixture.configureByText("model.als", "sig A {}\n\t-- 😀 😀\n\tpred p {} /* 😀 */ pred q {}\n\trun q for 3")
        assertEquals(listOf("pred" to "p", "pred" to "q", "command" to "for 3"), problems())
    }
    fun testTypeErrorReportsNothing() {
        myFixture.configureByText("model.als", "sig A {}\npred p { some Bogus }\nrun p for 3")
        assertEmpty(problems())
    }

    fun testTypeCheckedModelIsReported() {
        myFixture.configureByText("model.als", "sig A {}\npred p { some A }\nrun p for 3")
        assertEquals(listOf("pred" to "p", "command" to "for 3"), problems())
    }

    /** Every command of the parser's test file, with the declarations it refers to. */
    fun testCommandsOfTheParserTestFile() {
        val commands = File("$testDataPath/parser/Command.als").readText().lines()
            .filter { it.isNotBlank() && it.trim() != "check" }
        val declarations = "sig A {}\nsig B {}\npred p {}\nassert noCycles {}\npred target {}\npred first {}\nassert second {}"
        myFixture.configureByText("model.als", commands.joinToString("\n") + "\n" + declarations)
        assertEquals(
            listOf(
                "command" to "for 3",
                "command" to "for 3 but 5 Int",
                "command" to "for 5",
                "command" to "for exactly 2 A, 3..4 B, 10 steps",
                "scope item" to "exactly 2 A",
                "scope item" to "3..4 B",
                "command" to "run {}",
                "command" to "run first => check second",
                "pred" to "p",
                "pred" to "target",
                "pred" to "first",
            ),
            problems(),
        )
    }

    fun testScopeOfEachCommandOfAChain() {
        myFixture.configureByText("model.als", "sig A {}\npred p {}\nassert q {}\nrun p for 2 => check q for 3 but 4 A expect 0")
        assertEquals(listOf("pred" to "p", "command" to "for 3 but 4 A", "scope item" to "4 A"), problems())
    }
}
