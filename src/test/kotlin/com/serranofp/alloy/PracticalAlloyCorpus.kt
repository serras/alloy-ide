package com.serranofp.alloy

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import edu.mit.csail.sdg.alloy4.A4Reporter
import edu.mit.csail.sdg.alloy4.Err
import edu.mit.csail.sdg.alloy4.Util
import edu.mit.csail.sdg.parser.CompModule
import edu.mit.csail.sdg.parser.CompUtil
import java.io.File

/** The test data of the models of the Practical Alloy book (see [PracticalAlloyModelsTest]). */
object PracticalAlloyCorpus {
    const val TEST_DATA = "src/test/testData"

    /** A problem reported in a model of the book: the inspection, the text it is on, and its message. */
    data class Problem(val inspection: String, val text: String, val message: String) {
        /** The Kotlin that writes this problem, to paste in the expected problems. */
        fun code(): String = "Problem(\"$inspection\", ${quote(text)}, ${quote(message)})"
    }

    /**
     * The models under [folder] of `practicalalloy`, copied to the fixture's project, by their path under
     * `practicalalloy`, in order.
     */
    fun models(fixture: CodeInsightTestFixture, folder: String): List<Pair<String, VirtualFile>> {
        val root = fixture.copyDirectoryToProject("practicalalloy/$folder", folder)
        val models = mutableListOf<VirtualFile>()
        VfsUtilCore.iterateChildrenRecursively(root, null) { file ->
            if (file.extension == "als") models += file
            true
        }
        return models.sortedBy { it.path }.map { "$folder/${VfsUtilCore.getRelativePath(it, root)}" to it }
    }

    /** The problems of [inspections] in the fixture's file, with [severity], in the order of the file. */
    fun problems(fixture: CodeInsightTestFixture, inspections: Set<String>, severity: HighlightSeverity): List<Problem> =
        fixture.doHighlighting().filter { it.inspectionToolId in inspections && it.severity == severity }.sortedBy { it.startOffset }
            .map { Problem(it.inspectionToolId!!, fixture.file.text.substring(it.startOffset, it.endOffset), it.description) }

    /** The analyzer's model of [text], as the text of the book's model at [path], or its error. */
    fun parse(path: String, text: String): Result<CompModule> {
        val filename = Util.canon(File("$TEST_DATA/practicalalloy/$path").absolutePath)
        return try {
            Result.success(CompUtil.parseEverything_fromFile(A4Reporter.NOP, hashMapOf(filename to text), filename))
        } catch (e: Err) {
            Result.failure(e)
        }
    }

    private fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}
