package com.serranofp.alloy.simplifications

import com.intellij.codeInsight.intention.IntentionActionDelegate
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.serranofp.alloy.inspections.assertTypeChecks

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

    /** The texts of the Alloy rewrite intentions offered at the caret, whatever their family. */
    protected fun offered(): List<String> =
        myFixture.availableIntentions.filter { IntentionActionDelegate.unwrap(it) is AlloyRewriteIntention }.map { it.text }

    /** [formula] once the intention [text] is invoked with the caret at [at]; the model must still type-check. */
    protected fun invoked(formula: String, text: String, at: String = "", params: String = ""): String {
        configure(formula, at, params)
        myFixture.launchAction(myFixture.findSingleIntention(text))
        myFixture.assertTypeChecks()
        return myFixture.file.text.removePrefix(header).removePrefix("pred subject$params { ").removeSuffix(" }\n")
    }

    /**
     * Asserts that no intention named [text] is offered with the caret at [at] in [formula]. Every intention
     * offered is looked at, not only the Alloy ones, so that the assertion does not depend on how they are found.
     */
    protected fun assertNotOffered(formula: String, text: String, at: String = "", params: String = "") {
        configure(formula, at, params)
        val all = myFixture.availableIntentions.map { it.text }
        assertFalse("$text is offered on $formula: $all", text in all)
    }
}
