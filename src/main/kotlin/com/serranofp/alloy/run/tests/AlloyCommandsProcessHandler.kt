package com.serranofp.alloy.run.tests

import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.testframework.sm.ServiceMessageBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.serranofp.alloy.psi.AlloyFile
import com.serranofp.alloy.run.AlloyCommandExecution
import com.serranofp.alloy.run.AlloyCommandLauncher
import com.serranofp.alloy.run.AlloyCommandRef
import com.serranofp.alloy.run.AlloyCommandResults
import com.serranofp.alloy.run.AlloyCommands
import com.serranofp.alloy.run.AlloyRunService
import com.serranofp.alloy.run.CommandState
import com.serranofp.alloy.settings.AlloySettings
import java.io.OutputStream

/**
 * A suite as a "process" inside the IDE: on a background thread it takes the project's analysis, executes the
 * commands of [file] one at a time, as the gutter does, and prints the test runner's service messages for
 * them. Destroying it stops the command being executed and skips the rest, whose nodes stay not run.
 */
class AlloyCommandsProcessHandler(
    private val project: Project,
    private val file: VirtualFile,
    /** The commands to execute; all of them when empty. */
    private val only: List<AlloyCommandRef> = emptyList(),
    private val launcher: () -> AlloyCommandLauncher = { AlloyCommandLauncher(project) },
) : ProcessHandler() {
    @Volatile
    private var destroyed = false

    @Volatile
    private var running = false

    override fun startNotify() {
        super.startNotify()
        running = true
        ApplicationManager.getApplication().executeOnPooledThread(::runSuite)
    }

    override fun destroyProcessImpl() {
        destroyed = true
        // Before it started, nothing else ends it
        if (!running) notifyProcessTerminated(1)
    }

    override fun detachProcessImpl() = destroyProcessImpl()

    override fun detachIsDefault(): Boolean = false

    override fun getProcessInput(): OutputStream? = null

    private class Test(val ref: AlloyCommandRef, val name: String)

    private fun runSuite() {
        val service = AlloyRunService.getInstance(project)
        val suite = service.startSuite()
        if (suite == null) {
            notifyTextAvailable("${AlloyRunService.BUSY_MESSAGE}\n", ProcessOutputTypes.STDERR)
            notifyProcessTerminated(1)
            return
        }
        try {
            commitDocuments()
            val tests = tests()
            print(ServiceMessageBuilder("testCount").addAttribute("count", tests.size.toString()))
            for (test in tests) {
                if (destroyed || !run(service, suite, test)) break
            }
        } finally {
            service.suiteFinished(suite)
            notifyProcessTerminated(0)
        }
    }

    /** The commands in source order, named like the command. */
    private fun tests(): List<Test> = ReadAction.nonBlocking<List<Test>> {
        val psiFile = PsiManager.getInstance(project).findFile(file) as? AlloyFile ?: return@nonBlocking emptyList()
        AlloyCommands.commands(psiFile).map { command ->
            val ref = AlloyCommands.ref(command)
            Test(ref, "${command.firstChild.text} ${ref.displayName}")
        }.filter { test -> only.isEmpty() || only.any { matches(it, test.ref) } }
    }.executeSynchronously()

    private fun matches(wanted: AlloyCommandRef, ref: AlloyCommandRef): Boolean =
        if (wanted.label != null) ref.label != null && AlloyCommands.sameLabel(wanted.label, ref.label) else ref.label == null && wanted.index == ref.index

    /** Executes [test]; false if the suite ends with it, because it was stopped. */
    private fun run(service: AlloyRunService, suite: AlloyRunService.Suite, test: Test): Boolean {
        print(ServiceMessageBuilder.testStarted(test.name).addAttribute("locationHint", AlloyTestLocator.url(file, test.ref)))
        // Each command is found again at its launch, as edits during the suite may have moved it
        commitDocuments()
        val results = AlloyCommandResults.getInstance(project)
        val document = ReadAction.nonBlocking<Document?> { FileDocumentManager.getInstance().getDocument(file) }.executeSynchronously()
        val execution = AlloyCommandExecution(
            service, results, file, test.ref, document?.let(results::stamp) ?: -1, AlloySettings.getInstance().generation,
        )
        if (!service.startRun(execution, suite)) {
            print(ServiceMessageBuilder.testFailed(test.name).addAttribute("message", AlloyRunService.BUSY_MESSAGE))
            print(ServiceMessageBuilder.testFinished(test.name))
            return false
        }
        val start = System.currentTimeMillis()
        val launched = ApplicationManager.getApplication().executeOnPooledThread { launcher().launch(execution, service) }
        var stopRequested = false
        while (!execution.waitFor(POLL_MILLIS)) {
            if (destroyed && !stopRequested) {
                stopRequested = true
                execution.stop()
            }
        }
        launched.get()
        val duration = System.currentTimeMillis() - start
        val result = results.get(file, test.ref) ?: return false
        AlloyCommandTestResult.output(result).takeIf { it.isNotEmpty() }?.let {
            print(ServiceMessageBuilder.testStdOut(test.name).addAttribute("out", "$it\n"))
        }
        // A stopped command is left not completed, like the ones after it
        if (destroyed || result.state == CommandState.Stopped) return false
        AlloyCommandTestResult.failure(result)?.let { (message, details) ->
            print(ServiceMessageBuilder.testFailed(test.name).addAttribute("message", message).addAttribute("details", details.orEmpty()))
        }
        print(ServiceMessageBuilder.testFinished(test.name).addAttribute("duration", duration.toString()))
        return true
    }

    private fun commitDocuments() {
        ApplicationManager.getApplication().invokeAndWait { PsiDocumentManager.getInstance(project).commitAllDocuments() }
    }

    private fun print(message: ServiceMessageBuilder) {
        notifyTextAvailable("$message\n", ProcessOutputTypes.STDOUT)
    }

    private companion object {
        const val POLL_MILLIS = 100L
    }
}
