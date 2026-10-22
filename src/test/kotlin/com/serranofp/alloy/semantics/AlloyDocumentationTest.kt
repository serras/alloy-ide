package com.serranofp.alloy.semantics

import com.intellij.codeInsight.documentation.DocumentationManager
import com.intellij.lang.LanguageDocumentation
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.serranofp.alloy.AlloyLanguage
import com.serranofp.alloy.psi.AlloyBinderDecl
import com.serranofp.alloy.psi.AlloyEnumDecl
import com.serranofp.alloy.psi.AlloyEnumMember
import com.serranofp.alloy.psi.AlloyFieldDecl
import com.serranofp.alloy.psi.AlloyFunDecl
import com.serranofp.alloy.psi.AlloyLetDecl
import com.serranofp.alloy.psi.AlloyNamedDeclaration
import com.serranofp.alloy.psi.AlloyParamDecl
import com.serranofp.alloy.psi.AlloyPredDecl
import com.serranofp.alloy.psi.AlloySigName

class AlloyDocumentationTest : BasePlatformTestCase() {
    private fun analyze() {
        computeModel(myFixture.file)
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
        val doc = documentation()
        assertTrue(doc, doc.contains("b: set Node"))
    }

    fun testSignatureSource() {
        myFixture.configureByText("m.als", "/** A node. */\nabstract sig Node extends univ { next: set Node }\npred p { some No<caret>de and some Undefined }")
        analyze()
        val doc = documentation()
        assertTrue(doc, doc.contains("abstract sig Node extends univ"))
        assertFalse(doc, doc.contains("next"))
        assertTrue(doc, doc.contains("A node."))
    }

    private val provider = AlloyDocumentationProvider()

    private fun <T : AlloyNamedDeclaration> declaration(type: Class<T>, name: String): T =
        PsiTreeUtil.findChildrenOfType(myFixture.file, type).single { it.name == name }

    /** Each declaration named in the first column, by its PSI class, with its exact source and doc comment. */
    private fun assertDocumented(text: String, vararg expected: Triple<Pair<Class<out AlloyNamedDeclaration>, String>, String, String?>) {
        // With an error, as the source is shown when the analyzer has no explanation
        myFixture.configureByText("m.als", "$text\npred broken { some Undefined }")
        val failures = expected.mapNotNull { (declared, source, comment) ->
            val (type, name) = declared
            val declaration = declaration(type, name)
            val actual = provider.source(declaration) to provider.docComment(declaration)
            if (actual == source to comment) null else "$name: expected ${source to comment}, was $actual"
        }
        assertEmpty(failures.joinToString("\n"), failures)
    }

    private val field = AlloyFieldDecl::class.java
    private val parameter = AlloyParamDecl::class.java

    fun testSourceKeepsNestedBrackets() = assertDocumented(
        "sig A { r: A -> A }\nsig B {}\nsig S { f: set (A + B), g: A }\npred p[x: A, y: x.r[A, A], z: A -> (A + B)] {}\n" +
            "enum E { One, Two, Three }\nfact { let u = A, w = (B + A) | some u + w }\nfact { all n: A.r[A, A], m: n.r | some m }",
        Triple(field to "f", "f: set (A + B)", null),
        Triple(field to "g", "g: A", null),
        Triple(parameter to "y", "y: x.r[A, A]", null),
        Triple(parameter to "z", "z: A -> (A + B)", null),
        Triple(AlloyPredDecl::class.java to "p", "pred p[x: A, y: x.r[A, A], z: A -> (A + B)]", null),
        Triple(AlloyEnumMember::class.java to "Two", "Two", null),
        Triple(AlloyLetDecl::class.java to "u", "u = A", null),
        Triple(AlloyLetDecl::class.java to "w", "w = (B + A)", null),
        Triple(AlloyBinderDecl::class.java to "n", "n: A.r[A, A]", null),
        Triple(AlloyBinderDecl::class.java to "m", "m: n.r", null),
    )

    /** The first name of a group shows the whole group, which is how it gets its bound; a later one only itself. */
    fun testSourceOfANameInAGroup() = assertDocumented(
        "sig Node { disj a, b: set Node }\nfact { all x, y: Node | x = y }",
        Triple(field to "a", "a, b: set Node", null),
        Triple(field to "b", "b: set Node", null),
        Triple(AlloyBinderDecl::class.java to "x", "x, y: Node", null),
        Triple(AlloyBinderDecl::class.java to "y", "y: Node", null),
    )

    fun testDocCommentOfAFieldWithModifiersOrInAGroup() = assertDocumented(
        "sig A {\n  /** Contents */ var contents: set A,\n  /** Pair */ disj a, b: A,\n  /** Hidden */ private h: A,\n" +
            "  /** Both */ private var i, j: A,\n  /** Last */ k: A,\n  undocumented: A\n}",
        Triple(field to "contents", "contents: set A", "Contents"),
        Triple(field to "a", "a, b: A", "Pair"),
        Triple(field to "b", "b: A", "Pair"),
        Triple(field to "h", "h: A", "Hidden"),
        Triple(field to "i", "i, j: A", "Both"),
        Triple(field to "j", "j: A", "Both"),
        Triple(field to "k", "k: A", "Last"),
        Triple(field to "undocumented", "undocumented: A", null),
    )

    fun testDocCommentOfParagraphsWithModifiers() = assertDocumented(
        "/** Abstract */\nabstract sig A {}\n/** Several */\none sig B, C extends A {}\n/** Private */\nprivate pred p {}\n/** Enum */\nenum E { M }\n" +
            "// not a doc comment\nfun f: A { A }",
        Triple(AlloySigName::class.java to "A", "abstract sig A", "Abstract"),
        Triple(AlloySigName::class.java to "C", "one sig B, C extends A", "Several"),
        Triple(AlloyPredDecl::class.java to "p", "private pred p", "Private"),
        Triple(AlloyEnumDecl::class.java to "E", "enum E", "Enum"),
        Triple(AlloyFunDecl::class.java to "f", "fun f: A", null),
    )

    fun testExplanationOnlyOfTheDocumentedDeclaration() {
        // As completion asks for an item's documentation, with the caret on another reference
        myFixture.configureByText("m.als", "sig Node {}\n/** Some. */\npred nonEmpty { some Node }\npred other {}\nrun { nonEm<caret>pty } for 3")
        analyze()
        val original = myFixture.file.findElementAt(myFixture.caretOffset)
        val other = declaration(AlloyPredDecl::class.java, "other")
        val doc = LanguageDocumentation.INSTANCE.forLanguage(AlloyLanguage).generateDoc(other, original)!!
        assertTrue(doc, doc.contains("pred other"))
        assertFalse(doc, doc.contains("nonEmpty"))
    }

    fun testLocalBinder() {
        myFixture.configureByText("m.als", "sig Node {}\npred p { all n: Node | some n<caret> }")
        analyze()
        val doc = documentation()
        assertTrue(doc, doc.contains("n: Node"))
    }
}
