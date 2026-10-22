package com.serranofp.alloy.viewer

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.serranofp.alloy.editor.KEYWORDS

class AlloyEvaluatorCompletionTest : BasePlatformTestCase() {
    private fun model(text: String) = link(text).let { AlloySolutionModel(it.solution, module = it.module) }

    /** The names offered for [text], or the one completion inserted. */
    private fun variants(model: AlloySolutionModel, text: String): List<String> {
        myFixture.configureByText("evaluator.als", text)
        myFixture.file.virtualFile.putUserData(ALLOY_EVALUATOR_FRAGMENT) { model }
        myFixture.completeBasic()
        return myFixture.lookupElementStrings ?: listOf(myFixture.editor.document.text)
    }

    fun testFieldName() {
        // `sub` is from util/integer, which every model opens
        assertSameElements(variants(model(MODEL), "su<caret>"), "succ", "sub")
    }

    /** A name may contain `"`, but not start with one. */
    fun testPrefixStartsAtTheName() {
        assertSameElements(variants(model(MODEL), "\"su<caret>"), "succ", "sub")
    }

    fun testAtoms() {
        val model = model(MODEL)
        val atoms = model.solution.allAtoms.map { it.label }.filter { it.startsWith("Node$") }
        assertNotEmpty(atoms)
        assertSameElements(variants(model, "Node$<caret>"), atoms)
    }

    fun testSkolem() {
        assertEquals(listOf("\$show_n"), variants(model(MODEL), "\$sh<caret>"))
    }

    fun testOnlyModelNamesAreOffered() {
        val model = model(MODEL)
        val offered = variants(model, "<caret>")
        assertContainsElements(offered, "Node", "succ", "linked", "\$show_n", "plus")
        assertEmpty(offered.filter { it in KEYWORDS })
        assertSameElements(offered, AlloyEvaluatorCompletionContributor.NamesProvider.names(model).map { it.text })
    }

    fun testOpenedModulesAreQualifiedWhenAmbiguous() {
        val model = model("open util/ordering[A] as ao\nopen util/ordering[B] as bo\nopen util/integer\nsig A {}\nsig B {}\nrun {} for 2")
        val names = AlloyEvaluatorCompletionContributor.NamesProvider.names(model).map { it.text }
        assertContainsElements(names, "A", "ao/first", "bo/first", "ao/Ord", "bo/Ord", "add", "integer/max")
        // `next` is in both orderings and in util/integer
        assertContainsElements(names, "ao/next", "bo/next", "integer/next")
        assertDoesntContain(names, "first", "next", "integer/add", "run$1")
    }

    private companion object {
        const val MODEL = "sig Node { succ: set Node }\npred linked[a, b: Node] { b in a.succ }\nrun show { some n: Node | some n.succ } for 2"
    }
}
