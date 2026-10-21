package com.serranofp.alloy.simplifications

import com.intellij.codeInsight.intention.IntentionActionBean
import com.intellij.openapi.extensions.ExtensionPointName

class AlloyRewriteIndexTest : AlloyIntentionTestCase() {
    fun testOfferedAfterHighlighting() {
        configure("some Node.inbox implies p", at = "implies")
        assertTrue(offered().toString(), "Replace 'implies' with 'or'" in offered())
    }

    fun testNotOfferedWithATypeError() {
        myFixture.configureByText("model.als", header + "pred subject { some Node.inbox <caret>implies p }\nfact { some Bogus }\n")
        myFixture.doHighlighting()
        assertEmpty(offered())
    }

    fun testInnermostNodeAroundTheCaret() {
        // Both `implies` are rewritten by the intention: the inner one is around the caret
        assertEquals("p implies (not q or r)", invoked("p implies (q implies r)", "Replace 'implies' with 'or'", at = "q implies"))
        assertEquals("not p or (q implies r)", invoked("p implies (q implies r)", "Replace 'implies' with 'or'", at = "p implies"))
    }

    fun testIntentionsAreRegisteredInTheAlloyCategory() {
        val beans = ExtensionPointName.create<IntentionActionBean>("com.intellij.intentionAction").extensionList
            .filter { it.className.startsWith("com.serranofp.alloy.") }
        assertTrue(beans.all { it.categories?.toList() == listOf("Alloy") && it.language == "Alloy" })
        val alloy = beans.map { it.className.substringAfterLast('.') }
        assertSameElements(
            alloy,
            "AlloyDeMorganIntention", "AlloyOrToImpliesIntention", "AlloyImpliesToOrIntention", "AlloyQuantifierNegationIntention",
            "AlloyTemporalNegationIntention", "AlloyTemporalDistributionIntention", "AlloyTransposeJoinIntention",
        )
        for (name in alloy) {
            for (file in listOf("description.html", "before.als.template", "after.als.template")) {
                assertNotNull("$name has no $file", javaClass.classLoader.getResource("intentionDescriptions/$name/$file"))
            }
        }
    }

    fun testPreviewShowsTheRewrite() {
        configure("some Node.inbox implies p", at = "implies")
        val preview = myFixture.getIntentionPreviewText(myFixture.findSingleIntention("Replace 'implies' with 'or'"))
        // The diff of the rewritten formula
        assertEquals("no Node.inbox or p", preview)
    }
}
