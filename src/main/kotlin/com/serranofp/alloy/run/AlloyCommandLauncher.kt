package com.serranofp.alloy.run

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.impl.DocumentImpl
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.serranofp.alloy.analysis.AlloyAnalysisEngine
import com.serranofp.alloy.analysis.AlloyAnalysisEvent
import com.serranofp.alloy.psi.AlloyFile
import com.serranofp.alloy.semantics.AlloyModelResult
import com.serranofp.alloy.semantics.AlloyModelService
import com.serranofp.alloy.semantics.AlloyPositions
import com.serranofp.alloy.settings.AlloySettings
import com.serranofp.alloy.settings.AlloySolvers
import edu.mit.csail.sdg.translator.A4Options

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
) {
    private sealed interface Found

    /** What to execute: the command at [index] among the analyzer's commands of [filename]. */
    private class Target(val model: AlloyModelResult.Model, val filename: String, val index: Int) : Found

    private class NotFound(val summary: String, val details: String) : Found

    /**
     * Runs on a background thread; every failure ends [execution] with a summary and its cause. Disposing
     * [parent] stops the analysis.
     */
    fun launch(execution: AlloyCommandExecution, parent: Disposable) {
        solverWarning()?.let(execution::warn)
        val fileName = execution.file.name
        val target = when (val found = ReadAction.nonBlocking<Found> { find(execution) }.executeSynchronously()) {
            is Target -> found
            is NotFound -> {
                execution.fail(found.summary, found.details)
                return
            }
        }
        val warnings = target.model.warnings
        if (warnings.isNotEmpty() && !nonFatalWarnings()) {
            warnings.forEach { execution.accept(AlloyAnalysisEvent.Warning(it, System.currentTimeMillis())) }
            execution.fail(AlloyRunLog.FAILED, "$fileName has warnings. $WARNINGS_ARE_FATAL")
            return
        }
        val options = options().apply { originalFilename = target.filename }
        execution.begin {
            engine.execute(
                execution.command.displayName, parent, target.model.modules.contents, target.filename, target.index, options,
                execution::accept,
            )
        }
    }

    private fun find(execution: AlloyCommandExecution): Found {
        val fileName = execution.file.name
        val psiFile = PsiManager.getInstance(project).findFile(execution.file) as? AlloyFile
            ?: return NotFound(AlloyRunLog.FAILED, "$fileName is not an Alloy file")
        val model = when (val result = AlloyModelService.getInstance(project).model(psiFile)) {
            is AlloyModelResult.Model -> result
            is AlloyModelResult.Errors ->
                return NotFound(AlloyCommandExecution.MODEL_HAS_ERRORS, "$fileName has errors, see the problems reported in the editor")
            is AlloyModelResult.Unavailable ->
                return NotFound(AlloyRunLog.FAILED, "The Alloy analyzer failed: ${result.cause.message ?: result.cause}")
        }
        val notFound = NotFound(AlloyCommandExecution.COMMAND_NOT_FOUND, "Command ${execution.command.displayName} was not found in $fileName")
        val psiCommand = AlloyCommands.find(psiFile, execution.command) ?: return notFound
        val filename = AlloyPositions.filename(execution.file)
        val text = model.modules.contents[filename] ?: return notFound
        val document = DocumentImpl(text)
        // A command's position starts at its keyword, or at the last command of a `=>` chain
        val index = model.module.allCommands.indexOfFirst {
            it.pos.filename == filename && AlloyPositions.textRange(it.pos, document).startOffset in psiCommand.textRange
        }
        return if (index < 0) notFound else Target(model, filename, index)
    }

    companion object {
        const val WARNINGS_ARE_FATAL =
            "Warnings often indicate errors in the model. To analyze it anyway, turn on Non-fatal warnings in " +
                "Settings | Languages & Frameworks | Alloy."
    }
}
