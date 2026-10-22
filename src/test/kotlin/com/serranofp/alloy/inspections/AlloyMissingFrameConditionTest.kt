package com.serranofp.alloy.inspections

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class AlloyMissingFrameConditionTest : BasePlatformTestCase() {
    private val inspection = AlloyMissingFrameConditionInspection()

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(inspection)
    }

    private fun problems(): List<Pair<String, String>> =
        myFixture.doHighlighting().filter { it.inspectionToolId == inspection.shortName }.sortedBy { it.startOffset }.map {
            myFixture.file.text.substring(it.startOffset, it.endOffset) to it.description
        }

    private val network = "sig Node {\n  succ : one Node,\n  var inbox : set Node,\n  var outbox : set Node\n}\n\n"

    private val send = "pred send[n : Node] {\n  some i : n.outbox | inbox' = inbox + n.succ->i\n}\n"

    fun testSendMissesOutbox() {
        myFixture.configureByText("model.als", network + send)
        assertEquals(listOf("send" to "send does not constrain the next value of outbox"), problems())
    }

    fun testFramedEventIsNotReported() {
        myFixture.configureByText("model.als", network + "pred send[n : Node] {\n  inbox' = inbox\n  outbox' = outbox\n}\n")
        assertEmpty(problems())
    }

    fun testAddOneFrameCondition() {
        myFixture.configureByText("model.als", network + send)
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("send"))
        myFixture.launchAction(myFixture.findSingleIntention("Add outbox' = outbox"))
        myFixture.checkResult(network + "pred send[n : Node] {\n  some i : n.outbox | inbox' = inbox + n.succ->i\n  outbox' = outbox\n}\n")
        assertEmpty(problems())
    }

    fun testAddAllMissingFrameConditions() {
        val model = "sig Node {\n  var inbox, outbox, seen : set Node\n}\nvar sig Active in Node {}\n\n" +
            "pred receive[n : Node] {\n    n.inbox' = none\n}\n"
        myFixture.configureByText("model.als", model)
        assertEquals(listOf("receive" to "receive does not constrain the next value of Active, outbox, seen"), problems())
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("receive"))
        myFixture.launchAction(myFixture.findSingleIntention("Add all missing frame conditions"))
        myFixture.checkResult(
            "sig Node {\n  var inbox, outbox, seen : set Node\n}\nvar sig Active in Node {}\n\n" +
                "pred receive[n : Node] {\n    n.inbox' = none\n    Active' = Active\n    outbox' = outbox\n    seen' = seen\n}\n",
        )
        assertEmpty(problems())
    }

    fun testOneLineBody() {
        myFixture.configureByText("model.als", "sig Node { var inbox, outbox : set Node }\npred clear { no inbox' }")
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("clear"))
        myFixture.launchAction(myFixture.findSingleIntention("Add outbox' = outbox"))
        myFixture.checkResult("sig Node { var inbox, outbox : set Node }\npred clear { no inbox'\n  outbox' = outbox\n}")
        assertEmpty(problems())
    }

    fun testAmbiguousFieldIsRestrictedToItsSignature() {
        val model = "sig A { var f : set A }\nsig B { var f : set B }\npred e { A <: f' = A <: f }\n"
        myFixture.configureByText("model.als", model)
        assertEquals(listOf("e" to "e does not constrain the next value of B <: f"), problems())
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("e {"))
        myFixture.launchAction(myFixture.findSingleIntention("Add B <: f' = B <: f"))
        assertTrue(myFixture.editor.document.text.contains("pred e { A <: f' = A <: f\n  B <: f' = B <: f\n}"))
        assertEmpty(problems())
    }

    fun testStaticFieldOfTheSameNameMakesItAmbiguous() {
        myFixture.configureByText("model.als", "sig A { var f, g : set A }\nsig B { f : set B }\npred e { g' = g }\n")
        assertEquals(listOf("e" to "e does not constrain the next value of A <: f"), problems())
    }

    /** No signature is `var`, so the `value` fields of the meta model are not `var` either. */
    fun testFrameOverTheMetaModelWithOnlyVarFields() {
        val model = "sig Token {}\nsig File { var shared : set Token, var owner : lone Token }\n" +
            "pred share[t : Token] {\n  shared' = shared + File->t\n  all v : var$ - File\$shared | v.value = v.value'\n}\n" +
            "pred stutter { all v : var$ | v.value = v.value' }\n" +
            "pred unframed[t : Token] { shared' = shared + File->t }\n"
        myFixture.configureByText("model.als", model)
        assertEquals(listOf("unframed" to "unframed does not constrain the next value of owner"), problems())
    }

    private val helper = "pred moveMsg[n : Node] { inbox' = inbox - n->n }\n" +
        "pred send[n : Node] {\n  moveMsg[n]\n  outbox' = outbox\n}\n"

    fun testEventOnlyCalledFromEventsIsNotReported() {
        myFixture.configureByText("model.als", network + helper)
        assertEmpty(problems())
    }

    fun testEventAlsoCalledFromAFactIsReported() {
        myFixture.configureByText("model.als", network + helper + "fact { always (some n : Node | moveMsg[n] or send[n]) }\n")
        assertEquals(listOf("moveMsg" to "moveMsg does not constrain the next value of outbox"), problems())
    }

    fun testTypeErrorReportsNothing() {
        myFixture.configureByText("model.als", network + send + "fact { some Bogus }\n")
        assertEmpty(problems())
    }

    fun testOnlyTheOpenedModuleReportsItsEvents() {
        val a = myFixture.addFileToProject("a.als", "module a\n" + network + send)
        myFixture.configureByText("b.als", "open a\npred keep { inbox' = inbox and outbox' = outbox }\n")
        assertEmpty(problems())
        myFixture.configureFromExistingVirtualFile(a.virtualFile)
        assertEquals(listOf("send"), problems().map { it.first })
    }

    fun testSuppressForPredicate() {
        myFixture.configureByText("model.als", network + send + "\npred forward[n : Node] { inbox' = inbox + n->n }\n")
        assertEquals(listOf("send", "forward"), problems().map { it.first })
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("send"))
        // Offered under each of the problem's fixes
        myFixture.launchAction(myFixture.availableIntentions.first { it.text == "Suppress for predicate" })
        assertTrue(myFixture.editor.document.text.contains("\n// noinspection AlloyMissingFrameCondition\npred send[n : Node] {"))
        assertEquals(listOf("forward"), problems().map { it.first })
    }
}
