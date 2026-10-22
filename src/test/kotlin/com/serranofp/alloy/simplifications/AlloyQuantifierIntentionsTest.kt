package com.serranofp.alloy.simplifications

class AlloyQuantifierIntentionsTest : AlloyIntentionTestCase() {
    fun testNegationMovedInAndOut() {
        assertEquals("some n: Node | no n.inbox", invoked("not (all n: Node | some n.inbox)", "Move negation into quantifier", at = "all"))
        assertEquals("not (all n: Node | some n.inbox)", invoked("some n: Node | no n.inbox", "Move negation out of quantifier", at = "some"))
    }

    fun testNegatedSome() {
        // A comparison is negated by its negated form
        assertEquals("all n: Node | n.succ != n", invoked("not (some n: Node | n.succ = n)", "Move negation into quantifier", at = "some"))
        assertEquals("not (some n: Node | n.succ = n)", invoked("all n: Node | n.succ != n", "Move negation out of quantifier", at = "all"))
    }

    fun testNegationMovedInBeforeASequence() {
        // The parentheses are kept, although the analyzer ends the body before the `;` (see AlloyRewriteRenderTest)
        assertEquals("(some n: Node | no n.inbox) ; p", invoked("not (all n: Node | some n.inbox) ; p", "Move negation into quantifier", at = "all"))
    }

    fun testNotOfferedWithoutANegation() {
        assertNotOffered("all n: Node | some n.inbox", "Move negation out of quantifier", at = "all")
        assertNotOffered("no n: Node | some n.inbox", "Move negation out of quantifier", at = "no")
    }
}
