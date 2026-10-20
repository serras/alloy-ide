package com.serranofp.alloy.run

import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiElement
import com.intellij.ui.AnimatedIcon
import com.serranofp.alloy.psi.AlloyCommandDecl
import com.serranofp.alloy.psi.AlloyFile
import com.serranofp.alloy.psi.AlloyTypes

/**
 * A run icon on the `run`/`check` keyword of every top-level command (the first one of a `=>` chain),
 * which spins while the command is running. [AlloyCommandResults] refreshes the editor when that changes.
 */
class AlloyRunLineMarkerContributor : RunLineMarkerContributor() {
    override fun getInfo(element: PsiElement): Info? {
        val type = element.node.elementType
        if (type != AlloyTypes.RUN && type != AlloyTypes.CHECK) return null
        val command = element.parent as? AlloyCommandDecl ?: return null
        if (command.firstChild !== element || command.parent !is AlloyFile) return null
        val ref = AlloyCommands.ref(command)
        val running = command.containingFile.virtualFile
            ?.let { AlloyCommandResults.getInstance(command.project).get(it, ref) }
            ?.state == CommandState.Running
        return if (running) {
            Info(AnimatedIcon.Default.INSTANCE, arrayOf(AlloyRunCommandAction(command))) { "Running ${ref.displayName}…" }
        } else {
            Info(AllIcons.RunConfigurations.TestState.Run, arrayOf(AlloyRunCommandAction(command))) { "Run ${ref.displayName}" }
        }
    }
}
