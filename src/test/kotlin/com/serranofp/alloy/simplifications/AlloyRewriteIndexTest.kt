package com.serranofp.alloy.simplifications

import com.intellij.codeInsight.intention.IntentionActionBean
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.extensions.ExtensionPointName
import com.serranofp.alloy.psi.AlloyFile
import java.util.concurrent.TimeUnit

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

    fun testNotOfferedBeforeHighlighting() {
        // Off the EDT, where the platform asks, the intention does not compute the model itself
        myFixture.configureByText("model.als", header + "pred subject { some Node.inbox <caret>implies p }\n")
        val intention = AlloyImpliesToOrIntention()
        fun available() = ApplicationManager.getApplication().executeOnPooledThread<Boolean> {
            ReadAction.computeBlocking<Boolean, Throwable> { intention.isAvailable(project, myFixture.editor, myFixture.file.findElementAt(myFixture.caretOffset)!!) }
        }.get(60, TimeUnit.SECONDS)
        assertFalse(available())
        myFixture.doHighlighting()
        assertTrue(available())
    }

    fun testIndexAndSourceTextAreKeptWithTheModel() {
        configure("some Node.inbox implies p", at = "implies")
        val document = myFixture.editor.document
        val file = myFixture.file as AlloyFile
        assertTrue(
            myFixture.onModel { source, model ->
                source === AlloySourceText.of(model, file, document) && AlloyRewriteIndex.of(model, file, document) === AlloyRewriteIndex.of(model, file, document)
            },
        )
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
