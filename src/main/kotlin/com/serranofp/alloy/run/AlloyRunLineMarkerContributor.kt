package com.serranofp.alloy.run

import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.ui.AnimatedIcon
import com.serranofp.alloy.psi.AlloyCommandDecl
import com.serranofp.alloy.psi.AlloyFile
import com.serranofp.alloy.psi.AlloyTypes

/**
 * A run icon on the `run`/`check` keyword of every top-level command (the first one of a `=>` chain),
 * which spins while the command is running (also during its vacuity check); clicking the spinner opens a
 * menu with Stop. Afterwards the icon shows the [verdict] of the latest result, even when it is outdated,
 * and clicking it runs the command again. [AlloyCommandResults] refreshes the editor when that changes.
 */
class AlloyRunLineMarkerContributor : RunLineMarkerContributor() {
    override fun getInfo(element: PsiElement): Info? {
        val type = element.node.elementType
        if (type != AlloyTypes.RUN && type != AlloyTypes.CHECK) return null
        val command = element.parent as? AlloyCommandDecl ?: return null
        if (command.firstChild !== element || command.parent !is AlloyFile) return null
        val ref = AlloyCommands.ref(command)
        val results = AlloyCommandResults.getInstance(command.project)
        val result = command.containingFile.virtualFile?.let { results.get(it, ref) }
        if (result != null && (result.state == CommandState.Running || result.vacuity == Vacuity.Checking)) {
            return Info(AnimatedIcon.Default.INSTANCE, arrayOf(AlloyRunningCommandMenuAction(command))) { "Running ${ref.displayName}…" }
        }
        val verdict = result?.let(::verdict) ?: Verdict.Run
        val icon = when (verdict) {
            Verdict.Run -> AllIcons.RunConfigurations.TestState.Run
            Verdict.Passed -> AllIcons.RunConfigurations.TestState.Green2
            Verdict.Failed -> AllIcons.RunConfigurations.TestState.Red2
            Verdict.Vacuous -> AllIcons.RunConfigurations.TestState.Yellow2
        }
        return Info(icon, arrayOf(AlloyRunCommandAction(command))) { tooltip(it, ref, result, verdict) }
    }

    /** "Run noCycles — passed last time (outdated)"; read when hovered, so that it follows the edits. */
    private fun tooltip(element: PsiElement, ref: AlloyCommandRef, result: CommandResult?, verdict: Verdict): String {
        val last = when (verdict) {
            Verdict.Run -> return "Run ${ref.displayName}"
            Verdict.Passed -> "passed"
            Verdict.Failed -> "failed"
            Verdict.Vacuous -> "passed vacuously"
        }
        val document = PsiDocumentManager.getInstance(element.project).getDocument(element.containingFile)
        val outdated = result != null && document != null && AlloyCommandResults.getInstance(element.project).isOutdated(result, document)
        return "Run ${ref.displayName} — $last last time" + if (outdated) " (outdated)" else ""
    }
}
