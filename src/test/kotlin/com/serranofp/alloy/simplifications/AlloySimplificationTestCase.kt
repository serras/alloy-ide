package com.serranofp.alloy.simplifications

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.serranofp.alloy.AlloyBundle

/** Tests of a simplification [inspection], over models that start with [header]. */
abstract class AlloySimplificationTestCase : BasePlatformTestCase() {
    protected abstract val inspection: AlloySimplificationInspection

    protected open val header = """
        sig Node { succ: one Node, var inbox, outbox: set Message, id: one Id }
        sig Message {}
        sig Id {}
        var lone sig P, Q, R {}
        pred p { some P }
        pred q { some Q }
        pred r { some R }
        pred a { some Node.inbox }
        pred b { some Node.outbox }
        pred c { lone Node }
        pred acts[n: Node] { some n.inbox }
        pred waits[n: Node] { some n.outbox }

    """.trimIndent()

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(inspection)
    }

    /** Configures a model of [header] and [text]. */
    protected fun model(text: String) {
        myFixture.configureByText("model.als", header + text)
    }

    /** The text and message of each problem reported by [inspection]. */
    protected fun problems(): List<Pair<String, String>> =
        myFixture.doHighlighting().filter { it.inspectionToolId == inspection.shortName }.sortedBy { it.startOffset }.map {
            myFixture.file.text.substring(it.startOffset, it.endOffset) to it.description
        }

    /** The text of each problem reported by [inspection]. */
    protected fun reported(): List<String> = problems().map { it.first }

    /** Applies the fix named [fix] of the problem that starts at [at], and returns the text of the model after [header]. */
    protected fun fix(at: String, fix: String): String {
        val offset = myFixture.file.text.indexOf(at, header.length)
        assertTrue("$at is not in the model", offset >= 0)
        myFixture.doHighlighting()
        myFixture.editor.caretModel.moveToOffset(offset)
        myFixture.launchAction(myFixture.findSingleIntention(fix))
        return myFixture.file.text.removePrefix(header)
    }

    /**
     * For each of [formulas], written where `%s` is in [wrap] in a predicate of its own, the text after its
     * single quick-fix, without the rest of [wrap].
     */
    protected fun fixed(vararg formulas: String, params: String = "", wrap: String = "%s"): List<String> = formulas.map { formula ->
        val (before, after) = wrap.substringBefore("%s") to wrap.substringAfter("%s")
        val prefix = "pred subject$params { $before"
        model("$prefix$formula$after }\n")
        val problems = problems()
        assertEquals("$formula: $problems", listOf(formula), problems.map { it.first })
        myFixture.editor.caretModel.moveToOffset(header.length + prefix.length)
        val fixes = myFixture.availableIntentions.filter { it.familyName == AlloyBundle.message("simplification.fix.family") }
        assertEquals("$formula: ${fixes.map { it.text }}", 1, fixes.size)
        myFixture.launchAction(fixes.single())
        myFixture.file.text.removePrefix(header).removePrefix(prefix).removeSuffix("$after }\n")
    }

    /** Asserts that [inspection] reports nothing for any of [formulas]. */
    protected fun assertNotReported(vararg formulas: String, params: String = "", wrap: String = "%s") {
        for (formula in formulas) {
            model("pred subject$params { ${wrap.replace("%s", formula)} }\n")
            assertEmpty(formula, problems())
        }
    }
}
