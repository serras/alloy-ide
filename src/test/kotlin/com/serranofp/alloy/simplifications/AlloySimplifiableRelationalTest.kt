package com.serranofp.alloy.simplifications

class AlloySimplifiableRelationalTest : AlloySimplificationTestCase() {
    override val inspection = AlloySimplifiableRelationalInspection()

    fun testRelationalIdentities() {
        assertEquals(listOf("succ", "n.inbox", "outbox", "*succ", "*succ"), fixed("~~succ", "n.inbox + n.inbox", "outbox & outbox", "^succ + iden", "iden + ^succ", params = "[n: Node]", wrap = "some %s"))
    }

    fun testMutualInclusion() {
        assertEquals(listOf("Node.inbox = Message"), fixed("Node.inbox in Message and Message in Node.inbox"))
        model("fact {\n  Node.inbox in Message\n  Message in Node.inbox\n}\n")
        assertEquals(listOf("Node.inbox in Message\n  Message in Node.inbox" to "Mutual inclusion"), problems())
        assertEquals("fact {\n  Node.inbox = Message\n}\n", fix("Node.inbox in", "Simplify to 'Node.inbox = Message'"))
    }

    fun testDifferentOperands() {
        assertNotReported("n.inbox + n.outbox", "^succ + ^(~succ)", params = "[n: Node]", wrap = "some %s")
    }

    fun testIntegersAreNotRelations() {
        assertNotReported("#Node + #Node > 1")
    }
}
