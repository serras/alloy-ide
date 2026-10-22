package com.serranofp.alloy.simplifications

class AlloyTransposeJoinIntentionTest : AlloyIntentionTestCase() {
    private val swap = "Swap join with transpose"

    fun testJoinSwapped() {
        assertEquals("some succ.n", invoked("some n.~succ", swap, at = ".", params = "[n: Node]"))
        assertEquals("some n.~succ", invoked("some succ.n", swap, at = ".", params = "[n: Node]"))
    }

    fun testOperandsKeepTheirParentheses() {
        assertEquals("some succ.(Node.succ)", invoked("some (Node.succ).~succ", swap, at = ".~"))
    }

    fun testTransposeIsDroppedRatherThanDoubled() {
        assertEquals("some n.succ", invoked("some (~succ).n", swap, at = ".n", params = "[n: Node]"))
    }

    fun testOtherJoins() {
        // `rel` is ternary
        assertNotOffered("some rel.n", swap, at = ".", params = "[n: Node]")
        assertNotOffered("some succ.succ", swap, at = ".")
    }
}
