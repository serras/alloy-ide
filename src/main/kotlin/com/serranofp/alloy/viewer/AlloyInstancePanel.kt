package com.serranofp.alloy.viewer

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import edu.mit.csail.sdg.alloy4viz.VizGraphPanel
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.SwingConstants

class AlloyInstanceToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = AlloyInstancePanel(project, toolWindow.disposable)
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(panel, null, false))
    }
}

/**
 * The tool window: the empty state, or the session's header (which navigates to its command), toolbar and
 * status line, over the Visualization, Text and Table tabs. Rebuilt when the session changes; stepping
 * through a trace and projecting update it in place.
 */
class AlloyInstancePanel(private val project: Project, parent: Disposable) : JPanel(CardLayout()) {
    private val viewer = AlloyInstanceViewer.getInstance(project)

    private val header = ActionLink("") { viewer.navigateToOrigin() }
    private val status = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val stepper = JBLabel()
    private val loop = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val projection = AlloyProjectionChooser { redrawGraph() }
    private val tabs = JBTabbedPane()
    private val graph = JPanel(BorderLayout())
    private val text = textArea()
    private val table = textArea()
    private val toolbar: ActionToolbar
    private var graphPanel: VizGraphPanel? = null

    init {
        add(JBLabel(AlloyInstanceViewer.EMPTY_TEXT, SwingConstants.CENTER).apply { foreground = UIUtil.getContextHelpForeground() }, EMPTY)

        val actions = DefaultActionGroup().apply {
            SolutionKind.entries.forEach { add(NewSolutionAction(it)) }
            addSeparator()
            add(StepAction("Previous State", AllIcons.Actions.Back) { it.previous() })
            add(StepAction("Next State", AllIcons.Actions.Forward) { it.next() })
        }
        toolbar = ActionManager.getInstance().createActionToolbar("AlloyInstance", actions, true)
        toolbar.targetComponent = this

        val controls = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            add(toolbar.component)
            add(stepper)
            add(loop)
            add(JBLabel("Projection:"))
            add(projection.link)
        }
        val north = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(4, 8)
            add(header.apply { alignmentX = LEFT_ALIGNMENT })
            add(controls.apply { alignmentX = LEFT_ALIGNMENT })
            add(status.apply { alignmentX = LEFT_ALIGNMENT })
        }
        tabs.addTab(AlloyInstanceViewer.VISUALIZATION, graph)
        tabs.addTab(AlloyInstanceViewer.TEXT, JBScrollPane(text))
        tabs.addTab(AlloyInstanceViewer.TABLE, JBScrollPane(table))
        tabs.addChangeListener { tabs.selectedIndex.takeIf { it >= 0 }?.let { viewer.selectedTab = tabs.getTitleAt(it) } }
        add(JPanel(BorderLayout()).apply { add(north, BorderLayout.NORTH); add(tabs, BorderLayout.CENTER) }, SESSION)

        viewer.addListener(parent, ::rebuild)
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) = updateHeader()
        }, parent)
        rebuild()
    }

    private fun textArea() = JBTextArea().apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, UIUtil.getLabelFont().size)
    }

    private var shown: ViewerSession? = null

    /** Shows the current session; a new session gets a new graph, with the tab the user last chose. */
    fun rebuild() {
        val session = viewer.session
        (layout as CardLayout).show(this, if (session == null) EMPTY else SESSION)
        status.text = viewer.status.orEmpty()
        status.isVisible = viewer.status != null
        toolbar.updateActionsAsync()
        if (session == null || session === shown) return updateHeader()
        shown = session
        val model = session.model
        graph.removeAll()
        graphPanel = VizGraphPanel(WindowManager.getInstance().getFrame(project), listOf(model.vizState), false).also { graph.add(it) }
        projection.show(model)
        val tab = (0 until tabs.tabCount).firstOrNull { tabs.getTitleAt(it) == viewer.selectedTab } ?: 0
        tabs.selectedIndex = tab
        stateChanged()
        updateHeader()
    }

    private fun updateHeader() {
        val session = viewer.session ?: return
        header.text = viewer.header(session)
        header.isEnabled = true
        header.foreground = if (viewer.outdated(session) != null) UIUtil.getInactiveTextColor() else null
    }

    /** Shows the selected state in every tab, after the stepper changed it. */
    fun stateChanged() {
        val model = viewer.session?.model ?: return
        stepper.isVisible = model.hasStates
        loop.isVisible = model.hasStates
        stepper.text = model.stepperText
        loop.text = model.loopText
        text.text = model.text
        text.caretPosition = 0
        table.text = model.table
        table.caretPosition = 0
        redrawGraph()
    }

    private fun redrawGraph() {
        val panel = graphPanel ?: return
        panel.resetProjectionAtomCombos()
        panel.remakeAll(WindowManager.getInstance().getFrame(project))
        graph.revalidate()
        graph.repaint()
    }

    private inner class NewSolutionAction(private val kind: SolutionKind) : AnAction(kind.label, kind.description, kind.icon), DumbAware {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            val session = viewer.session
            e.presentation.isVisible = session != null
            e.presentation.isEnabled = viewer.isEnabled(kind)
            e.presentation.description = if (session != null && viewer.outdated(session) != null) AlloyInstanceViewer.RUN_AGAIN else kind.description
        }

        override fun actionPerformed(e: AnActionEvent) {
            viewer.requestSolution(kind)
        }
    }

    private inner class StepAction(text: String, icon: Icon, private val step: (AlloySolutionModel) -> Unit) : AnAction(text, null, icon), DumbAware {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isVisible = viewer.session?.model?.hasStates == true
        }

        override fun actionPerformed(e: AnActionEvent) {
            val model = viewer.session?.model ?: return
            step(model)
            stateChanged()
        }
    }

    private companion object {
        const val EMPTY = "empty"
        const val SESSION = "session"
    }
}
