package com.serranofp.alloy.run.tests

import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.testframework.TestConsoleProperties
import com.intellij.execution.testframework.actions.AbstractRerunFailedTestsAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.ui.ComponentContainer

/** *Rerun Failed Tests*: a run of the same file restricted to the failed commands, in source order. */
class AlloyRerunFailedAction(container: ComponentContainer, properties: TestConsoleProperties) : AbstractRerunFailedTestsAction(container) {
    init {
        init(properties)
    }

    override fun getRunProfile(environment: ExecutionEnvironment): MyRunProfile? {
        val configuration = myConsoleProperties.configuration as? AlloyCommandsRunConfiguration ?: return null
        val failed = getFailedTests(configuration.project).mapNotNull { test -> test.locationUrl?.let(::commandOf) }
        if (failed.isEmpty()) return null
        return object : MyRunProfile(configuration) {
            override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState =
                AlloyCommandsRunState(configuration, failed)

            override fun getModules(): Array<Module> = Module.EMPTY_ARRAY
        }
    }

    private fun commandOf(locationUrl: String) =
        locationUrl.takeIf { it.startsWith("${AlloyTestLocator.PROTOCOL}://") }
            ?.let { AlloyTestLocator.parse(it.removePrefix("${AlloyTestLocator.PROTOCOL}://"))?.second }
}
