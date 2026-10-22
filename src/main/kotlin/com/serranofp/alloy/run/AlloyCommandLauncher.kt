package com.serranofp.alloy.run

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.impl.DocumentImpl
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.serranofp.alloy.AlloyBundle
import com.serranofp.alloy.analysis.AlloyAnalysisEngine
import com.serranofp.alloy.analysis.AlloyAnalysisEvent
import com.serranofp.alloy.psi.AlloyFile
import com.serranofp.alloy.semantics.AlloyModelResult
import com.serranofp.alloy.semantics.AlloyModelService
import com.serranofp.alloy.semantics.AlloyPositions
import com.serranofp.alloy.settings.AlloySettings
import com.serranofp.alloy.settings.AlloySolvers
import edu.mit.csail.sdg.translator.A4Options
import java.util.concurrent.CancellationException

/**
 * Starts a command inside the IDE: takes the model of the file from [AlloyModelService], finds the
 * analyzer's command at the position of the gutter's command, and executes it on [AlloyAnalysisEngine].
 */
class AlloyCommandLauncher(
    private val project: Project,
    private val engine: AlloyAnalysisEngine = AlloyAnalysisEngine.getInstance(),
    private val options: () -> A4Options = { AlloySettings.getInstance().analyzerOptions { AlloySolvers.getInstance().ids() } },
    /** Set while the saved solver is unavailable and the run uses `sat4j` instead. */
    private val solverWarning: () -> String? = {
        AlloySettings.getInstance().solverFallbackWarning { AlloySolvers.getInstance().ids() }
    },
    private val nonFatalWarnings: () -> Boolean = { AlloySettings.getInstance().state.nonFatalWarnings },
    private val checkVacuity: () -> Boolean = { AlloySettings.getInstance().state.checkVacuity },
) {
    private sealed interface Found

    /** What to execute: the command at [index] among the analyzer's commands of [filename]. */
    private class Target(val model: AlloyModelResult.Model, val filename: String, val index: Int) : Found

    private class NotFound(val summary: String, val details: String) : Found

    /**
     * Runs on a background thread; every failure ends [execution] with a summary and its cause, so that the
     * project's analysis is never left taken. A cancellation, such as that of the task while the model is
     * read, stops the execution and goes on. Disposing [parent] stops the analysis.
     */
    fun launch(execution: AlloyCommandExecution, parent: Disposable) {
        try {
            start(execution, parent)
        } catch (e: Throwable) {
            if (e is ControlFlowException || e is CancellationException) {
                execution.terminate()
                throw e
            }
            thisLogger().warn("Launching ${execution.command.displayName} failed", e)
            execution.fail(AlloyRunLog.FAILED, AlloyBundle.message("run.failure.analyzer", e.message ?: e.toString()))
        }
    }

    private fun start(execution: AlloyCommandExecution, parent: Disposable) {
        solverWarning()?.let(execution::warn)
        val target = when (val found = ReadAction.nonBlocking<Found> { find(execution) }.executeSynchronously()) {
            is Target -> found
            is NotFound -> return execution.fail(found.summary, found.details)
        }
        val warnings = target.model.warnings
        if (warnings.isNotEmpty() && !nonFatalWarnings()) {
            warnings.forEach { execution.accept(AlloyAnalysisEvent.Warning(it, System.currentTimeMillis())) }
            execution.fail(AlloyRunLog.FAILED, AlloyBundle.message("run.failure.warnings", execution.file.name, WARNINGS_ARE_FATAL))
            return
        }
        val options = options().apply { originalFilename = target.filename }
        val name = execution.command.displayName
        val followUp = if (checkVacuity()) {
            AlloyCommandExecution.FollowUp { sigs, query, listener -> engine.execute("$name (vacuity)", parent, sigs, query, options, listener) }
        } else {
            null
        }
        execution.begin(
            { engine.execute(name, parent, target.model.modules.contents, target.filename, target.index, options, execution::accept) },
            followUp,
        )
    }

    private fun find(execution: AlloyCommandExecution): Found {
        val fileName = execution.file.name
        val psiFile = PsiManager.getInstance(project).findFile(execution.file) as? AlloyFile
            ?: return NotFound(AlloyRunLog.FAILED, AlloyBundle.message("run.failure.not.alloy", fileName))
        val psiCommand = AlloyCommands.find(psiFile, execution.command)
        psiCommand?.let { execution.expect(AlloyCommands.expects(it)) }
        val result = AlloyModelService.getInstance(project).model(psiFile)
        val filename = AlloyPositions.filename(execution.file)
        val modules = result.modules
        execution.analyzes(modules.files.filterKeys { modules.reaches(filename, it) }.values.toSet())
        val model = when (result) {
            is AlloyModelResult.Model -> result
            is AlloyModelResult.Errors -> return NotFound(AlloyCommandExecution.MODEL_HAS_ERRORS, AlloyBundle.message("run.failure.errors", fileName))
            is AlloyModelResult.Unavailable ->
                return NotFound(AlloyRunLog.FAILED, AlloyBundle.message("run.failure.analyzer", result.cause.message ?: result.cause.toString()))
        }
        val notFound = NotFound(
            AlloyCommandExecution.COMMAND_NOT_FOUND, AlloyBundle.message("run.failure.command.missing", execution.command.displayName, fileName),
        )
        if (psiCommand == null) return notFound
        val text = model.modules.contents[filename] ?: return notFound
        val document = DocumentImpl(text)
        // A command's position starts at its keyword, or at the last command of a `=>` chain
        val index = model.module.allCommands.indexOfFirst {
            it.pos.filename == filename && AlloyPositions.textRange(it.pos, document).startOffset in psiCommand.textRange
        }
        return if (index < 0) notFound else Target(model, filename, index)
    }

    companion object {
        val WARNINGS_ARE_FATAL: String get() = AlloyBundle.message("run.failure.warnings.fatal")
    }
}
