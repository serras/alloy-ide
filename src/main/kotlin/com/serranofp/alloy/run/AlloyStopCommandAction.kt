package com.serranofp.alloy.run

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.SmartPointerManager
import com.intellij.ui.awt.RelativePoint
import com.serranofp.alloy.psi.AlloyCommandDecl
import java.awt.event.MouseEvent

/** Stops the running analysis of a command. */
class AlloyStopCommandAction(command: AlloyCommandDecl) :
    AnAction("Stop", "Stop the analysis of ${AlloyCommands.ref(command).displayName}", AllIcons.Actions.Suspend), DumbAware {
    private val pointer = SmartPointerManager.createPointer(command)

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val command = pointer.element ?: return
        val file = command.containingFile.virtualFile ?: return
        val ref = AlloyCommands.ref(command)
        AlloyRunService.getInstance(command.project).activeExecution
            ?.takeIf { it.file == file && it.command == ref }
            ?.stop()
    }
}

/**
 * The gutter action of a running command. A gutter icon with a single action runs it on click, so this one
 * opens a menu with [group], which holds the Stop action.
 */
class AlloyRunningCommandMenuAction(command: AlloyCommandDecl) :
    AnAction("Running ${AlloyCommands.ref(command).displayName}…", null, AllIcons.Actions.Suspend), DumbAware {
    val group = DefaultActionGroup(AlloyStopCommandAction(command))

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val popup = JBPopupFactory.getInstance()
            .createActionGroupPopup(null, group, e.dataContext, JBPopupFactory.ActionSelectionAid.MNEMONICS, true)
        val mouse = e.inputEvent as? MouseEvent
        if (mouse != null) popup.show(RelativePoint(mouse)) else popup.showInBestPositionFor(e.dataContext)
    }
}
