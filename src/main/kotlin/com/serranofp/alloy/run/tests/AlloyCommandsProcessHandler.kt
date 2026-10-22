package com.serranofp.alloy.run.tests

import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.testframework.sm.ServiceMessageBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.serranofp.alloy.AlloyBundle
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
import java.util.concurrent.ExecutionException

/**
 * A suite as a "process" inside the IDE: on a background thread it takes the project's analysis, executes the
 * commands of [file] one at a time, as the gutter does, and prints the test runner's service messages for
 * them. Destroying it stops the command being executed and skips the rest, whose nodes stay not run.
 * Detaching does the same, as nothing would follow a suite left running, but ends it as detached.
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
    private var detached = false

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
        if (!running) ended(1)
    }

    override fun detachProcessImpl() {
        detached = true
        destroyProcessImpl()
    }

    override fun detachIsDefault(): Boolean = false

    override fun getProcessInput(): OutputStream? = null

    private fun ended(exitCode: Int) = if (detached) notifyProcessDetached() else notifyProcessTerminated(exitCode)

    private class Test(val ref: AlloyCommandRef, val name: String)

    private fun runSuite() {
        val service = AlloyRunService.getInstance(project)
        val suite = service.startSuite()
        if (suite == null) {
            notifyTextAvailable("${AlloyRunService.BUSY_MESSAGE}\n", ProcessOutputTypes.STDERR)
            ended(1)
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
            ended(0)
        }
    }

    /** The commands in source order, named like the command. */
    private fun tests(): List<Test> = ReadAction.nonBlocking<List<Test>> {
        val psiFile = PsiManager.getInstance(project).findFile(file) as? AlloyFile ?: return@nonBlocking emptyList()
        AlloyCommands.commands(psiFile).map { command ->
            val ref = AlloyCommands.ref(command)
            Test(ref, "${command.firstChild.text} ${ref.displayName}")
        }.filter { test -> only.isEmpty() || test.ref in only }
    }.executeSynchronously()

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
            failed(test, AlloyRunService.BUSY_MESSAGE)
            return false
        }
        val start = System.currentTimeMillis()
        val launched = ApplicationManager.getApplication().executeOnPooledThread { launcher().launch(execution, service) }
        execution.awaitOrStop({ destroyed })
        try {
            launched.get()
        } catch (e: ExecutionException) {
            // A cancelled launch, which already ended the execution
            thisLogger().debug(e)
        }
        val duration = System.currentTimeMillis() - start
        val result = results.get(file, test.ref)
        if (result == null) {
            // Its file was deleted meanwhile
            failed(test, AlloyBundle.message("run.tests.no.result"))
            return false
        }
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

    private fun failed(test: Test, message: String) {
        print(ServiceMessageBuilder.testFailed(test.name).addAttribute("message", message))
        print(ServiceMessageBuilder.testFinished(test.name))
    }

    /**
     * Commits the documents, so that the PSI where commands are found matches the text the analyzer reads.
     * A commit is a write action, which is not allowed in any modality: while a modal dialog is open, the
     * suite waits for it only if there is something to commit, which is rare as the editor cannot be typed in.
     */
    private fun commitDocuments() {
        val documents = PsiDocumentManager.getInstance(project)
        if (!documents.hasUncommitedDocuments()) return
        ApplicationManager.getApplication().invokeAndWait({ documents.commitAllDocuments() }, ModalityState.nonModal())
    }

    private fun print(message: ServiceMessageBuilder) {
        notifyTextAvailable("$message\n", ProcessOutputTypes.STDOUT)
    }
}
