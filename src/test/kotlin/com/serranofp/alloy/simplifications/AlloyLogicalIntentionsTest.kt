package com.serranofp.alloy.simplifications

class AlloyLogicalIntentionsTest : AlloyIntentionTestCase() {
    fun testDeMorganBothWays() {
        assertEquals("not p and not q", invoked("not (p or q)", "Apply De Morgan's law"))
        assertEquals("not (p or q)", invoked("not p and not q", "Apply De Morgan's law"))
        assertEquals("not p or not q", invoked("not (p and q)", "Apply De Morgan's law"))
        assertEquals("not (p and q)", invoked("not p or not q", "Apply De Morgan's law"))
    }

    fun testImplicationToDisjunction() {
        assertEquals("no inbox or no outbox", invoked("some inbox implies no outbox", "Replace 'implies' with 'or'", at = "implies"))
    }

    fun testDisjunctionToImplication() {
        assertEquals("p implies q", invoked("not p or q", "Replace 'or' with 'implies'", at = "or"))
        assertEquals("some inbox implies no outbox", invoked("no inbox or no outbox", "Replace 'or' with 'implies'", at = "or"))
        assertNotOffered("p or q", "Replace 'or' with 'implies'", at = "or")
    }

    fun testImplicationWithAnElse() {
        assertNotOffered("p implies q else r", "Replace 'implies' with 'or'", at = "implies")
    }

    fun testResultInsideAnOperator() {
        assertEquals("(not p or q) and r", invoked("(p implies q) and r", "Replace 'implies' with 'or'", at = "implies"))
    }

    fun testNegatingANegationOrAComparison() {
        assertEquals("p or x != y", invoked("not (not p and x = y)", "Apply De Morgan's law", params = "[x, y: Node]"))
    }

    fun testNegatedMultiplicity() {
        myFixture.enableInspections(AlloySimplifiableBooleanInspection())
        assertEquals(
            "some inbox and no outbox",
            invoked("not (some inbox implies some outbox)", "Simplify to 'some inbox and no outbox'"),
        )
    }

    fun testBlockLinesAreNotADisjunction() {
        assertNotOffered("not p\n  not q", "Apply De Morgan's law", at = "q")
    }
}
