package com.serranofp.alloy.inspections

class AlloyStaticPrimeTest : AlloyInspectionTestCase() {
    override val inspection = AlloyStaticPrimeInspection()

    private val network = "sig Node {\n  succ : one Node,\n  var inbox : set Node\n}\nvar sig Active in Node {}\n\n"

    fun testStaticFieldPrimed() {
        myFixture.configureByText("model.als", network + "pred step { inbox' = inbox + succ' }\n")
        assertEquals(listOf("succ'" to "succ is static, so succ' is the same as succ"), problems())
    }

    /** The analyzer's span starts at `succ`, inside the parentheses; the one in the comment is not counted. */
    fun testParenthesesInACommentAreNotBalanced() {
        myFixture.configureByText("model.als", network + "pred step { inbox' = inbox + (succ /* ) */)' }\n")
        assertEquals(listOf("(succ /* ) */)'"), problems().map { it.first })
        removePrime("(succ")
        myFixture.checkResult(network + "pred step { inbox' = inbox + (succ /* ) */) }\n")
        assertEmpty(problems())
    }

    fun testParenthesisOpenedInACommentIsNotBalanced() {
        myFixture.configureByText("model.als", network + "pred step[n: Node] { some (n.succ /* ( */)' }\n")
        assertEquals(listOf("(n.succ /* ( */)'"), problems().map { it.first })
        removePrime("(n.succ")
        myFixture.checkResult(network + "pred step[n: Node] { some (n.succ /* ( */) }\n")
    }

    fun testPredicateParameterPrimed() {
        myFixture.configureByText("model.als", network + "pred unchanged[r: Node -> Node] { r' = r }\n")
        assertEquals(
            listOf(
                "r'" to "r is a parameter, and parameters keep their value across states, so r' is the same as r. " +
                    "A macro, such as let unchanged[r] { r' = r }, primes its argument instead",
            ),
            problems(),
        )
    }

    fun testQuantifiedVariablePrimed() {
        myFixture.configureByText("model.als", network + "fact { all n: Node | n' = n }\n")
        assertEquals(listOf("n'" to "n is a quantified variable, which keeps its value across states, so n' is the same as n"), problems())
    }

    fun testMutableExpressions() {
        myFixture.configureByText(
            "model.als",
            network +
                "pred a[n: Node] { some n.inbox' }\n" +
                "pred b[n: Node] { some (n.succ.inbox)' }\n" +
                "pred c { some Active' }\n" +
                "pred d { let x = inbox | some x' }\n" +
                "let unchanged[x] { x = (x)' }\n" +
                "pred e { unchanged[succ] }\n" +
                "fun boxes: Node -> Node { inbox }\n" +
                "pred f { boxes' = boxes }\n",
        )
        assertEmpty(problems())
    }

    fun testMetaModelIsNotStatic() {
        myFixture.configureByText(
            "model.als",
            "sig File { var shared: set File, succ: set File }\npred keep { all v: var${"$"} - File${"$"}shared | v.value' = v.value }\n",
        )
        assertEmpty(problems())
    }

    fun testEveryKindOfParagraph() {
        myFixture.configureByText(
            "model.als",
            "sig Node { succ: set Node } { succ' = succ }\n" +
                "assert same { Node' = Node }\n" +
                "fact { some succ' }\n" +
                "run { no (Node.succ)' }\n",
        )
        assertEquals(
            listOf(
                "succ'" to "succ is static, so succ' is the same as succ",
                "Node'" to "Node is static, so Node' is the same as Node",
                "succ'" to "succ is static, so succ' is the same as succ",
                "(Node.succ)'" to "Node, succ are static, so (Node.succ)' is the same as (Node.succ)",
            ),
            problems(),
        )
    }

    private fun removePrime(at: String) {
        launch(at, "Remove the prime")
    }

    fun testRemoveThePrime() {
        myFixture.configureByText("model.als", network + "pred step { inbox' = inbox + succ' }\n")
        removePrime("succ'")
        myFixture.checkResult(network + "pred step { inbox' = inbox + succ }\n")
        assertEmpty(problems())
    }

    fun testRemoveThePrimeOfAParenthesizedExpression() {
        myFixture.configureByText("model.als", network + "pred step[n: Node] { some (n.succ)' }\n")
        assertEquals(listOf("(n.succ)'" to "succ is static, so (n.succ)' is the same as (n.succ)"), problems())
        removePrime("(n.succ)'")
        myFixture.checkResult(network + "pred step[n: Node] { some (n.succ) }\n")
        assertEmpty(problems())
    }
}
