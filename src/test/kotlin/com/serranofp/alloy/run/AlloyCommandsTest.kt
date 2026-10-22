package com.serranofp.alloy.run

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** How the commands of a file are named and told apart: [AlloyCommands] and [AlloyCommandRef]. */
class AlloyCommandsTest : BasePlatformTestCase() {
    private val model = """
        sig A {}
        pred show {}
        pred first {}
        assert second {}
        assert x {}
        run show for 3
        run this/show
        check x for 3 but 2 A
        run show expect 1
        run { some A } for 2
        run named { some A }
        run first => check second
        check { no A } expect 0
    """.trimIndent()

    private fun refs(): List<AlloyCommandRef> {
        myFixture.configureByText("model.als", model)
        return AlloyCommands.commands(myFixture.file).map(AlloyCommands::ref)
    }

    fun testLabelIsTheNameBeforeTheScope() {
        assertEquals(
            listOf("show", "this/show", "x", "show", null, "named", "first", null),
            refs().map { it.label },
        )
    }

    fun testIndexAndOccurrenceOfEachCommand() {
        val refs = refs()
        assertEquals((0..7).toList(), refs.map { it.index })
        // `this/show` names the same predicate as `show`
        assertEquals(listOf(0, 1, 0, 2, 0, 0, 0, 0), refs.map { it.occurrence })
    }

    fun testDisplayNames() {
        assertEquals(
            listOf("show", "this/show", "x", "show", "command 5", "named", "first", "command 8"),
            refs().map { it.displayName },
        )
    }

    fun testEachRefFindsItsOwnCommand() {
        val refs = refs()
        val commands = AlloyCommands.commands(myFixture.file)
        for ((ref, command) in refs.zip(commands)) {
            assertSame(ref.toString(), command, AlloyCommands.find(myFixture.file, ref))
        }
        assertNull(AlloyCommands.find(myFixture.file, AlloyCommandRef("show", 0, occurrence = 3)))
        assertNull(AlloyCommands.find(myFixture.file, AlloyCommandRef(null, 8)))
    }

    fun testUnlabelledRefsAreEqualByIndexOnly() {
        assertEquals(AlloyCommandRef(null, 4), AlloyCommandRef(null, 4, occurrence = 2))
        assertFalse(AlloyCommandRef(null, 4) == AlloyCommandRef(null, 5))
        assertFalse(AlloyCommandRef(null, 4) == AlloyCommandRef("show", 4))
        assertEquals(AlloyCommandRef("show", 0).hashCode(), AlloyCommandRef("this/show", 9).hashCode())
    }
}
