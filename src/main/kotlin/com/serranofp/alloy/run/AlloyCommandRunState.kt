package com.serranofp.alloy.run

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class AlloyCommandRunState(
    private val project: Project,
    private val file: VirtualFile,
    private val command: AlloyCommandRef,
) : RunProfileState {
    override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
        val service = AlloyRunService.getInstance(project)
        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        val handler = AlloyRunProcessHandler(service, ConsoleViewSink(console))
        service.startRun(handler)
        console.attachToProcess(handler)

        // The server analyzes the text it was sent with didOpen/didChange, so the model must be open in an
        // editor; the platform then keeps the server in sync with unsaved edits.
        if (!FileEditorManager.getInstance(project).isFileOpen(file)) {
            FileEditorManager.getInstance(project).openFile(file, false)
        }

        handler.addProcessListener(object : ProcessListener {
            override fun startNotified(event: ProcessEvent) {
                ApplicationManager.getApplication().executeOnPooledThread {
                    val connection = awaitConnection()
                    AlloyCommandLauncher(service).launch(handler, connection, connection?.uriOf(file), file.name, command)
                }
            }
        })
        return DefaultExecutionResult(console, handler)
    }

    /** A server that is still starting (for example, because the file was just opened) is waited for. */
    private fun awaitConnection(): AlloyServerConnection? {
        val deadline = System.currentTimeMillis() + SERVER_START_TIMEOUT_MILLIS
        while (true) {
            LspAlloyServerConnection.find(project)?.let { return it }
            if (System.currentTimeMillis() > deadline) return null
            Thread.sleep(100)
        }
    }

    private companion object {
        const val SERVER_START_TIMEOUT_MILLIS = 15_000L
    }
}
