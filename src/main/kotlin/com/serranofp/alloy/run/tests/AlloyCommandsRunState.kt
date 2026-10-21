package com.serranofp.alloy.run.tests

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import com.intellij.execution.testframework.sm.runner.SMTestLocator
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.execution.ui.ConsoleView
import com.serranofp.alloy.run.AlloyCommandRef

/** The console of a suite: the SM test runner's tree, fed by [AlloyCommandsProcessHandler]. */
class AlloyCommandsRunState(
    private val configuration: AlloyCommandsRunConfiguration,
    private val commands: List<AlloyCommandRef>,
) : RunProfileState {
    override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
        val file = configuration.file() ?: throw ExecutionException("The Alloy file is not found")
        val handler = AlloyCommandsProcessHandler(configuration.project, file, commands)
        val properties = AlloyCommandsConsoleProperties(configuration, executor)
        val console = SMTestRunnerConnectionUtil.createAndAttachConsole(FRAMEWORK, handler, properties)
        val result = DefaultExecutionResult(console, handler)
        val rerun = properties.createRerunFailedTestsAction(console)
        rerun.setModelProvider { (console as SMTRunnerConsoleView).resultsViewer }
        result.setRestartActions(rerun)
        return result
    }

    companion object {
        const val FRAMEWORK = "Alloy"
    }
}

class AlloyCommandsConsoleProperties(configuration: RunConfiguration, executor: Executor) :
    SMTRunnerConsoleProperties(configuration, AlloyCommandsRunState.FRAMEWORK, executor) {
    override fun getTestLocator(): SMTestLocator = AlloyTestLocator

    override fun createRerunFailedTestsAction(consoleView: ConsoleView): AlloyRerunFailedAction =
        AlloyRerunFailedAction(consoleView, this)
}
