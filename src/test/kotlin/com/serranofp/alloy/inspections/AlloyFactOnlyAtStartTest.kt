package com.serranofp.alloy.inspections

import com.intellij.codeInsight.intention.IntentionActionDelegate
import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.lang.annotation.HighlightSeverity

class AlloyFactOnlyAtStartTest : AlloyInspectionTestCase() {
    override val inspection = AlloyFactOnlyAtStartInspection()

    private fun weak(text: String, message: String) = Problem(HighlightSeverity.WEAK_WARNING, text, message)

    private fun information(text: String) =
        Problem(HighlightSeverity.INFORMATION, text, "This fact constrains only the first state, because it uses no temporal operator")

    private val network = "sig Node {\n  succ : one Node,\n  var inbox : set Node\n}\n\n"

    private val events = "pred initiate[n : Node] { n.inbox' = n.inbox + n }\n" +
        "pred node_acts[n : Node] { initiate[n] }\n" +
        "pred stutter { inbox' = inbox }\n"

    private val notAlways = ", because the fact is not under always"

    fun testFrameWithoutAlways() {
        myFixture.configureByText("model.als", network + "fact { all n: Node | n.inbox' = n.inbox }\n")
        assertEquals(listOf(weak("n.inbox'", "n.inbox' constrains only the transition out of the first state$notAlways")), detailedProblems())
    }

    fun testEventCalledFromAFact() {
        myFixture.configureByText("model.als", network + events + "fact { some n: Node | initiate[n] }\n")
        assertEquals(
            listOf(weak("initiate[n]", "The event initiate is constrained to happen only in the first transition$notAlways")),
            detailedProblems(),
        )
    }

    fun testEventsUnderAlways() {
        for (fact in listOf(
            "fact { always (stutter or some n: Node | node_acts[n]) }",
            "fact {\n  no inbox\n  always (stutter or some n: Node | node_acts[n])\n}",
            "fact { eventually some n: Node | initiate[n] }",
            "fact { some inbox until (inbox' = inbox) }",
        )) {
            myFixture.configureByText("model.als", "$network$events$fact\n")
            assertEmpty(fact, detailedProblems())
        }
    }

    fun testUnderAfterOnly() {
        myFixture.configureByText("model.als", network + "fact { after (inbox' = inbox) }\nfact { after after some inbox }\n")
        assertEquals(
            listOf(
                weak("after", "This after constrains only the second transition$notAlways"),
                weak("after", "This after constrains only the third state$notAlways"),
            ),
            detailedProblems(),
        )
    }

    fun testAfterWithAFutureOperator() {
        for (fact in listOf(
            "fact { after always (inbox' = inbox) }",
            "fact { after eventually some inbox }",
            "fact { after (some inbox until no inbox) }",
            "fact { after after always some inbox }",
        )) {
            myFixture.configureByText("model.als", "$network$fact\n")
            assertEmpty(fact, detailedProblems())
        }
    }

    fun testPrimeOnAStaticExpression() {
        myFixture.configureByText("model.als", network + "fact { succ' = succ }\n")
        assertEmpty(detailedProblems())
    }

