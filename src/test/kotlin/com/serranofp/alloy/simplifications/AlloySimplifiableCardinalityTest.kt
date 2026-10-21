package com.serranofp.alloy.simplifications

class AlloySimplifiableCardinalityTest : AlloySimplificationTestCase() {
    override val inspection = AlloySimplifiableCardinalityInspection()

    override val header get() = super.header + "var sig leader in Node {}\n"

    fun testCardinalityTests() {
        assertEquals(
            listOf("no inbox", "no inbox", "some inbox", "some inbox", "some inbox", "one leader", "lone leader", "lone leader"),
            fixed("#inbox = 0", "0 = #inbox", "#inbox > 0", "#inbox >= 1", "#inbox != 0", "#leader = 1", "#leader =< 1", "#leader < 2"),
        )
    }

    fun testOtherEquivalentAndMirroredForms() {
        assertEquals(listOf("no inbox", "no inbox", "some inbox", "lone leader"), fixed("#inbox < 1", "#inbox =< 0", "0 < #inbox", "2 > #leader"))
    }

    fun testNegatedMultiplicitiesAndNone() {
        assertEquals(
            listOf("no n.inbox", "some n.inbox", "no n.inbox", "some n.inbox", "no n.inbox"),
            fixed("not some n.inbox", "not no n.inbox", "n.inbox = none", "n.inbox != none", "none = n.inbox", params = "[n: Node]"),
        )
    }

    fun testMessage() {
        model("fact { #inbox = 0 }\n")
        assertEquals(listOf("#inbox = 0" to "Cardinality test"), problems())
        assertEquals("fact { no inbox }\n", fix("#inbox", "Replace with 'no'"))
    }

    fun testOtherCardinalityComparisons() {
        assertNotReported("#inbox = 2", "#inbox > 1", "#inbox = #outbox", "#inbox =< 2")
    }

    fun testOperandKeepsItsParentheses() {
        assertEquals(listOf("no (inbox + outbox)", "some n.inbox.~inbox"), fixed("#(inbox + outbox) = 0", "#n.inbox.~inbox > 0", params = "[n: Node]"))
    }
}
