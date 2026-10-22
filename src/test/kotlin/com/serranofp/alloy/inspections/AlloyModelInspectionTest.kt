package com.serranofp.alloy.inspections

import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/** Reports what the analyzer's positions of a file map to: commands, their scopes and scope items, and predicates. */
class AlloyTestInspection : AlloyModelInspection() {
    override fun getShortName(): String = "AlloyTest"

    override fun getDisplayName(): String = "Alloy test"

    override fun getGroupDisplayName(): String = "Alloy"

    override fun check(context: AlloyInspectionContext, holder: ProblemsHolder) {
        for (command in context.model.module.allCommands) {
            if (!context.inFile(command.pos)) continue
            val decl = context.commandDecl(command.pos) ?: continue
            val scope = AlloyScopeText.of(decl, context.part(decl, command.pos))
            context.register(holder, decl, scope?.range ?: decl.textRange, "command", type = WEAK)
            for (scope in command.scope) {
                context.scopeItemRange(decl, scope.pos)?.let { context.register(holder, decl, it, "scope item", type = WEAK) }
            }
        }
        for (func in context.model.module.allFunc) {
            val decl = context.predDecl(func.pos) ?: continue
            val name = decl.nameIdentifier ?: continue
            context.register(holder, decl, name.textRange, "pred", type = WEAK)
        }
    }

    private companion object {
        val WEAK = ProblemHighlightType.WEAK_WARNING
    }
}

class AlloyModelInspectionTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(AlloyTestInspection())
    }

    private fun problems(): List<Pair<String, String>> =
        myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING)
            .filter { it.severity == HighlightSeverity.WEAK_WARNING }
            .sortedBy { it.startOffset }
            .map { it.description to myFixture.file.text.substring(it.startOffset, it.endOffset) }

    fun testTypeErrorReportsNothing() {
        myFixture.configureByText("model.als", "sig A {}\npred p { some Bogus }\nrun p for 3")
        assertEmpty(problems())
    }

    fun testTypeCheckedModelIsReported() {
        myFixture.configureByText("model.als", "sig A {}\npred p { some A }\nrun p for 3")
        assertEquals(listOf("pred" to "p", "command" to "for 3"), problems())
    }

    /** Every command of the parser's test file, with the declarations it refers to. */
    fun testCommandsOfTheParserTestFile() {
        val commands = File("src/test/testData/parser/Command.als").readText().lines()
            .filter { it.isNotBlank() && it.trim() != "check" }
        val declarations = "sig A {}\nsig B {}\npred p {}\nassert noCycles {}\npred target {}\npred first {}\nassert second {}"
        myFixture.configureByText("model.als", commands.joinToString("\n") + "\n" + declarations)
        assertEquals(
            listOf(
                "command" to "for 3",
                "command" to "for 3 but 5 Int",
                "command" to "for 5",
                "command" to "for exactly 2 A, 3..4 B, 10 steps",
                "scope item" to "exactly 2 A",
                "scope item" to "3..4 B",
                "command" to "run {}",
                "command" to "run first => check second",
                "pred" to "p",
                "pred" to "target",
                "pred" to "first",
            ),
            problems(),
        )
    }

    fun testScopeOfEachCommandOfAChain() {
        myFixture.configureByText("model.als", "sig A {}\npred p {}\nassert q {}\nrun p for 2 => check q for 3 but 4 A expect 0")
        assertEquals(listOf("pred" to "p", "command" to "for 3 but 4 A", "scope item" to "4 A"), problems())
    }
}
