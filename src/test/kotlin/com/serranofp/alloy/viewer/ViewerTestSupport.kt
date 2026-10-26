package com.serranofp.alloy.viewer

import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.UIUtil
import com.serranofp.alloy.run.AlloyCommandRef
import com.serranofp.alloy.run.AlloyCommandResults
import com.serranofp.alloy.run.AlloyRunService
import com.serranofp.alloy.run.CommandLink
import com.serranofp.alloy.run.CommandResult
import com.serranofp.alloy.run.CommandState
import com.serranofp.alloy.run.waitUntil
import com.serranofp.alloy.settings.AlloySettings
import edu.mit.csail.sdg.alloy4.A4Reporter
import edu.mit.csail.sdg.parser.CompUtil
import edu.mit.csail.sdg.translator.A4Options
import edu.mit.csail.sdg.translator.A4Solution
import edu.mit.csail.sdg.translator.TranslateAlloyToKodkod
import java.awt.BorderLayout
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.KeyStroke

/** The solution of the first command of [model], solved here (the engine's own tests cover the engine). */
fun solve(model: String): A4Solution = link(model).solution

/** The solution of the first command of [model] with the module it was parsed into, as a run links them. */
fun link(model: String): CommandLink.Solution {
    val module = CompUtil.parseEverything_fromString(A4Reporter.NOP, model)
    return CommandLink.Solution(TranslateAlloyToKodkod.execute_command(A4Reporter.NOP, module.allReachableSigs, module.allCommands.first(), A4Options()), module)
}

const val STATIC = "sig Node { succ: set Node }\nrun { some succ } for 2"

const val ORDERING = "open util/ordering[State]\nsig State { v: set State }\nrun {} for 3"

/** Exactly one trace: states 0, 1 and 2, then back to state 1. */
const val LASSO = "abstract sig V {}\none sig V1, V2, V3 extends V {}\nvar one sig Cur in V {}\n" +
    "fact { Cur = V1 and after Cur = V2 and always (Cur = V2 implies after Cur = V3) and always (Cur = V3 implies after Cur = V2) }\n" +
    "run {} for 3 but 1..3 steps"

/** Exactly one trace: states 0 and 1, then state 1 forever. */
const val SELF_LOOP = "abstract sig V {}\none sig V1, V2 extends V {}\nvar one sig Cur in V {}\n" +
    "fact { Cur = V1 and after always Cur = V2 }\n" +
    "run {} for 2 but 1..2 steps"

/** Many traces of up to 4 states. */
const val TRACES = "sig A {}\nvar sig X in A {}\nrun { some X and after no X } for 3 but 1..4 steps"

/** The model file that the tests' results are of: its command `show` is [SHOW]. */
const val SHOW_FILE = "pred show {}\nrun show for 3\n"

val SHOW = AlloyCommandRef("show", 0)

/**
 * A test of the instance viewer. Light tests share their project, and with it the viewer, its persisted state, the
 * evaluator's watches and the project's analysis: each test starts with none of them, and ends with no
 * enumeration running, no execution active, and every one of them reset.
 */
abstract class AlloyViewerTestCase : BasePlatformTestCase() {
    protected val viewer: AlloyInstanceViewer get() = AlloyInstanceViewer.getInstance(project)
    protected val results: AlloyCommandResults get() = AlloyCommandResults.getInstance(project)

    override fun setUp() {
        super.setUp()
        reset()
    }

    override fun tearDown() {
        try {
            viewer.stopEnumeration()
            waitUntil("the enumeration did not stop") { !viewer.enumerating }
            AlloyRunService.getInstance(project).activeExecution?.terminate()
            reset()
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun reset() {
        results.clear()
        viewer.clear()
        viewer.loadState(AlloyInstanceViewerState())
        AlloyEvaluatorState.getInstance(project).loadState(AlloyEvaluatorWatches())
    }

    /** A result that found the solution of [model]'s first command, current for [file]'s text and the settings. */
    protected fun result(
        model: String, file: VirtualFile = myFixture.file.virtualFile, state: CommandState = CommandState.InstanceFound, summary: String = "found",
    ): CommandResult {
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        return CommandResult(
            state, summary, link = link(model),
            documentStamp = results.stamp(document), settingsGeneration = AlloySettings.getInstance().generation,
        )
    }

    /** Opens the solution of [model] as the result of [command] in [file], and lets the viewers show it. */
    protected fun open(model: String, file: VirtualFile = myFixture.file.virtualFile, command: AlloyCommandRef = SHOW): ViewerSession {
        viewer.open(file, command, result(model, file))
        UIUtil.dispatchAllInvocationEvents()
        return viewer.session(file)!!
    }

    /** A new viewer of [file], until the test ends. */
    protected fun panel(file: VirtualFile = myFixture.file.virtualFile): AlloyInstancePanel = AlloyInstancePanel(project, file, testRootDisposable)

    /** A new viewer of [file] showing the solution of [model], as the result of `show` in it, by default a new [SHOW_FILE]. */
    protected fun panelFor(model: String, file: VirtualFile = myFixture.configureByText("m.als", SHOW_FILE).virtualFile): AlloyInstancePanel =
        panel(file).also { open(model, file) }
}

/** Presses the left button on [state]'s circle, as selecting it with the mouse starts with. */
fun AlloyTraceStrip.pressState(state: Int) {
    val circle = geometry!!.circles[state]
    dispatchEvent(MouseEvent(this, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, circle.centerX.toInt(), circle.centerY.toInt(), 1, false, MouseEvent.BUTTON1))
}

/**
 * Runs the action of this component for [stroke], as pressing it in the focused component does: only if its
 * `update` enables it. Returns whether it ran.
 */
fun JComponent.press(stroke: KeyStroke): Boolean {
    val action = ActionUtil.getActions(this).single { action -> action.shortcutSet.shortcuts.any { (it as? KeyboardShortcut)?.firstKeyStroke == stroke } }
    val event = TestActionEvent.createTestEvent(action)
    action.update(event)
    if (!event.presentation.isEnabled) return false
    action.actionPerformed(event)
    return true
}

/** Runs the action of this component for [key], without modifiers. */
fun JComponent.press(key: Int): Boolean = press(KeyStroke.getKeyStroke(key, 0))

/** The captions shown above the panes, as their labels show them; null for a pane without one. */
val AlloyStatePanes.shownCaptions: List<String?>
    get() = panes.map { pane -> ((pane.layout as BorderLayout).getLayoutComponent(BorderLayout.NORTH) as? JBLabel)?.text }
