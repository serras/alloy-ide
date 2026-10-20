package com.serranofp.alloy.run

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import java.util.concurrent.TimeUnit

class AlloyRunLaunchTest : BasePlatformTestCase() {
    private val service get() = AlloyRunService.getInstance(project)
    private val results get() = AlloyCommandResults.getInstance(project)
    private val show = AlloyCommandRef("show", 0)
    private val noCycles = AlloyCommandRef("noCycles", 1)

    override fun setUp() {
        super.setUp()
        results.clear()
        myFixture.configureByText("model.als", "sig Node { succ: lone Node }\npred show { some succ }\nrun show for 3\ncheck noCycles { no iden & succ } for 3\n")
    }

    override fun tearDown() {
        try {
            service.activeExecution?.terminate()
        } finally {
            super.tearDown()
        }
    }

    private val file get() = myFixture.file.virtualFile

    private fun executed(command: AlloyCommandRef = show) =
        results.get(file, command)!!.details.log.map { it.text }.firstOrNull { it.startsWith("Executing") }

    fun testLaunchStoresRunningThenTheResult() {
        assertTrue(service.launch(file, show, myFixture.editor))
        waitUntilIdle(project)

        val result = results.get(file, show)!!
        assertEquals(CommandState.InstanceFound, result.state)
        assertTrue(result.summary, result.summary.startsWith("Instance found · "))
        assertFalse(results.isOutdated(result, myFixture.editor.document))
        assertEquals("Executing \"Run show for 3\"", executed())
    }

    fun testCounterexample() {
        assertTrue(service.launch(file, noCycles, myFixture.editor))
        waitUntilIdle(project)
        assertEquals(CommandState.CounterexampleFound, results.get(file, noCycles)!!.state)
    }

    fun testUnsavedEditIsAnalyzed() {
        val document = myFixture.editor.document
        WriteCommandAction.runWriteCommandAction(project) {
            document.replaceString(document.text.indexOf("for 3"), document.text.indexOf("for 3") + 5, "for 4")
        }
        assertTrue(FileDocumentManager.getInstance().isFileModified(file))
        assertTrue(service.launch(file, show, myFixture.editor))
        waitUntilIdle(project)
        assertEquals("Executing \"Run show for 4\"", executed())
    }

    fun testUnsavedEditOfAnOpenedModuleIsAnalyzed() {
        val library = myFixture.addFileToProject("lib.als", "module lib\nsig Item {}")
        myFixture.configureByText("main.als", "open lib\npred show { some Item }\nrun show for 3\n")
        val main = myFixture.file.virtualFile
        val document = FileDocumentManager.getInstance().getDocument(library.virtualFile)!!
        WriteCommandAction.runWriteCommandAction(project) { document.setText("module lib\nsig Item {}\nfact { no Item }") }

        assertTrue(service.launch(main, show, myFixture.editor))
        waitUntilIdle(project)
        assertEquals(CommandState.NoInstance, results.get(main, show)!!.state)
    }

    fun testSecondLaunchWhileBusyLeavesTheStoreUnchanged() {
        val first = newExecution(project, file, show)
        service.startRun(first)
        val before = results.results(file)

        assertFalse(service.launch(file, noCycles, myFixture.editor))
        assertSame(first, service.activeExecution)
        assertEquals(before, results.results(file))
        assertNull(results.get(file, noCycles))
    }

    /** Runs a slow `run show` as the background task, on [indicator]; returns once it is solving. */
    private fun startSlowTask(indicator: ProgressIndicator): Pair<AlloyCommandExecution, java.util.concurrent.Future<*>> {
        myFixture.configureByText("model.als", PIGEONHOLE)
        val execution = newExecution(project, myFixture.file.virtualFile, show)
        service.startRun(execution)
        val task = AlloyCommandTask(project, execution, AlloyCommandLauncher(project, options = ::slowOptions), testRootDisposable)
        val running = ApplicationManager.getApplication().executeOnPooledThread { task.run(indicator) }
        waitUntil("the analysis did not start solving") { execution.log.progress == "Solving..." }
        return execution to running
    }

    fun testCancellingTheIndicatorStopsTheAnalysis() {
        val indicator = EmptyProgressIndicator()
        val (execution, running) = startSlowTask(indicator)
        indicator.cancel()
        running.get(10, TimeUnit.SECONDS)
        UIUtil.dispatchAllInvocationEvents()

        assertTrue(execution.isFinished())
        assertEquals(CommandState.Stopped, results.get(myFixture.file.virtualFile, show)!!.state)
        assertNull(service.activeExecution)
    }

    fun testEditDuringARunOutdatesItsResult() {
        val indicator = EmptyProgressIndicator()
        val (execution, running) = startSlowTask(indicator)
        myFixture.type("x")
        execution.stop()
        running.get(10, TimeUnit.SECONDS)
        val result = results.get(myFixture.file.virtualFile, show)!!
        assertEquals(Outdated.FileChanged, results.outdated(result, myFixture.editor.document))
    }

    fun testIndicatorFollowsTheAnalyzer() {
        val indicator = ProgressIndicatorBase()
        val (execution, running) = startSlowTask(indicator)
        waitUntil("the indicator did not follow") { indicator.text2 == "Solving..." }
        execution.stop()
        running.get(10, TimeUnit.SECONDS)
    }

    fun testTaskIsNamedAfterTheCommand() {
        val execution = newExecution(project, file, show)
        assertEquals("Alloy: Run show", AlloyCommandTask(project, execution, AlloyCommandLauncher(project), testRootDisposable).title)
    }
}
