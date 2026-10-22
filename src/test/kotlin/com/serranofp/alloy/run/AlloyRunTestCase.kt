package com.serranofp.alloy.run

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.serranofp.alloy.semantics.AlloyModelService
import edu.mit.csail.sdg.parser.CompUtil
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * A test of command executions on the shared light project. It starts without results, and ends by ending
 * the execution it left running and releasing the project's analysis: a suite or an enumeration still
 * holding it fails the test that leaked it, rather than every later test with a busy project.
 */
abstract class AlloyRunTestCase : BasePlatformTestCase() {
    protected val service: AlloyRunService get() = AlloyRunService.getInstance(project)
    protected val results: AlloyCommandResults get() = AlloyCommandResults.getInstance(project)

    override fun setUp() {
        super.setUp()
        results.clear()
    }

    override fun tearDown() {
        try {
            AlloyModelService.getInstance(project).parse = CompUtil::parseEverything_fromFile
            releaseRunService(project)
            // The results keep solutions, which are large
            results.clear()
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /**
     * Launches [command] of the open file with [launcher] as the background task does, off the EDT, calling
     * [before] once its execution is the project's; waits for its result.
     */
    protected fun launchAndWait(
        command: AlloyCommandRef,
        launcher: AlloyCommandLauncher = AlloyCommandLauncher(project),
        before: (AlloyCommandExecution) -> Unit = {},
    ): CommandResult {
        val file = myFixture.file.virtualFile
        val execution = newExecution(project, file, command)
        assertTrue(service.startRun(execution))
        before(execution)
        ApplicationManager.getApplication().executeOnPooledThread { launcher.launch(execution, testRootDisposable) }.get(30, TimeUnit.SECONDS)
        assertTrue("the execution did not end", execution.waitFor(30_000))
        return results.get(file, command)!!
    }

    /** The command of the open file whose text starts with [prefix]. */
    protected fun commandRef(prefix: String): AlloyCommandRef =
        AlloyCommands.ref(AlloyCommands.commands(myFixture.file).single { it.text.startsWith(prefix) })

    /**
     * Starts the `run show` of [model] as a launch does: a background task under [indicator], which
     * `ProgressManager` runs, solving with [slowOptions]. Returns once [ready] holds for its execution.
     */
    protected fun startTask(
        model: String = PIGEONHOLE,
        indicator: ProgressIndicator = EmptyProgressIndicator(),
        ready: (AlloyCommandExecution) -> Boolean = { it.log.progress == "Solving..." && analysisIsSearching() },
    ): Pair<AlloyCommandExecution, Future<*>> {
        myFixture.configureByText("model.als", model)
        val execution = newExecution(project, myFixture.file.virtualFile, AlloyCommandRef("show", 0))
        assertTrue(service.startRun(execution))
        val task = AlloyCommandTask(project, execution, AlloyCommandLauncher(project, options = ::slowOptions), testRootDisposable)
        val running = ApplicationManager.getApplication().executeOnPooledThread {
            ProgressManager.getInstance().runProcess({ task.run(indicator) }, indicator)
        }
        waitUntil("the analysis did not get going") { ready(execution) || running.isDone }
        assertTrue("the analysis ended first: ${execution.log.lines.map { it.text }}", ready(execution))
        return execution to running
    }
}
