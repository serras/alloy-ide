package com.serranofp.alloy.simplifications

class AlloySimplifiableTemporalTest : AlloySimplificationTestCase() {
    override val inspection = AlloySimplifiableTemporalInspection()

    fun testIdempotentOperators() {
        assertEquals(
            listOf("always some inbox", "eventually p", "historically p", "once p"),
            fixed("always always some inbox", "eventually eventually p", "historically historically p", "once once p"),
        )
    }

    fun testAbsorbedOperators() {
        assertEquals(
            listOf("always eventually n.acts", "eventually always n.waits"),
            fixed("eventually always eventually n.acts", "always eventually always n.waits", params = "[n: Node]"),
        )
    }

    fun testMessages() {
        model("fact { always always p }\nfact { eventually always eventually p }\n")
        assertEquals(listOf("always always p" to "Repeated 'always'", "eventually always eventually p" to "Absorbed 'eventually'"), problems())
    }

    fun testOperatorsThatDoNotAbsorb() {
        assertNotReported("after after p", "before before p", "always eventually p", "eventually always p", "always after always p")
    }

    fun testOneFamilyDisabled() {
        myFixture.enableInspections(AlloySimplifiableBooleanInspection())
        myFixture.disableInspections(inspection)
        model("fact { always always p }\nfact { not not q }\n")
        val reported = myFixture.doHighlighting().filter { it.inspectionToolId?.startsWith("AlloySimplifiable") == true }
            .map { myFixture.file.text.substring(it.startOffset, it.endOffset) }
        assertEquals(listOf("not not q"), reported)
    }
}