    /** Wrapping would also make `no inbox`, about the first state, an invariant. */
    fun testNoWrapForAFactWithAConjunctAboutTheFirstState() {
        myFixture.configureByText("model.als", network + "fact {\n  no inbox\n  all n: Node | n.inbox' = n.inbox\n}\n")
        assertEquals(listOf(weak("n.inbox'", "n.inbox' constrains only the transition out of the first state$notAlways")), detailedProblems())
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("n.inbox'"))
        assertNull(myFixture.getAvailableIntention("Wrap in always"))
    }

    fun testSequenceOperator() {
        myFixture.configureByText("model.als", network + "fact { no inbox ; some inbox }\n")
        assertEmpty(detailedProblems())
    }

    fun testSignatureFact() {
        myFixture.configureByText("model.als", "sig Node { var inbox : set Node } { lone inbox }\n")
        assertEmpty(detailedProblems())
    }

    fun testOneWarningPerFact() {
        myFixture.configureByText("model.als", network + events + "fact {\n  stutter\n  all n: Node | n.inbox' = n.inbox\n}\n")
        assertEquals(listOf(weak("stutter", "The event stutter is constrained to happen only in the first transition$notAlways")), detailedProblems())
    }

    fun testIntendedInvariant() {
        myFixture.configureByText("model.als", network + "fact inbox_bound { all n: Node | lone n.inbox }\n")
        assertEquals(listOf(information("fact inbox_bound")), detailedProblems())
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("inbox_bound"))
        // The fact may as well be about the initial state
        val wrap = IntentionActionDelegate.unwrap(myFixture.getAvailableIntention("Wrap in always")!!)
        assertEquals(PriorityAction.Priority.LOW, (wrap as PriorityAction).priority)
    }

    fun testInitialStateFactIsNotHighlighted() {
        myFixture.configureByText("model.als", network + "fact init { no inbox }\n")
        val infos = myFixture.doHighlighting().filter { it.inspectionToolId == inspection.shortName }
        assertEquals(listOf(information("fact init")), detailedProblems())
        // Information has no colors, so nothing is drawn in the editor
        for (info in infos) {
            assertEquals(HighlightSeverity.INFORMATION, info.severity)
            val attributes = info.getTextAttributes(null, null)
            assertTrue(attributes == null || listOf(attributes.foregroundColor, attributes.backgroundColor, attributes.effectColor).all { it == null })
        }
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("init"))
        assertNotNull(myFixture.getAvailableIntention("Wrap in always"))
    }

    fun testStaticFact() {
        myFixture.configureByText("model.als", network + "fact { all n: Node | Node in n.^succ }\n")
        assertEmpty(detailedProblems())
    }

    fun testThroughAPredicate() {
        myFixture.configureByText(
            "model.als",
            network + "pred init { no inbox }\npred invariant { lone inbox }\nfact { init }\nfact { always invariant }\n",
        )
        assertEquals(listOf(information("fact")), detailedProblems())
    }

    private fun wrap(at: String) {
        launch(at, "Wrap in always")
    }

    fun testWrapMultiLineBody() {
        myFixture.configureByText("model.als", network + "fact inbox_bound {\n  all n: Node | lone n.inbox\n}\n")
        wrap("inbox_bound")
        myFixture.checkResult(network + "fact inbox_bound {\n  always {\n    all n: Node | lone n.inbox\n  }\n}\n")
        assertEmpty(detailedProblems())
    }

    fun testWrapOneLineBody() {
        myFixture.configureByText("model.als", network + "fact { all n: Node | n.inbox' = n.inbox }\n")
        wrap("n.inbox'")
        myFixture.checkResult(network + "fact { always { all n: Node | n.inbox' = n.inbox } }\n")
        assertEmpty(detailedProblems())
    }

    fun testWrapBodyWithCommentsAndBlankLines() {
        myFixture.configureByText(
            "model.als",
            network + "fact frame {\n    // inbox is kept\n    all n: Node | n.inbox' = n.inbox\n\n    no inbox' & iden\n}\n",
        )
        wrap("n.inbox'")
        myFixture.checkResult(
            network + "fact frame {\n    always {\n        // inbox is kept\n        all n: Node | n.inbox' = n.inbox\n\n" +
                "        no inbox' & iden\n    }\n}\n",
        )
        assertEmpty(detailedProblems())
    }

    fun testWrapPreview() {
        val model = network + "fact { all n: Node | n.inbox' = n.inbox }\n"
        myFixture.configureByText("model.als", model)
        myFixture.doHighlighting()
        myFixture.editor.caretModel.moveToOffset(model.indexOf("n.inbox'"))
        val preview = myFixture.getIntentionPreviewText(myFixture.findSingleIntention("Wrap in always"))
        assertEquals(network + "fact { always { all n: Node | n.inbox' = n.inbox } }\n", preview)
        assertEquals(model, myFixture.editor.document.text)
    }
}
