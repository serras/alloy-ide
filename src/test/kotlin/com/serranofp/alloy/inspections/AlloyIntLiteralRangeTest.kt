package com.serranofp.alloy.inspections

import com.serranofp.alloy.AlloyBundle

class AlloyIntLiteralRangeTest : AlloyInspectionTestCase() {
    override val inspection = AlloyIntLiteralRangeInspection()

    /** Applies [fix] at the literal [at], the first occurrence in the file, and returns the number selected for editing. */
    private fun apply(at: String, fix: String): String? = launch(at, fix)

    private val nodes = "sig Node {}\n"

    fun testBoundsOfTheRange() {
        val reported = listOf("-9", "-8", "7", "8", "- 9", "0b111", "0b1000", "00").associateWith { literal ->
            myFixture.configureByText("model.als", nodes + "fact { #Node > $literal }\nrun a {}\n")
            problems().map { it.first }
        }
        assertEquals(
            mapOf(
                "-9" to listOf("-9"), "-8" to emptyList(), "7" to emptyList(), "8" to listOf("8"), "- 9" to listOf("- 9"),
                "0b111" to emptyList(), "0b1000" to listOf("0b1000"), "00" to emptyList(),
            ),
            reported,
        )
    }

    fun testLiteralBeyondEveryBitwidth() {
        myFixture.configureByText("model.als", nodes + "fact { #Node < 1000000000 }\nrun a {}\n")
        assertEquals(listOf("1000000000" to "a uses 4-bit integers (-8..7), so 1000000000 is read as 0"), problems())
        // No bitwidth the analyzer allows holds it, so there is no fix
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("1000000000"))
        assertEmpty(myFixture.availableIntentions.filter { it.familyName == AlloyBundle.message("inspection.int.literal.fix.family") })
    }

    fun testLiteralOfACheckedAssertion() {
        myFixture.configureByText("model.als", nodes + "assert small { #Node < 9 }\ncheck small for 3\nassert other { #Node < 10 }\n")
        assertEquals(listOf("9" to "small uses 4-bit integers (-8..7), so 9 is read as -7"), problems())
        assertEquals("5", apply("9", "Set the bitwidth of small to 5 int"))
        myFixture.checkResult(nodes + "assert small { #Node < 9 }\ncheck small for 3 but 5 int\nassert other { #Node < 10 }\n")
    }

    fun testLiteralOfACommandChain() {
        // Each command of the chain has its own bitwidth
        myFixture.configureByText("model.als", nodes + "pred a { #Node < 12 }\npred b { #Node < 9 }\nrun a for 3 but 5 int => run b for 3\n")
        assertEquals(listOf("9" to "b uses 4-bit integers (-8..7), so 9 is read as -7"), problems())
        assertEquals("5", apply("9", "Set the bitwidth of b to 5 int"))
        myFixture.checkResult(nodes + "pred a { #Node < 12 }\npred b { #Node < 9 }\nrun a for 3 but 5 int => run b for 3 but 5 int\n")
    }

    fun testDefaultBitwidth() {
        myFixture.configureByText("model.als", nodes + "fact { #Node =< 8 }\nrun example {} for 3\n")
        assertEquals(listOf("8" to "example uses 4-bit integers (-8..7), so 8 is read as -8"), problems())
    }

    fun testLiteralInAPredicate() {
        myFixture.configureByText("model.als", nodes + "pred big { #Node > 20 }\nrun big for 5 int\nrun small {} for 3\n")
        assertEquals(listOf("20" to "big uses 5-bit integers (-16..15), so 20 is read as -12"), problems())
    }

    fun testSeveralCommands() {
        myFixture.configureByText(
            "model.als",
            nodes + "fact { #Node < 12 }\nrun a {} for 4\nrun b {} for 4 but 5 int\nrun c {} for 4 but 3 int\n",
        )
        assertEquals(
            listOf("12" to "a uses 4-bit integers (-8..7), so 12 is read as -4; c uses 3-bit integers (-4..3), so 12 is read as -4"),
            problems(),
        )
    }

    fun testAnonymousCommandAndNegativeLiteral() {
        myFixture.configureByText("model.als", nodes + "fact { #Node > -8 and #Node < 9 }\nrun {}\n")
        assertEquals(listOf("9" to "the run at line 3 uses 4-bit integers (-8..7), so 9 is read as -7"), problems())
    }

    fun testLiteralInAMacroBody() {
        myFixture.configureByText(
            "model.als",
            nodes + "let many[s] { #s > 10 }\nfact { many[Node] }\npred p { many[Node - Node] }\nrun p for 3\nrun q {} for 3\n",
        )
        assertEquals(
            listOf("10" to "p, q use 4-bit integers (-8..7), so 10 is read as -6"),
            problems(),
        )
    }

    fun testLiteralBelowTheMinimum() {
        myFixture.configureByText("model.als", nodes + "fact { #Node > -20 }\nrun example {} for 3\n")
        assertEquals(listOf("-20" to "example uses 4-bit integers (-8..7), so -20 is read as -4"), problems())
        assertEquals("6", apply("20", "Set the bitwidth of example to 6 int"))
    }

    /** The analyzer rejects `1_000`, which the plugin's lexer accepts; it allows `_` only in hex and binary literals. */
    fun testLiteralsInOtherForms() {
        myFixture.configureByText("model.als", nodes + "fact { #Node < 0x1F and #Node < 0b1010 and #Node < 007 and #Node < 009 }\nrun a {}\n")
        assertEquals(
            listOf(
                "0x1F" to "a uses 4-bit integers (-8..7), so 31 is read as -1",
                "0b1010" to "a uses 4-bit integers (-8..7), so 10 is read as -6",
                "009" to "a uses 4-bit integers (-8..7), so 9 is read as -7",
            ),
            problems(),
        )
    }

    fun testManyCommandsAreSummarized() {
        val commands = listOf("a", "b", "c", "d", "e").joinToString("") { "run $it {} for 3\n" }
        myFixture.configureByText("model.als", nodes + "fact { #Node < 12 }\n" + commands + "run f {} for 3 but 3 int\n")
        assertEquals(
            listOf("12" to "a, b, c and 2 more commands use 4-bit integers (-8..7), so 12 is read as -4; f uses 3-bit integers (-4..3), so 12 is read as -4"),
            problems(),
        )
        // One fix per bitwidth, for all its commands
        assertNull(apply("12", "Set the bitwidth of a, b, c and 2 more commands to 5 int"))
        val raised = listOf("a", "b", "c", "d", "e").joinToString("") { "run $it {} for 3 but 5 int\n" }
        myFixture.checkResult(nodes + "fact { #Node < 12 }\n" + raised + "run f {} for 3 but 3 int\n")
        assertEquals(listOf("12" to "f uses 3-bit integers (-4..3), so 12 is read as -4"), problems())
    }

    fun testCommandWithoutIntegersIsSkipped() {
        myFixture.configureByText("model.als", nodes + "fact { #Node < 12 }\nrun none {} for 3 but 0 int\n")
        assertEmpty(problems())
    }

    fun testSignatureFact() {
        myFixture.configureByText("model.als", "sig Node { n: Int } { n > 9 }\nrun example {} for 3\n")
        assertEquals(listOf("9" to "example uses 4-bit integers (-8..7), so 9 is read as -7"), problems())
    }

    private val several = nodes + "fact { #Node < 12 }\nrun a {} for 4\nrun c {} for 4 but 3 int\n"

    fun testRaiseTheBitwidth() {
        myFixture.configureByText("model.als", several)
        assertEquals("5", apply("12", "Set the bitwidth of a to 5 int"))
        myFixture.checkResult(nodes + "fact { #Node < 12 }\nrun a {} for 4 but 5 int\nrun c {} for 4 but 3 int\n")
        assertEquals(listOf("12" to "c uses 3-bit integers (-4..3), so 12 is read as -4"), problems())
    }

    fun testBitwidthAlreadyWritten() {
        myFixture.configureByText("model.als", several)
        assertEquals("5", apply("12", "Set the bitwidth of c to 5 int"))
        myFixture.checkResult(nodes + "fact { #Node < 12 }\nrun a {} for 4\nrun c {} for 4 but 5 int\n")
    }

    fun testCommandWithoutFor() {
        myFixture.configureByText("model.als", nodes + "fact { #Node < 12 }\nrun a {}\n")
        assertEquals("5", apply("12", "Set the bitwidth of a to 5 int"))
        myFixture.checkResult(nodes + "fact { #Node < 12 }\nrun a {} for 5 int\n")
        assertEmpty(problems())
    }
}
