package com.serranofp.alloy.semantics

import com.intellij.openapi.editor.impl.DocumentImpl
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import edu.mit.csail.sdg.alloy4.A4Reporter
import edu.mit.csail.sdg.alloy4.Err
import edu.mit.csail.sdg.alloy4.Pos
import edu.mit.csail.sdg.parser.CompUtil
import java.nio.file.Files

class AlloyPositionsTest : BasePlatformTestCase() {
    private fun errorPos(text: String): Pos =
        try {
            CompUtil.parseEverything_fromString(A4Reporter.NOP, text)
            error("expected an error")
        } catch (e: Err) {
            e.pos
        }

    fun testSingleLineSpan() {
        val text = "sig A { f: Undefined }"
        val range = AlloyPositions.textRange(errorPos(text), DocumentImpl(text))
        assertEquals("Undefined", range.substring(text))
    }

    fun testTabsCountAsOneColumn() {
        val text = "sig A {}\n\tpred p { some\t\tBogus }"
        val range = AlloyPositions.textRange(errorPos(text), DocumentImpl(text))
        assertEquals("Bogus", range.substring(text))
    }

    fun testMultiLineSpan() {
        val text = "sig A {}\nfact {\n  A in\n    A\n}"
        val document = DocumentImpl(text)
        val range = AlloyPositions.textRange(Pos("m.als", 3, 3, 5, 4), document)
        assertEquals("A in\n    A", range.substring(text))
    }

    fun testPositionsOutsideTheDocumentAreClamped() {
        val text = "sig A {}\nsig B {}"
        val range = AlloyPositions.textRange(Pos("m.als", 5, 2, 40, 9), DocumentImpl(text))
        assertEquals(TextRange(13, 17), range)
    }

    fun testRangeToPosRoundTrips() {
        val text = "sig A {}\n\tfact {\n  A in A\n}"
        val document = DocumentImpl(text)
        val range = TextRange(text.indexOf("fact"), text.indexOf("A in A") + 4)
        val pos = AlloyPositions.pos("m.als", document, range)
        assertEquals(Pos("m.als", 2, 2, 6, 3), pos)
        assertEquals(range, AlloyPositions.textRange(pos, document))
    }

    fun testEmptyRangeIsOneColumn() {
        val document = DocumentImpl("sig A {}")
        assertEquals(Pos("m.als", 5, 1, 5, 1), AlloyPositions.pos("m.als", document, TextRange(4, 4)))
    }

    fun testProjectFileFromTheAnalyzersFilename() {
        val file = myFixture.configureByText("model.als", "sig A {}").virtualFile
        val filename = AlloyPositions.filename(file)
        assertFalse(AlloyPositions.isLibrary(filename))
        assertEquals(file, AlloyPositions.file(filename, mapOf(filename to file)))
    }

    fun testProjectFileOnDisk() {
        val dir = Files.createTempDirectory("alloy-positions")
        VfsRootAccess.allowRootAccess(testRootDisposable, dir.toRealPath().toString())
        val path = Files.writeString(dir.resolve("model.als"), "sig A {}")
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
        // The analyzer's canonical path can differ from the VFS one (/var vs /private/var on macOS): the files
        // that were parsed map it back to the same VirtualFile, and anything else is found on disk
        val filename = AlloyPositions.filename(file)
        assertEquals(path.toRealPath().toString(), filename)
        assertEquals(file, AlloyPositions.file(filename, mapOf(filename to file)))
        assertEquals(filename, AlloyPositions.filename(AlloyPositions.file(filename)!!))
    }

    fun testLibraryFileIsInTheAnalyzersJar() {
        val module = CompUtil.parseEverything_fromString(A4Reporter.NOP, "open util/ordering[A]\nsig A {}")
        val ordering = module.allReachableModules.single { it.moduleName == "util/ordering" }
        val filename = ordering.pos().filename
        assertTrue(filename, AlloyPositions.isLibrary(filename))

        val file = AlloyPositions.file(filename)!!
        assertTrue(file.fileSystem is JarFileSystem)
        assertTrue(file.url, file.url.endsWith("!/models/util/ordering.als"))
        assertFalse(file.isWritable)
        assertTrue(String(file.contentsToByteArray()).contains("module util/ordering"))
    }

    fun testUnknownLibraryFile() {
        assertNull(AlloyPositions.libraryFile("/\$alloy4\$/models/util/nothing.als"))
    }
}
