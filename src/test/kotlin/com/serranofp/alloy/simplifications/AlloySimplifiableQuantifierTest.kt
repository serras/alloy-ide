package com.serranofp.alloy.simplifications

class AlloySimplifiableQuantifierTest : AlloySimplificationTestCase() {
    override val inspection = AlloySimplifiableQuantifierInspection()

    override val header = """
        sig Node { succ: one Node, var inbox: set Message, id: one Id }
        sig Message {}
        sig Id {}
        pred p[n: Node] { some n.inbox }
        pred p3[x, y, z: Node] { x.succ = y.succ }

    """.trimIndent()

    fun testNegatedQuantifiers() {
        assertEquals(
            listOf("no n: Node | n in n.^succ", "some n: Node | p[n]", "some n: Node | p[n]", "no n: Node | p[n]"),
            fixed("not (some n: Node | n in n.^succ)", "not (no n: Node | p[n])", "not (all n: Node | not p[n])", "all n: Node | not p[n]"),
        )
    }

    fun testNegatedQuantifierInsideAnOperator() {
        model("fact { not (some n: Node | p[n]) and some Node }\n")
        assertEquals("fact { (no n: Node | p[n]) and some Node }\n", fix("not (some", "Simplify to 'no n: Node | p[n]'"))
    }

    fun testNegatedQuantifierBeforeASequence() {
        // Without parentheses, the quantifier's body would take in `; some Node`
        model("pred subject { not (some n: Node | n in n.succ) ; some Node }\n")
        assertEquals("pred subject { (no n: Node | n in n.succ) ; some Node }\n", fix("not (some", "Simplify to 'no n: Node | n in n.succ'"))
    }

    fun testMembershipQuantifiers() {
        assertEquals(
            listOf("Message in Node.inbox", "some Message & Node.inbox", "no Message & Node.inbox", "Message in Node.inbox"),
            fixed("all m: Message | m in Node.inbox", "some m: Message | m in Node.inbox", "no m: Message | m in Node.inbox", "all m: one Message | m in Node.inbox"),
        )
    }

    fun testMembershipThatDependsOnTheVariable() {
        assertNotReported("all n: Node | n in n.succ", "all n: set Node | n in Node.succ", "all n: Node, m: Node | n in m.succ", "all n: lone Node | n in Node.succ")
    }

    fun testDistinctVariables() {
        assertEquals(
            listOf("all disj x, y: Node | x.id != y.id", "some disj x, y: Node | x.id = y.id", "all disj x, y: Node | x.id != y.id", "all disj x, y: Node | x.id != y.id"),
            fixed(
                "all x, y: Node | x != y implies x.id != y.id", "some x, y: Node | x != y and x.id = y.id",
                "all x, y: Node | y != x implies x.id != y.id", "all x, y: Node | not x = y implies x.id != y.id",
            ),
        )
    }

    fun testThreeVariables() {
        assertNotReported("all x, y, z: Node | x != y implies p3[x, y, z]", "all disj x, y: Node | x != y implies x.id != y.id")
    }
}
