package com.serranofp.alloy.run.tests

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import com.serranofp.alloy.psi.AlloyFile

/** "Run 'All commands in a.als'" in the context menu of an Alloy file, in the editor and in the *Project* view. */
class AlloyCommandsConfigurationProducer : LazyRunConfigurationProducer<AlloyCommandsRunConfiguration>() {
    override fun getConfigurationFactory(): ConfigurationFactory = AlloyCommandsConfigurationType.getInstance().factory

    override fun setupConfigurationFromContext(
        configuration: AlloyCommandsRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>,
    ): Boolean {
        val file = context.psiLocation?.containingFile as? AlloyFile ?: return false
        val virtualFile = file.virtualFile ?: return false
        configuration.fileUrl = virtualFile.url
        configuration.commands = emptyList()
        configuration.name = AlloyCommandsRunConfiguration.suggestedName(virtualFile)
        sourceElement.set(file)
        return true
    }

    override fun isConfigurationFromContext(configuration: AlloyCommandsRunConfiguration, context: ConfigurationContext): Boolean {
        val file = context.psiLocation?.containingFile as? AlloyFile ?: return false
        return configuration.commands.isEmpty() && configuration.fileUrl == file.virtualFile?.url
    }
}
