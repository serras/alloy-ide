package com.serranofp.alloy.run

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.project.Project
import com.serranofp.alloy.AlloyIcons

class AlloyCommandConfigurationType : ConfigurationTypeBase(
    ID, "Alloy command", "Runs an Alloy run or check command", AlloyIcons.FILE,
) {
    init {
        addFactory(Factory(this))
    }

    val factory: ConfigurationFactory
        get() = configurationFactories.single()

    private class Factory(type: AlloyCommandConfigurationType) : ConfigurationFactory(type) {
        override fun getId(): String = ID

        override fun createTemplateConfiguration(project: Project): RunConfiguration =
            AlloyCommandRunConfiguration(project, this, "Alloy command")

        override fun getOptionsClass(): Class<out BaseState> = AlloyCommandOptions::class.java
    }

    companion object {
        const val ID = "AlloyCommand"

    }
}

fun getAlloyCommandConfigurationType(): AlloyCommandConfigurationType =
    ConfigurationTypeUtil.findConfigurationType(AlloyCommandConfigurationType::class.java)