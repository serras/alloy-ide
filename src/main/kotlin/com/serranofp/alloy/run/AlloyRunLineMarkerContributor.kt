package com.serranofp.alloy.run

import com.intellij.execution.lineMarker.ExecutorAction
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiElement
import com.serranofp.alloy.psi.AlloyCommandDecl
import com.serranofp.alloy.psi.AlloyFile
import com.serranofp.alloy.psi.AlloyTypes

/** A run icon on the `run`/`check` keyword of every top-level command (the first one of a `=>` chain). */
class AlloyRunLineMarkerContributor : RunLineMarkerContributor() {
    override fun getInfo(element: PsiElement): Info? {
        val type = element.node.elementType
        if (type != AlloyTypes.RUN && type != AlloyTypes.CHECK) return null
        val command = element.parent as? AlloyCommandDecl ?: return null
        if (command.firstChild !== element || command.parent !is AlloyFile) return null
        return Info(AllIcons.RunConfigurations.TestState.Run, ExecutorAction.getActions(0)) {
            "Run ${AlloyCommands.ref(command).displayName}"
        }
    }
}
