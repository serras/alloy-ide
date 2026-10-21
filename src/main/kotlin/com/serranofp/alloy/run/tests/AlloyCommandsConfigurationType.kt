package com.serranofp.alloy.run.tests

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.project.Project
import com.serranofp.alloy.AlloyIcons

/** *Alloy Commands*: every command of one Alloy file, run in the IDE's test runner. */
class AlloyCommandsConfigurationType : ConfigurationTypeBase(ID, "Alloy Commands", "Run the commands of an Alloy file as tests", AlloyIcons.FILE) {
    val factory: ConfigurationFactory = object : ConfigurationFactory(this) {
        override fun getId(): String = ID

        override fun createTemplateConfiguration(project: Project): RunConfiguration =
            AlloyCommandsRunConfiguration(project, this, "Alloy Commands")
    }

    init {
        addFactory(factory)
    }

    companion object {
        const val ID = "AlloyCommands"

        fun getInstance(): AlloyCommandsConfigurationType = ConfigurationTypeUtil.findConfigurationType(AlloyCommandsConfigurationType::class.java)
    }
}
