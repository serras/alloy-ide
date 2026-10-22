package com.serranofp.alloy.inspections

import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class AlloyIntLiteralRangeTest : BasePlatformTestCase() {
    private val inspection = AlloyIntLiteralRangeInspection()

    override fun setUp() {
        super.setUp()
        TemplateManagerImpl.setTemplateTesting(testRootDisposable)
        myFixture.enableInspections(inspection)
    }

    private fun problems(): List<Pair<String, String>> =
        myFixture.doHighlighting().filter { it.inspectionToolId == inspection.shortName }.sortedBy { it.startOffset }.map {
            myFixture.file.text.substring(it.startOffset, it.endOffset) to it.description
        }

    /** Applies [fix] at the literal [at], the first occurrence in the file, and returns the number selected for editing. */
    private fun apply(at: String, fix: String): String? {
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf(at))
        myFixture.launchAction(myFixture.findSingleIntention(fix))
        val template = TemplateManagerImpl.getTemplateState(myFixture.editor) ?: return null
        val selected = template.currentVariableRange?.substring(myFixture.editor.document.text)
        template.gotoEnd(false)
        return selected
    }

    private val nodes = "sig Node {}\n"

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
