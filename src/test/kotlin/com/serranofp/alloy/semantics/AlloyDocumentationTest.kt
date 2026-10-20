package com.serranofp.alloy.semantics

import com.intellij.codeInsight.documentation.DocumentationManager
import com.intellij.lang.LanguageDocumentation
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.serranofp.alloy.AlloyLanguage
import com.serranofp.alloy.psi.AlloyFile
import java.util.concurrent.TimeUnit

class AlloyDocumentationTest : BasePlatformTestCase() {
    private fun analyze() {
        ApplicationManager.getApplication().executeOnPooledThread<AlloyModelResult> {
            ReadAction.compute<AlloyModelResult, Throwable> { AlloyModelService.getInstance(project).model(myFixture.file as AlloyFile) }
        }.get(30, TimeUnit.SECONDS)
    }

    /** The documentation at the caret, as Quick Documentation and hover get it. */
    private fun documentation(): String {
        val original = myFixture.file.findElementAt(myFixture.caretOffset)
        val target = DocumentationManager.getInstance(project).findTargetElement(myFixture.editor, myFixture.file)!!
        return LanguageDocumentation.INSTANCE.forLanguage(AlloyLanguage).generateDoc(target, original)!!
    }

    fun testFieldShowsItsDeclarationWithItsType() {
        myFixture.configureByText("m.als", "sig Node { next: set Node }\npred p { some Node.ne<caret>xt }")
        analyze()
        val doc = documentation()
        assertTrue(doc, doc.contains("next"))
        assertTrue(doc, doc.contains("Node-&gt;Node"))
    }

    fun testDocumentedPredicate() {
        myFixture.configureByText(
            "m.als",
            "sig Node {}\n/**\n * Some node exists.\n * Used everywhere.\n */\npred nonEmpty[n: Node] { some n }\nrun { nonEm<caret>pty[Node] } for 3",
        )
        analyze()
        val doc = documentation()
        assertTrue(doc, doc.contains("pred nonEmpty"))
        assertTrue(doc, doc.contains("Some node exists.<br>Used everywhere."))
    }

    fun testModelWithErrorsShowsTheSourceAndTheComment() {
        myFixture.configureByText(
            "m.als",
            "sig Node { next: set Node }\n/** Some node exists. */\npred nonEmpty[n: Node] { some n.next }\nrun { nonEm<caret>pty[Node] and some Undefined } for 3",
        )
        analyze()
        val doc = documentation()
        assertTrue(doc, doc.contains("pred nonEmpty[n: Node]"))
        assertFalse(doc, doc.contains("some n.next"))
        assertTrue(doc, doc.contains("Some node exists."))
    }

    fun testFieldSourceWithItsBound() {
        myFixture.configureByText("m.als", "sig Node { disj a, b: set Node }\npred p { some Node.b<caret> and some Undefined }")
        analyze()
        assertTrue(documentation(), documentation().contains("b: set Node"))
    }

    fun testSignatureSource() {
        myFixture.configureByText("m.als", "/** A node. */\nabstract sig Node extends univ { next: set Node }\npred p { some No<caret>de and some Undefined }")
        analyze()
        val doc = documentation()
        assertTrue(doc, doc.contains("abstract sig Node extends univ"))
        assertFalse(doc, doc.contains("next"))
        assertTrue(doc, doc.contains("A node."))
    }

    fun testLocalBinder() {
        myFixture.configureByText("m.als", "sig Node {}\npred p { all n: Node | some n<caret> }")
        analyze()
        val doc = documentation()
        assertTrue(doc, doc.contains("n: Node"))
    }
}
