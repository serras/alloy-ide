package com.serranofp.alloy.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile

class AlloyCommandOptions : RunConfigurationOptions() {
    var filePath by string()

    /** Label of the command; empty for unlabelled commands, which are found by [commandIndex]. */
    var commandLabel by string()

    var commandIndex by property(0)
}

class AlloyCommandRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    RunConfigurationBase<AlloyCommandOptions>(project, factory, name) {

    override fun getOptions(): AlloyCommandOptions = super.getOptions() as AlloyCommandOptions

    var filePath: String
        get() = options.filePath.orEmpty()
        set(value) {
            options.filePath = value
        }

    var command: AlloyCommandRef
        get() = AlloyCommandRef(options.commandLabel?.takeIf { it.isNotBlank() }, options.commandIndex)
        set(value) {
            options.commandLabel = value.label
            options.commandIndex = value.index
        }

    fun findFile(): VirtualFile? =
        filePath.takeIf { it.isNotBlank() }?.let { LocalFileSystem.getInstance().refreshAndFindFileByPath(it) }

    override fun checkConfiguration() {
        if (filePath.isBlank()) throw RuntimeConfigurationError("The Alloy model file is not set")
        if (findFile() == null) throw RuntimeConfigurationError("The Alloy model file $filePath does not exist")
        if (command.label == null && command.index < 0) throw RuntimeConfigurationError("The command is not set")
    }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = AlloyCommandSettingsEditor(project)

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
        val file = findFile() ?: throw ExecutionException("The Alloy model file $filePath does not exist")
        return AlloyCommandRunState(project, file, command)
    }
}
