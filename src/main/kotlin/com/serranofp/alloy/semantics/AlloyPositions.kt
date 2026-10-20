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
    fun textRange(pos: Pos, document: Document): TextRange {
        val start = offset(document, pos.y, pos.x)
        val end = offset(document, pos.y2, pos.x2 + 1)
        return TextRange(start, maxOf(start, end))
    }

    /** The [Pos] in [filename] that spans [range] of [document]; an empty range is one column wide. */
    fun pos(filename: String, document: Document, range: TextRange): Pos {
        val startLine = document.getLineNumber(range.startOffset)
        val last = maxOf(range.startOffset, range.endOffset - 1)
        val endLine = document.getLineNumber(last)
        return Pos(
            filename,
            range.startOffset - document.getLineStartOffset(startLine) + 1, startLine + 1,
            last - document.getLineStartOffset(endLine) + 1, endLine + 1,
        )
    }

    private fun offset(document: Document, line: Int, column: Int): Int {
        if (document.lineCount == 0) return 0
        val index = (line - 1).coerceIn(0, document.lineCount - 1)
        return (document.getLineStartOffset(index) + column - 1).coerceAtMost(document.getLineEndOffset(index))
    }
}
