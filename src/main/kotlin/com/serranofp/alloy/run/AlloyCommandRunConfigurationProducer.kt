package com.serranofp.alloy.run

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.serranofp.alloy.psi.AlloyCommandDecl
import com.serranofp.alloy.psi.AlloyTypes

class AlloyCommandRunConfigurationProducer : LazyRunConfigurationProducer<AlloyCommandRunConfiguration>() {
    override fun getConfigurationFactory(): ConfigurationFactory = getAlloyCommandConfigurationType().factory

    override fun setupConfigurationFromContext(
        configuration: AlloyCommandRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>,
    ): Boolean {
        val command = commandAt(context) ?: return false
        val file = command.containingFile.virtualFile ?: return false
        val ref = AlloyCommands.ref(command)
        configuration.filePath = file.path
        configuration.command = ref
        configuration.name = "${command.firstChild.text} ${ref.displayName}"
        sourceElement.set(command)
        return true
    }

    override fun isConfigurationFromContext(
        configuration: AlloyCommandRunConfiguration,
        context: ConfigurationContext,
    ): Boolean {
        val command = commandAt(context) ?: return false
        val file = command.containingFile.virtualFile ?: return false
        return configuration.filePath == file.path && configuration.command == AlloyCommands.ref(command)
    }

    private fun commandAt(context: ConfigurationContext): AlloyCommandDecl? {
        val location = context.psiLocation ?: return null
        val command = PsiTreeUtil.getParentOfType(location, AlloyCommandDecl::class.java, false) ?: return null
        // Only the command itself, not an element reached from somewhere else in the file
        return command.takeIf { it.firstChild.node.elementType.let { t -> t == AlloyTypes.RUN || t == AlloyTypes.CHECK } }
    }
}
