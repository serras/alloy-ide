package com.serranofp.alloy.run.tests

import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.LocatableConfigurationBase
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.JDOMExternalizerUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.serranofp.alloy.AlloyFileType
import com.serranofp.alloy.run.AlloyCommandRef
import org.jdom.Element

/**
 * Runs the top-level commands of the Alloy file at [fileUrl], or only [commands] when there are any (as
 * *Rerun Failed Tests* does), in source order.
 */
class AlloyCommandsRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    LocatableConfigurationBase<Any>(project, factory, name) {
    /** The URL of the file, so that files of any file system (such as those of tests) can be run. */
    var fileUrl: String = ""
    var commands: List<AlloyCommandRef> = emptyList()

    fun file(): VirtualFile? = fileUrl.takeIf { it.isNotEmpty() }?.let { VirtualFileManager.getInstance().findFileByUrl(it) }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = AlloyCommandsSettingsEditor(project)

    override fun checkConfiguration() {
        val file = file() ?: throw RuntimeConfigurationError("The Alloy file is not found")
        if (file.fileType != AlloyFileType) throw RuntimeConfigurationError("${file.name} is not an Alloy file")
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState =
        AlloyCommandsRunState(this, commands)

    override fun suggestedName(): String? = file()?.let { suggestedName(it) }

    override fun writeExternal(element: Element) {
        super.writeExternal(element)
        JDOMExternalizerUtil.writeField(element, FILE, fileUrl)
        for (command in commands) {
            element.addContent(Element(COMMAND).setAttribute(INDEX, command.index.toString()).also { child ->
                command.label?.let { child.setAttribute(LABEL, it) }
            })
        }
    }

    override fun readExternal(element: Element) {
        super.readExternal(element)
        fileUrl = JDOMExternalizerUtil.readField(element, FILE).orEmpty()
        commands = element.getChildren(COMMAND).mapNotNull { child ->
            child.getAttributeValue(INDEX)?.toIntOrNull()?.let { AlloyCommandRef(child.getAttributeValue(LABEL), it) }
        }
    }

    override fun clone(): RunConfiguration = (super.clone() as AlloyCommandsRunConfiguration).also {
        it.fileUrl = fileUrl
        it.commands = commands
    }

    companion object {
        private const val FILE = "file"
        private const val COMMAND = "command"
        private const val INDEX = "index"
        private const val LABEL = "label"

        fun suggestedName(file: VirtualFile): String = "All commands in ${file.name}"
    }
}
