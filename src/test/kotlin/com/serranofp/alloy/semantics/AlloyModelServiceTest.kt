package com.serranofp.alloy.semantics

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.serranofp.alloy.AlloyLanguage
import com.serranofp.alloy.psi.AlloyFile
import com.serranofp.alloy.viewer.alloyEvaluatorDocument
import edu.mit.csail.sdg.alloy4.A4Reporter
import edu.mit.csail.sdg.alloy4.ErrorFatal
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AlloyModelServiceTest : BasePlatformTestCase() {
    private val service get() = AlloyModelService.getInstance(project)

    /** As the annotator calls it: in a read action, off the EDT. */
    private fun model(file: PsiFile): AlloyModelResult = computeModel(file)

    private fun edit(file: PsiFile, text: String) {
        val document = FileDocumentManager.getInstance().getDocument(file.virtualFile)!!
        WriteCommandAction.runWriteCommandAction(project) { document.setText(text) }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    private fun errorText(result: AlloyModelResult, file: PsiFile): String {
        assertInstanceOf(result, AlloyModelResult.Errors::class.java)
        val error = (result as AlloyModelResult.Errors).errors.single()
        val document = FileDocumentManager.getInstance().getDocument(file.virtualFile)!!
        return AlloyPositions.textRange(error.pos, document).substring(document.text)
    }

    fun testFreshModel() {
        val file = myFixture.configureByText("model.als", "sig Node { next: lone Node }\nrun {} for 3")
        val result = model(file)
        assertInstanceOf(result, AlloyModelResult.Model::class.java)
        result as AlloyModelResult.Model
        assertEquals(listOf("this/Node"), result.module.allSigs.map { it.label })
        assertEmpty(result.warnings)
        assertEquals(mapOf(AlloyPositions.filename(file.virtualFile) to file.virtualFile), result.modules.files)
    }

    fun testCachedUntilTheFileChanges() {
        val file = myFixture.configureByText("model.als", "sig A {}")
        val first = model(file)
        assertSame(first, model(file))
        edit(file, "sig B {}")
        assertNotSame(first, model(file))
    }

    fun testWarnings() {
        val file = myFixture.configureByText("model.als", "sig A {}\nfact { some A & none }")
        val result = model(file) as AlloyModelResult.Model
        assertTrue(result.warnings.single().msg, result.warnings.single().msg.contains("irrelevant"))
    }

    fun testModelWithAnError() {
        val file = myFixture.configureByText("model.als", "sig A { f: Undefined }")
        val result = model(file)
        assertEquals("Undefined", errorText(result, file))
        assertTrue((result as AlloyModelResult.Errors).errors.single().msg.contains("cannot be found"))
    }

    fun testUnsavedEdit() {
        val file = myFixture.configureByText("model.als", "sig A {}")
        assertInstanceOf(model(file), AlloyModelResult.Model::class.java)
        edit(file, "sig A { f: Undefined }")
        assertTrue(FileDocumentManager.getInstance().isFileModified(file.virtualFile))
        assertEquals("Undefined", errorText(model(file), file))
    }

    fun testOpenedProjectModule() {
        val library = myFixture.addFileToProject("lib.als", "module lib\nsig Item {}")
        val file = myFixture.configureByText("main.als", "open lib\nsig Box { items: set Item }")
        val result = model(file) as AlloyModelResult.Model
        val main = AlloyPositions.filename(file.virtualFile)
        val lib = AlloyPositions.filename(library.virtualFile)
        assertEquals(setOf(main, lib), result.modules.files.keys)
        assertEquals(setOf(lib), result.modules.opens[main])
        assertTrue(result.modules.reaches(main, lib))
        assertFalse(result.modules.reaches(lib, main))
    }

    fun testEditedOpenedModuleInvalidatesItsDependents() {
        val library = myFixture.addFileToProject("lib.als", "module lib\nsig Item {}")
        val file = myFixture.configureByText("main.als", "open lib\nsig Box { items: set Item }")
        assertInstanceOf(model(file), AlloyModelResult.Model::class.java)

        // Unsaved, like any edit in the editor
        edit(library, "module lib\nsig Thing {}")
        assertEquals("Item", errorText(model(file), file))
    }

    fun testOpenedModuleInSubdirectory() {
        myFixture.addFileToProject("sub/lib.als", "module sub/lib\nsig Item {}")
        val file = myFixture.configureByText("main.als", "open sub/lib\nsig Box { items: set Item }")
        assertEquals(2, (model(file) as AlloyModelResult.Model).modules.files.size)
    }

    fun testLibraryModuleFromTheJar() {
        val file = myFixture.configureByText("main.als", "open util/ordering[State]\nsig State {}\nfact { some first }")
        val result = model(file) as AlloyModelResult.Model
        assertEquals(1, result.modules.files.size)
        assertTrue(result.module.allReachableModules.any { AlloyPositions.isLibrary(it.pos().filename) })
    }

    fun testAnalyzerExceptionIsUnavailable() {
        service.parse = { _, _, _ -> throw IllegalStateException("analyzer bug") }
        val file = myFixture.configureByText("model.als", "sig A {}")
        val result = model(file)
        assertInstanceOf(result, AlloyModelResult.Unavailable::class.java)
        assertEquals("analyzer bug", (result as AlloyModelResult.Unavailable).cause.message)
    }

    fun testFatalAnalyzerErrorIsUnavailable() {
        service.parse = { _, _, _ -> throw ErrorFatal("Unknown exception occurred") }
        val file = myFixture.configureByText("model.als", "sig A {}")
        assertInstanceOf(model(file), AlloyModelResult.Unavailable::class.java)
    }

    fun testCancellationIsNotCaught() {
        service.parse = { _, _, _ -> throw ProcessCanceledException() }
        val file = myFixture.configureByText("model.als", "sig A {}")
        assertThrows(ProcessCanceledException::class.java) { model(file) }
    }

    /** [model] under a progress indicator, as highlighting runs it. */
    private fun modelWithProgress(file: PsiFile): AlloyModelResult =
        ApplicationManager.getApplication()
            .executeOnPooledThread<Result<AlloyModelResult>> {
                runCatching {
                    ProgressManager.getInstance().runProcess<AlloyModelResult>(
                        { ReadAction.computeBlocking<AlloyModelResult, Throwable> { service.model(file as AlloyFile) } },
                        EmptyProgressIndicator(),
                    )
                }
            }
            .get(30, TimeUnit.SECONDS)
            .getOrThrow()

    fun testTheParseCanBeCancelledAndIsNotCached() {
        val real = service.parse
        var cancel = true
        service.parse = { reporter, contents, filename ->
            // As a write action that arrives during the parse
            if (cancel) ProgressManager.getInstance().progressIndicator.cancel()
            cancel = false
            // As CompModule reports each paragraph it type-checks, and CompUtil wraps what it does not expect
            try {
                reporter.typecheck("Sig this/A\n")
            } catch (e: Throwable) {
                throw ErrorFatal("Unknown exception occurred: $e", e)
            }
            real(reporter, contents, filename)
        }
        val file = myFixture.configureByText("model.als", "sig A {}")
        assertThrows(ProcessCanceledException::class.java) { modelWithProgress(file) }
        assertInstanceOf(modelWithProgress(file), AlloyModelResult.Model::class.java)
    }

    fun testWrappedCancellationIsNotCached() {
        service.parse = { _, _, _ -> throw ErrorFatal("Unknown exception occurred", RuntimeException(ProcessCanceledException())) }
        val file = myFixture.configureByText("model.als", "sig A {}")
        assertThrows(ProcessCanceledException::class.java) { model(file) }
        service.restoreParse()
        assertInstanceOf(model(file), AlloyModelResult.Model::class.java)
    }

    fun testOneParseAtATime() {
        val real = service.parse
        val parses = AtomicInteger()
        val parsing = CountDownLatch(1)
        val release = CountDownLatch(1)
        service.parse = { reporter, contents, filename ->
            parses.incrementAndGet()
            parsing.countDown()
            release.await(30, TimeUnit.SECONDS)
            real(reporter, contents, filename)
        }
        val file = myFixture.configureByText("model.als", "sig A {}")
        val pool = ApplicationManager.getApplication()
        val first = pool.executeOnPooledThread<AlloyModelResult> { ReadAction.computeBlocking<AlloyModelResult, Throwable> { service.model(file as AlloyFile) } }
        assertTrue(parsing.await(30, TimeUnit.SECONDS))
        val second = pool.executeOnPooledThread<AlloyModelResult> { ReadAction.computeBlocking<AlloyModelResult, Throwable> { service.model(file as AlloyFile) } }
        // Time for the second thread to reach the parse, if nothing stops it
        Thread.sleep(300)
        release.countDown()
        assertSame(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS))
        assertEquals(1, parses.get())
    }

    fun testFilesThatAreNotModulesHaveNoModel() {
        var parses = 0
        service.parse = { _, _, _ -> parses++; error("not parsed") }
        val copy = PsiFileFactory.getInstance(project).createFileFromText("copy.als", AlloyLanguage, "sig A {}", false, false)
        assertNull(copy.virtualFile)
        assertInstanceOf(model(copy), AlloyModelResult.Unavailable::class.java)
        val fragment = PsiDocumentManager.getInstance(project).getPsiFile(alloyEvaluatorDocument(project) { null })!!
        assertInstanceOf(model(fragment), AlloyModelResult.Unavailable::class.java)
        assertEquals(0, parses)
    }

    fun testOpenedLibraryModulesAreAmongTheOpens() {
        val file = myFixture.configureByText("main.als", "open util/ordering[State]\nsig State {}")
        val modules = model(file).modules
        val main = AlloyPositions.filename(file.virtualFile)
        assertEquals(setOf(main), modules.files.keys)
        val library = modules.opens[main]!!.single()
        assertTrue(library, AlloyPositions.isLibrary(library) && library.endsWith("ordering.als"))
    }

    /** A module the analyzer reads from disk, such as a Markdown one, has no PSI; its file is tracked instead. */
    fun testModuleReadFromDiskInvalidatesItsDependents() {
        val dir = temporaryDirectory()
        File(dir, "main.als").writeText("open lib\nsig Box { items: set Item }")
        val markdown = File(dir, "lib.md").apply { writeText("---\n---\n```alloy\nmodule lib\nsig Item {}\n```\n") }
        val main = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(dir, "main.als"))!!
        val library = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(markdown)!!
        val file = PsiManager.getInstance(project).findFile(main)!!
        assertInstanceOf(model(file), AlloyModelResult.Model::class.java)

        markdown.writeText("---\n---\n```alloy\nmodule lib\nsig Thing {}\n```\n")
        VfsUtil.markDirtyAndRefresh(false, false, false, library)
        assertEquals("Item", errorText(model(file), file))
    }

    private val temporaryDirectories = mutableListOf<File>()

    /** A directory on disk that the VFS may see, deleted after the test. */
    private fun temporaryDirectory(): File =
        FileUtil.createTempDirectory("alloy-disk", null).canonicalFile.also {
            temporaryDirectories += it
            VfsRootAccess.allowRootAccess(testRootDisposable, it.path)
        }

    fun testNotOnTheEdt() {
        var parses = 0
        service.parse = { _, _, _ -> parses++; error("not parsed") }
        val file = myFixture.configureByText("model.als", "sig A {}") as AlloyFile
        val e = runCatching { ReadAction.computeBlocking<AlloyModelResult, Throwable> { service.model(file) } }.exceptionOrNull()
        assertNotNull(e)
        assertTrue(e.toString(), e!!.message.orEmpty().contains("Event Dispatch Thread"))
        assertEquals(0, parses)
        assertNull(service.cachedModel(file))
    }

    /**
     * The analyzer's own parse of a model long enough to take a while, cancelled as it starts: it stops at the
     * first paragraph it reports, rather than parse the rest, and nothing is cached.
     */
    fun testRealParseStopsWhenCancelled() {
        val real = service.parse
        var reports = 0
        var cancel = true
        service.parse = { reporter, contents, filename ->
            if (cancel) ProgressManager.getInstance().progressIndicator.cancel()
            cancel = false
            val counting = object : A4Reporter() {
                override fun parse(msg: String?) = reporter.parse(msg).also { reports++ }
                override fun typecheck(msg: String?) = reporter.typecheck(msg).also { reports++ }
            }
            real(counting, contents, filename)
        }
        val text = (1..2000).joinToString("\n") { "sig S$it { f$it: set S$it }\nfact { some S$it.f$it }" }
        val file = myFixture.configureByText("model.als", text) as AlloyFile
        assertThrows(ProcessCanceledException::class.java) { modelWithProgress(file) }
        assertEquals(0, reports)
        assertNull(service.cachedModel(file))
        assertInstanceOf(modelWithProgress(file), AlloyModelResult.Model::class.java)
        assertTrue(reports > 2000)
    }

    fun testCreatingAMissingModuleInvalidatesItsOpener() {
        val file = myFixture.configureByText("main.als", "open lib\nsig Box { items: set Item }")
        val missing = model(file)
        assertInstanceOf(missing, AlloyModelResult.Errors::class.java)
        assertEquals(1, missing.modules.files.size)

        myFixture.addFileToProject("lib.als", "module lib\nsig Item {}")
        val found = model(file)
        assertInstanceOf(found, AlloyModelResult.Model::class.java)
        assertEquals(2, found.modules.files.size)
    }

    fun testDeletingAnOpenedModuleInvalidatesItsOpener() {
        val library = myFixture.addFileToProject("lib.als", "module lib\nsig Item {}")
        val file = myFixture.configureByText("main.als", "open lib\nsig Box { items: set Item }")
        assertInstanceOf(model(file), AlloyModelResult.Model::class.java)

        WriteCommandAction.runWriteCommandAction(project) { library.virtualFile.delete(this) }
        val result = model(file)
        assertInstanceOf(result, AlloyModelResult.Errors::class.java)
        assertEquals(setOf(AlloyPositions.filename(file.virtualFile)), result.modules.files.keys)
    }

    fun testRenamingAnOpenedModuleInvalidatesItsOpener() {
        val library = myFixture.addFileToProject("lib.als", "module lib\nsig Item {}")
        val file = myFixture.configureByText("main.als", "open lib\nsig Box { items: set Item }")
        assertInstanceOf(model(file), AlloyModelResult.Model::class.java)

        WriteCommandAction.runWriteCommandAction(project) { library.virtualFile.rename(this, "other.als") }
        assertInstanceOf(model(file), AlloyModelResult.Errors::class.java)
    }

    fun testOpenOfAModuleThatDoesNotExist() {
        val file = myFixture.configureByText("main.als", "open nowhere\nsig A {}")
        val result = model(file)
        assertInstanceOf(result, AlloyModelResult.Errors::class.java)
        val error = (result as AlloyModelResult.Errors).errors.single()
        assertTrue(error.msg, error.msg.contains("nowhere"))
        val main = AlloyPositions.filename(file.virtualFile)
        assertEquals(setOf(main), result.modules.files.keys)
        assertEquals(emptySet<String>(), result.modules.opens[main])
    }

    /** Collecting the modules of a cycle of opens ends, and so does [AlloyModules.reaches] over it. */
    fun testCycleOfOpens() {
        myFixture.addFileToProject("b.als", "module b\nopen a\nsig B {}")
        val file = myFixture.configureByText("a.als", "module a\nopen b\nsig A {}")
        val modules = model(file).modules
        val a = AlloyPositions.filename(file.virtualFile)
        val b = modules.files.keys.single { it != a }
        assertEquals(setOf(b), modules.opens[a])
        assertEquals(setOf(a), modules.opens[b])
        assertTrue(modules.reaches(a, b))
        assertTrue(modules.reaches(b, a))
        assertFalse(modules.reaches(a, "/elsewhere.als"))
    }

    fun testDiamondOfOpens() {
        myFixture.addFileToProject("d.als", "module d\nsig D {}")
        myFixture.addFileToProject("b.als", "module b\nopen d\nsig B { f: set D }")
        myFixture.addFileToProject("c.als", "module c\nopen d\nsig C { g: set D }")
        val file = myFixture.configureByText("main.als", "open b\nopen c\nfact { some B.f + C.g }")
        val result = model(file) as AlloyModelResult.Model
        assertEquals(listOf("b.als", "c.als", "d.als", "main.als"), result.modules.files.values.map { it.name }.sorted())
        val d = result.modules.files.entries.single { it.value.name == "d.als" }.key
        assertTrue(result.modules.files.keys.all { result.modules.reaches(it, d) })
    }

    /** As the analyzer, a module is found by its path: the name it declares does not matter. */
    fun testModuleNameDifferentFromItsPath() {
        myFixture.addFileToProject("lib.als", "module other\nsig Item {}")
        val file = myFixture.configureByText("main.als", "open lib\nsig Box { items: set Item }")
        val result = model(file)
        assertEquals(2, result.modules.files.size)
        assertInstanceOf(result, AlloyModelResult.Model::class.java)
    }

    fun testOpenedPath() {
        assertEquals("/m/sub/test.als", AlloyModelService.openedPath("/m/main.als", "main", "sub/test"))
        assertEquals("/m/test.als", AlloyModelService.openedPath("/m/main.als", "", "test"))
        assertEquals("/m/util/y.als", AlloyModelService.openedPath("/m/util/x.als", "util/x", "util/y"))
        assertEquals("/m/other.als", AlloyModelService.openedPath("/m/sub/x.als", "sub/x", "other"))
    }

    fun testOpenedPathAboveTheRoot() {
        // `a/b/main` names a module two directories below where names start, which is above `/`
        assertEquals("/x.als", AlloyModelService.openedPath("/main.als", "a/b/main", "x"))
        assertEquals("/x.als", AlloyModelService.openedPath("/m/main.als", "a/b/main", "x"))
        // An empty name leads to no file
        assertNull(AlloyModelService.openedPath("/m/main.als", "main", ""))
    }

    fun testOpenerRoot() {
        // The same for `module main` and `module app/main`, which opens its `app/...` names from there
        assertEquals("/m", AlloyModelService.openerRoot("/m/main.als"))
        assertEquals("/m/sub", AlloyModelService.openerRoot("/m/sub/x.als"))
    }

    fun testPathDerivedNames() {
        fun derived(opener: String, moduleName: String, path: String): String? =
            AlloyModelService.pathDerivedName(opener, moduleName, path)?.also {
                assertEquals("$it leads back", path, AlloyModelService.openedPath(opener, moduleName, it))
            }
        assertEquals("sub/y", derived("/m/main.als", "main", "/m/sub/y.als"))
        assertEquals("lib", derived("/m/main.als", "main", "/m/lib.als"))
        assertEquals("sub/y", derived("/m/main.als", "", "/m/sub/y.als"))
        assertEquals("app/sub/y", derived("/m/main.als", "app/main", "/m/sub/y.als"))
        assertEquals("util/y", derived("/m/util/x.als", "util/x", "/m/util/y.als"))
        assertNull(derived("/m/main.als", "main", "/other/y.als"))
        assertNull(derived("/m/main.als", "main", "/m/sub/1y.als"))
        assertNull(derived("/m/main.als", "main", "/m/my-dir/y.als"))
    }

    override fun tearDown() {
        try {
            service.restoreParse()
            temporaryDirectories.forEach(FileUtil::delete)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }
}
