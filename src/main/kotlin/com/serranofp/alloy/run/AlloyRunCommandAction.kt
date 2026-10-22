package com.serranofp.alloy.run

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.SmartPointerManager
import com.serranofp.alloy.AlloyBundle
import com.serranofp.alloy.psi.AlloyCommandDecl

/** The gutter action of a command: executes it right away, with its result shown inline. */
class AlloyRunCommandAction(command: AlloyCommandDecl) :
    AnAction(AlloyBundle.message("run.action.run", AlloyCommands.ref(command).displayName), null, AllIcons.RunConfigurations.TestState.Run), DumbAware {
    private val pointer = SmartPointerManager.createPointer(command)

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val command = pointer.element ?: return
        val file = command.containingFile.virtualFile ?: return
        AlloyRunService.getInstance(command.project).launch(file, AlloyCommands.ref(command), editor)
    }
}
