package com.serranofp.alloy.semantics

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import edu.mit.csail.sdg.alloy4.Pos
import edu.mit.csail.sdg.alloy4.Util
import java.io.File

/**
 * Converts between the analyzer's [Pos] and the IDE's offsets and files. A [Pos] has 1-based lines and
 * columns, counts a tab as one column and includes its end column. It names its file by the canonical path
 * that `CompUtil` uses, or by `/$alloy4$/models/...` for the library modules bundled in the analyzer's JAR.
 */
object AlloyPositions {
    /** The name the analyzer gives [file] when it parses it, or opens it from another module. */
    fun filename(file: VirtualFile): String = Util.canon(file.path)

    fun isLibrary(filename: String): Boolean = filename.startsWith(Util.jarPrefix())

    /**
     * The file that the analyzer calls [filename]. Library modules are read-only files inside the analyzer's
     * JAR; project files are looked up in [projectFiles] (keyed by [filename]) and then on disk.
     */
    fun file(filename: String, projectFiles: Map<String, VirtualFile> = emptyMap()): VirtualFile? =
        if (isLibrary(filename)) libraryFile(filename)
        else projectFiles[filename] ?: LocalFileSystem.getInstance().findFileByPath(filename)

    /** `/$alloy4$/models/util/ordering.als` is `jar://…/org.alloytools.alloy.core-6.2.0.jar!/models/util/ordering.als`. */
    fun libraryFile(filename: String): VirtualFile? =
        jarEntry(filename.removePrefix(Util.jarPrefix()).replace(File.separatorChar, '/'))

    /** The `models/` directory of the analyzer's JAR, which holds every library module. */
    fun libraryRoot(): VirtualFile? = jarEntry("models")

    private fun jarEntry(entry: String): VirtualFile? {
        val jar = PathManager.getJarPathForClass(Util::class.java) ?: return null
        return JarFileSystem.getInstance().findFileByPath(jar + JarFileSystem.JAR_SEPARATOR + entry)
    }

    /** The text that [pos] spans in [document], clamped to the document. */
    fun textRange(pos: Pos, document: Document): TextRange = textRange(pos, DocumentLines(document))

    /** The text that [pos] spans in the text of [lines], clamped to it. */
    internal fun textRange(pos: Pos, lines: AlloyLines): TextRange = textRange(pos, lines as Lines)

    /** The [Pos] in [filename] that spans [range] of [document]; an empty range is one column wide. */
    fun pos(filename: String, document: Document, range: TextRange): Pos = pos(filename, DocumentLines(document), range)

    /** The [Pos] in [filename] that spans [range] of the text of [lines]; an empty range is one column wide. */
    internal fun pos(filename: String, lines: AlloyLines, range: TextRange): Pos = pos(filename, lines as Lines, range)

    private fun textRange(pos: Pos, lines: Lines): TextRange {
        val start = offset(lines, pos.y, pos.x)
        val end = offset(lines, pos.y2, pos.x2 + 1)
        return TextRange(start, maxOf(start, end))
    }

    private fun pos(filename: String, lines: Lines, range: TextRange): Pos {
        val startLine = lines.lineOf(range.startOffset)
        val last = maxOf(range.startOffset, range.endOffset - 1)
        val endLine = lines.lineOf(last)
        return Pos(
            filename,
            range.startOffset - lines.lineStart(startLine) + 1, startLine + 1,
            last - lines.lineStart(endLine) + 1, endLine + 1,
        )
    }

    private fun offset(lines: Lines, line: Int, column: Int): Int {
        if (lines.lineCount == 0) return 0
        val index = (line - 1).coerceIn(0, lines.lineCount - 1)
        return (lines.lineStart(index) + column - 1).coerceAtMost(lines.lineEnd(index))
    }

    /** What the conversions need of a text: a [Document], or the [AlloyLines] of a text the analyzer saw. */
    internal interface Lines {
        val lineCount: Int
        fun lineStart(line: Int): Int
        fun lineEnd(line: Int): Int
        fun lineOf(offset: Int): Int
    }

    private class DocumentLines(private val document: Document) : Lines {
        override val lineCount: Int get() = document.lineCount
        override fun lineStart(line: Int): Int = document.getLineStartOffset(line)
        override fun lineEnd(line: Int): Int = document.getLineEndOffset(line)
        override fun lineOf(offset: Int): Int = document.getLineNumber(offset)
    }
}

/**
 * The line starts of a text, as a [Document] would split it into lines (at `\n`), for the texts the analyzer
 * saw: building a document for each lookup would copy the whole text every time.
 */
internal class AlloyLines(text: CharSequence) : AlloyPositions.Lines {
    val length: Int = text.length
    private val starts: IntArray = buildList {
        add(0)
        text.forEachIndexed { i, c -> if (c == '\n') add(i + 1) }
    }.toIntArray()

    // An empty text has one empty line
    override val lineCount: Int get() = starts.size
    override fun lineStart(line: Int): Int = starts[line]
    override fun lineEnd(line: Int): Int = if (line + 1 < starts.size) starts[line + 1] - 1 else length
    override fun lineOf(offset: Int): Int = starts.binarySearch(offset).let { if (it >= 0) it else -it - 2 }
}
