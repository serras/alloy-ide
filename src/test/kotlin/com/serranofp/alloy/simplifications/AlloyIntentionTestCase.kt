package com.serranofp.alloy.simplifications

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Tests of the Alloy intentions, over models that start with [header]. */
abstract class AlloyIntentionTestCase : BasePlatformTestCase() {
    protected open val header = """
        sig Node { succ: one Node, var inbox, outbox: set Node, rel: Node -> Node }
        var lone sig P, Q, R {}
        pred p { some P }
        pred q { some Q }
        pred r { some R }

    """.trimIndent()

    /** Configures a model of [header] and `pred subject[params] { formula }`, with the caret at [at] in [formula]. */
    protected fun configure(formula: String, at: String = "", params: String = "") {
        val prefix = "pred subject$params { "
        val offset = formula.indexOf(at)
        assertTrue("$at is not in $formula", offset >= 0)
        myFixture.configureByText("model.als", header + prefix + formula.substring(0, offset) + "<caret>" + formula.substring(offset) + " }\n")
        // The intentions use the model that highlighting computes
        myFixture.doHighlighting()
    }

    /** The texts of the Alloy intentions offered at the caret. */
    protected fun offered(): List<String> = myFixture.availableIntentions.filter { it.familyName in FAMILIES }.map { it.text }

    /** [formula] once the intention [text] is invoked with the caret at [at]. */
    protected fun invoked(formula: String, text: String, at: String = "", params: String = ""): String {
        configure(formula, at, params)
        myFixture.launchAction(myFixture.findSingleIntention(text))
        return myFixture.file.text.removePrefix(header).removePrefix("pred subject$params { ").removeSuffix(" }\n")
    }

    /** Asserts that no intention named [text] is offered with the caret at [at] in [formula]. */
    protected fun assertNotOffered(formula: String, text: String, at: String = "", params: String = "") {
        configure(formula, at, params)
        assertFalse("$text is offered on $formula: ${offered()}", text in offered())
    }

    private companion object {
        val FAMILIES = setOf(
            "Apply De Morgan's law", "Replace 'or' with 'implies'", "Replace 'implies' with 'or'",
            "Move negation into or out of quantifier", "Move negation into or out of temporal operator",
            "Distribute or factor out temporal operator", "Swap join with transpose",
        )
    }
}
