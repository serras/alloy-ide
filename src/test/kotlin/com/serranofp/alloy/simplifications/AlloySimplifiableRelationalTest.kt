package com.serranofp.alloy.simplifications

class AlloySimplifiableRelationalTest : AlloySimplificationTestCase() {
    override val inspection = AlloySimplifiableRelationalInspection()

    fun testRelationalIdentities() {
        assertEquals(listOf("succ", "n.inbox", "outbox", "*succ", "*succ"), fixed("~~succ", "n.inbox + n.inbox", "outbox & outbox", "^succ + iden", "iden + ^succ", params = "[n: Node]", wrap = "some %s"))
    }

    fun testOperandsOfABoxJoin() {
        model("pred subject[n: Node] { some (succ + succ)[n] and some inbox[n + n] }\n")
        assertEquals(listOf("(succ + succ)", "n + n"), reported())
        assertEquals("pred subject[n: Node] { some succ[n] and some inbox[n + n] }\n", fix("(succ +", "Simplify to 'succ'"))
        assertEquals("pred subject[n: Node] { some succ[n] and some inbox[n] }\n", fix("n + n", "Simplify to 'n'"))
    }

    fun testMutualInclusion() {
        assertEquals(listOf("Node.inbox = Message"), fixed("Node.inbox in Message and Message in Node.inbox"))
        model("fact {\n  Node.inbox in Message\n  Message in Node.inbox\n}\n")
        assertEquals(listOf("Node.inbox in Message\n  Message in Node.inbox" to "Mutual inclusion"), problems())
        assertEquals("fact {\n  Node.inbox = Message\n}\n", fix("Node.inbox in", "Simplify to 'Node.inbox = Message'"))
    }

    fun testMutualInclusionAfterAPairThatCannotBeRewritten() {
        // The first pair would drop the `}` of the inner block, which the analyzer flattens into the outer one
        model("fact {\n  { some Node\n    Node.inbox in Message }\n  Message in Node.inbox\n  Node.inbox in Message\n}\n")
        assertEquals(listOf("Message in Node.inbox\n  Node.inbox in Message" to "Mutual inclusion"), problems())
    }

    fun testReceiverOfACall() {
        // `e.g` is `g[e]` to the analyzer, but its receiver is written left of a `.`, not in brackets
        model("fun g[s: Node -> Node]: set Node { s.Node }\npred subject { some (Node->Node + Node->Node).g }\n")
        assertEquals(
            "fun g[s: Node -> Node]: set Node { s.Node }\npred subject { some (Node->Node).g }\n",
            fix("(Node->Node +", "Simplify to 'Node->Node'"),
        )
    }

    fun testDifferentOperands() {
        assertNotReported("n.inbox + n.outbox", "^succ + ^(~succ)", params = "[n: Node]", wrap = "some %s")
    }

    fun testIntegersAreNotRelations() {
        assertNotReported("#Node + #Node > 1")
    }
}
