package com.serranofp.alloy.simplifications

class AlloyTemporalIntentionsTest : AlloyIntentionTestCase() {
    private val into = "Move negation into temporal operator"
    private val outOf = "Move negation out of temporal operator"

    fun testNegatedAlways() {
        assertEquals("eventually no inbox", invoked("not always some inbox", into, at = "always"))
        assertEquals("not always some inbox", invoked("eventually no inbox", outOf, at = "eventually"))
    }

    fun testNegatedUntil() {
        assertEquals("no inbox releases some outbox", invoked("not (some inbox until no outbox)", into, at = "until"))
        // Out of it only when both operands are negated
        assertNotOffered("no inbox releases some outbox", outOf, at = "releases")
        assertEquals("not (some inbox until some outbox)", invoked("no inbox releases no outbox", outOf, at = "releases"))
    }

    fun testEveryPair() {
        // Each negated form, its dual, and the dual's operator, where the caret goes to move the negation out
        val pairs = listOf(
            Triple("not always p", "eventually not p", "eventually"),
            Triple("not eventually p", "always not p", "always"),
            Triple("not after p", "after not p", "after"),
            Triple("not historically p", "once not p", "once"),
            Triple("not once p", "historically not p", "historically"),
            Triple("not (p until q)", "not p releases not q", "releases"),
            Triple("not (p releases q)", "not p until not q", "until"),
            Triple("not (p since q)", "not p triggered not q", "triggered"),
            Triple("not (p triggered q)", "not p since not q", "since"),
        )
        for ((negated, dual, operator) in pairs) {
            assertEquals(negated, dual, invoked(negated, into))
            assertEquals(dual, negated, invoked(dual, outOf, at = operator))
        }
    }

    fun testBeforeIsNotSelfDual() {
        assertNotOffered("not before p", into, at = "before")
        assertNotOffered("not before p", outOf, at = "before")
        assertNotOffered("before not p", into, at = "before")
        assertNotOffered("before not p", outOf, at = "before")
    }

    fun testAlwaysOverAConjunction() {
        assertEquals("always some inbox and always lone outbox", invoked("always (some inbox and lone outbox)", "Distribute temporal operator", at = "always"))
        assertEquals("always (some inbox and lone outbox)", invoked("always some inbox and always lone outbox", "Factor out temporal operator", at = "and"))
    }

    fun testEveryDistribution() {
        assertEquals("eventually p or eventually q", invoked("eventually (p or q)", "Distribute temporal operator", at = "eventually"))
        assertEquals("historically p and historically q and historically r", invoked("historically (p and q and r)", "Distribute temporal operator", at = "historically"))
        assertEquals("once p or once q", invoked("once (p or q)", "Distribute temporal operator", at = "once"))
        assertEquals("once (p or q)", invoked("once p or once q", "Factor out temporal operator", at = "or"))
    }

    fun testOperatorsThatDoNotDistribute() {
        assertNotOffered("always (p or q)", "Distribute temporal operator", at = "always")
        assertNotOffered("eventually (p and q)", "Distribute temporal operator", at = "eventually")
        assertNotOffered("always p or always q", "Factor out temporal operator", at = "or")
        assertNotOffered("always p and eventually q", "Factor out temporal operator", at = "and")
    }
}
