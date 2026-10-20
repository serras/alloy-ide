package com.serranofp.alloy.viewer

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import edu.mit.csail.sdg.alloy4graph.GraphViewer
import edu.mit.csail.sdg.alloy4viz.AlloyAtom
import edu.mit.csail.sdg.alloy4viz.AlloyProjection
import edu.mit.csail.sdg.alloy4viz.AlloyType
import edu.mit.csail.sdg.alloy4viz.VizState
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JLayer
import javax.swing.JPanel
import javax.swing.JViewport
import javax.swing.Scrollable
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
 * through a trace and projecting update it in place. A trace shows the selected state and its successor, side by
 * side or stacked; under a projection, one atom selector per projected signature applies to both graphs.
 */
class AlloyInstancePanel(private val project: Project, parent: Disposable) : JPanel(CardLayout()) {
    private val viewer = AlloyInstanceViewer.getInstance(project)

    private val header = ActionLink("") { viewer.navigateToOrigin() }
    private val status = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val stepper = JBLabel()
    private val loop = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val projection = AlloyProjectionChooser { redrawGraph() }
    private val tabs = JBTabbedPane()
    private val graphs = AlloyStatePanes()
    private val selectors = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(2)))
    private val darkGraphs = DarkGraphLayerUI()
    private val text = AlloyTextPane()
    private val tables = AlloyStatePanes()
    private val toolbar: ActionToolbar

    /** The atom shown for each projected signature, kept while that atom exists. */
    private val chosenAtoms = mutableMapOf<AlloyType, AlloyAtom>()

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
        val visualization = JPanel(BorderLayout()).apply {
            add(graphs, BorderLayout.CENTER)
            add(selectors, BorderLayout.SOUTH)
        }
        darkGraphs.followTheme(parent, visualization::repaint)
        tabs.addTab(AlloyInstanceViewer.VISUALIZATION, visualization)
        tabs.addTab(AlloyInstanceViewer.TEXT, JBScrollPane(text))
        tabs.addTab(AlloyInstanceViewer.TABLE, tables)
        tabs.addChangeListener { tabs.selectedIndex.takeIf { it >= 0 }?.let { viewer.selectedTab = tabs.getTitleAt(it) } }
        add(JPanel(BorderLayout()).apply { add(north, BorderLayout.NORTH); add(tabs, BorderLayout.CENTER) }, SESSION)

        viewer.addListener(parent, ::rebuild)
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) = updateHeader()
        }, parent)
        // The diff's colors are the editor color scheme's
        ApplicationManager.getApplication().messageBus.connect(parent).subscribe(EditorColorsManager.TOPIC, EditorColorsListener { showText() })
        rebuild()
    }

    private fun textArea() = JBTextArea().apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, UIUtil.getLabelFont().size)
    }

    /** The analyzer's text of a single state, or the difference from a trace state to its successor. */
    private fun showText() {
        val model = viewer.session?.model ?: return
        model.diff?.let(text::showDiff) ?: text.showText(model.text)
    }

    private fun tablePane(table: String) = JBScrollPane(textArea().apply { text = table; caretPosition = 0 })

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
        chosenAtoms.clear()
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
        showText()
        val next = model.nextTable
        tables.show(
            if (next == null) listOf(null to tablePane(model.table))
            else listOf(model.caption to tablePane(model.table), model.nextCaption to tablePane(next)),
        )
        redrawGraph()
    }

    /** Rebuilds the atom selectors and the graphs, of one state or of a trace state and its successor. */
    private fun redrawGraph() {
        val model = viewer.session?.model ?: return
        val theme = model.vizState
        val shown = linkedMapOf<AlloyType, AlloyAtom>()
        selectors.removeAll()
        chosenAtoms.keys.retainAll(theme.projectedTypes)
        for (type in theme.projectedTypes) {
            val atoms = theme.originalInstance.type2atoms(type).sorted()
            val atom = chosenAtoms[type]?.takeIf { it in atoms } ?: atoms.firstOrNull()
            if (atom == null) chosenAtoms.remove(type) else chosenAtoms[type] = atom
            atom?.let { shown[type] = it }
            selectors.add(JBLabel("${type.name}:"))
            selectors.add(atomSelector(theme, type, atoms, atom))
        }
        selectors.isVisible = selectors.componentCount > 0
        val choice = AlloyProjection(shown)
        val next = model.nextVizState
        graphs.show(
            if (next == null) listOf(null to graphPane(theme, choice))
            else listOf(model.caption to graphPane(theme, choice), model.nextCaption to graphPane(next, choice)),
        )
        selectors.revalidate()
        selectors.repaint()
    }

    private fun atomSelector(theme: VizState, type: AlloyType, atoms: List<AlloyAtom>, atom: AlloyAtom?): ComboBox<AlloyAtom> =
        ComboBox(atoms.toTypedArray()).apply {
            renderer = textListCellRenderer { it.getVizName(theme, true) }
            selectedItem = atom
            isEnabled = atoms.isNotEmpty()
            addActionListener {
                val selected = selectedItem as? AlloyAtom ?: return@addActionListener
                if (chosenAtoms[type] == selected) return@addActionListener
                chosenAtoms[type] = selected
                redrawGraph()
            }
        }

    /**
     * The graph of [theme] under [choice], on a white canvas that the dark mapping recolors, scrolled. As in the
     * visualizer, right-clicking the canvas around the graph shows the graph's zoom popup too.
     */
    private fun graphPane(theme: VizState, choice: AlloyProjection): JComponent {
        val graph = theme.getGraph(WindowManager.getInstance().getFrame(project), choice)
        val canvas = GraphCanvas().apply { add(graph) }
        if (graph is GraphViewer) canvas.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (e.button == MouseEvent.BUTTON3 || (e.button == MouseEvent.BUTTON1 && e.isControlDown)) graph.alloyPopup(canvas, e.x, e.y)
            }
        })
        return JBScrollPane(JLayer(canvas, darkGraphs))
    }

    /** The white canvas around a graph, which fills the visible area when the graph is smaller. */
    private class GraphCanvas : JPanel(), Scrollable {
        init {
            background = Color.WHITE
        }

        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = JBUI.scale(16)
        override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) =
            if (orientation == SwingConstants.VERTICAL) visibleRect.height else visibleRect.width
        override fun getScrollableTracksViewportWidth() = (parent?.parent as? JViewport)?.let { it.width > preferredSize.width } ?: false
        override fun getScrollableTracksViewportHeight() = (parent?.parent as? JViewport)?.let { it.height > preferredSize.height } ?: false
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
