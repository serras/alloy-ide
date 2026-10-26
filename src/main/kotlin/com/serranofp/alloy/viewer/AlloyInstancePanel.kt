package com.serranofp.alloy.viewer

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Splitter
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.PopupHandler
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.serranofp.alloy.AlloyBundle
import edu.mit.csail.sdg.alloy4graph.GraphViewer
import edu.mit.csail.sdg.alloy4viz.AlloyAtom
import edu.mit.csail.sdg.alloy4viz.AlloyProjection
import edu.mit.csail.sdg.alloy4viz.AlloyType
import edu.mit.csail.sdg.alloy4viz.VizState
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.LayoutManager
import java.awt.Rectangle
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JViewport
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import kotlin.math.max

/**
 * The viewer of [file]: the empty state, or the session's header (which navigates to its command), toolbar and
 * status line, over the Visualization, Text and Table tabs. Rebuilt when the session changes; stepping
 * through a trace and projecting update it in place; the projection is this viewer's own, also when another viewer
 * shows the same session. A trace shows the selected state and its successor, side by
 * side or stacked, and the trace path strip, after the toolbar, which highlights both and selects the state
 * clicked, or stepped to with its keys; under a projection, one atom selector per projected signature applies to both graphs. The Evaluator
 * panel, which a toolbar toggle shows, sits under the tabs and follows the session and its selected state.
 * Selecting a state does the analyzer's work for it off the EDT, the first time; only the graphs are made on it.
 */
class AlloyInstancePanel(private val project: Project, private val file: VirtualFile, parent: Disposable) : JPanel(CardLayout()) {
    private val viewer = AlloyInstanceViewer.getInstance(project)

    private val header = ActionLink("") { viewer.navigateToOrigin(file) }
    private val status = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val projection = AlloyProjectionChooser { redrawGraph() }
    private val tabs = JBTabbedPane()
    private val graphs = AlloyStatePanes()
    private val selectors = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(2)))
    private val darkGraphs = DarkGraphTheme()
    private val text = AlloyTextPane()
    private val tables = AlloyStatePanes()
    private val strip = AlloyTraceStrip(parent, onSelect = ::goTo)
    private val toolbar: ActionToolbar
    private val evaluator = AlloyEvaluatorPanel(project, parent)
    private val splitter = OnePixelSplitter(true, viewer.evaluatorProportion)

    /** The atom shown for each projected signature, kept while that atom exists. */
    private val chosenAtoms = mutableMapOf<AlloyType, AlloyAtom>()

    /** How many states were selected, so that only the latest one is shown when their work ends out of order. */
    private var selections = 0L

    init {
        add(JBLabel(AlloyInstanceViewer.EMPTY_TEXT, SwingConstants.CENTER).apply { foreground = UIUtil.getContextHelpForeground() }, EMPTY)

        val actions = DefaultActionGroup().apply {
            SolutionKind.entries.forEach { add(NewSolutionAction(it)) }
            addSeparator()
            add(EvaluatorToggle())
        }
        toolbar = ActionManager.getInstance().createActionToolbar("AlloyInstance", actions, true)
        toolbar.targetComponent = this

        val controls = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            add(toolbar.component)
            add(JBLabel(AlloyBundle.message("viewer.projection")))
            add(projection.link)
        }
        val toolbarLine = JPanel().apply {
            layout = ToolbarLineLayout(controls, strip.scrollPane)
            isOpaque = false
            add(controls.apply { isOpaque = false })
            add(strip.scrollPane)
        }
        val north = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(4, 8)
            add(header.apply { alignmentX = LEFT_ALIGNMENT })
            add(toolbarLine.apply { alignmentX = LEFT_ALIGNMENT })
            add(status.apply { alignmentX = LEFT_ALIGNMENT })
        }
        val visualization = JPanel(BorderLayout()).apply {
            add(graphs, BorderLayout.CENTER)
            add(selectors, BorderLayout.SOUTH)
        }
        darkGraphs.followTheme(parent, visualization::repaint)
        tabs.addTab(AlloyBundle.message("viewer.tab.visualization"), visualization)
        tabs.addTab(AlloyBundle.message("viewer.tab.text"), JBScrollPane(text))
        tabs.addTab(AlloyBundle.message("viewer.tab.table"), tables)
        tabs.addChangeListener { TAB_IDS.getOrNull(tabs.selectedIndex)?.let { viewer.selectedTab = it } }
        splitter.firstComponent = tabs
        splitter.secondComponent = evaluator
        evaluator.isVisible = viewer.evaluatorVisible
        splitter.addPropertyChangeListener(Splitter.PROP_PROPORTION) { viewer.evaluatorProportion = splitter.proportion }
        add(JPanel(BorderLayout()).apply { add(north, BorderLayout.NORTH); add(splitter, BorderLayout.CENTER) }, SESSION)

        viewer.addListener(file, parent, ::rebuild)
        // Only an edit of the session's file outdates it, or moves its command
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (FileDocumentManager.getInstance().getFile(event.document) == file) updateHeader()
            }
        }, parent)
        // The diff's colors and the fonts are the editor color scheme's
        ApplicationManager.getApplication().messageBus.connect(parent).subscribe(EditorColorsManager.TOPIC, EditorColorsListener {
            showText()
            showTables()
        })
        rebuild()
    }

    private fun textArea() = JBTextArea().apply {
        isEditable = false
        font = AlloyTextPane.editorFont()
    }

    /** The analyzer's text of a single state, or the difference from a trace state to its successor. */
    private fun showText() {
        val model = session?.model ?: return
        model.diff?.let(text::showDiff) ?: text.showText(model.text)
    }

    private fun tablePane(table: String) = JBScrollPane(textArea().apply { text = table; caretPosition = 0 })

    /** The session of this panel's file. */
    private val session: ViewerSession? get() = viewer.session(file)

    private var shown: ViewerSession? = null

    /** Shows the current session; a new session gets a new graph, with the tab the user last chose. */
    fun rebuild() {
        val session = this.session
        (layout as CardLayout).show(this, if (session == null) EMPTY else SESSION)
        val message = viewer.status(file)
        status.text = message.orEmpty()
        status.isVisible = message != null
        toolbar.updateActionsAsync()
        if (session == null || session === shown) return updateHeader()
        // A new solution of the same command keeps this viewer's projection; another result starts from its own
        val sameCommand = shown?.origin == session.origin
        shown = session
        val model = session.model
        chosenAtoms.clear()
        projection.show(model, keep = sameCommand)
        tabs.selectedIndex = TAB_IDS.indexOf(viewer.selectedTab).coerceAtLeast(0)
        stateChanged()
        updateHeader()
    }

    private fun updateHeader() {
        val session = this.session ?: return
        header.text = viewer.header(session)
        header.foreground = if (viewer.outdated(session) != null) UIUtil.getInactiveTextColor() else null
        evaluator.updateCaption()
    }

    /** Projects this viewer's graphs over [type] too, or no longer over it, as its projection chooser does. */
    fun setProjected(type: AlloyType, projected: Boolean) = projection.setProjected(type, projected)

    /** Shows or hides the Evaluator panel, for this and the next results. */
    fun setEvaluatorVisible(visible: Boolean) {
        viewer.evaluatorVisible = visible
        evaluator.isVisible = visible
        splitter.revalidate()
        splitter.repaint()
    }

    /** Shows the selected state in every tab and in the trace path strip, after it changed. */
    fun stateChanged() {
        val model = session?.model ?: return
        strip.scrollPane.isVisible = model.hasStates
        if (model.hasStates) {
            strip.show(model.traceLength, model.loopState, model.state, model.successor())
            strip.reveal()
        }
        showText()
        showTables()
        redrawGraph()
        evaluator.refresh(session)
    }

    private fun showTables() {
        val model = session?.model ?: return
        val next = model.nextTable
        tables.show(
            if (next == null) listOf(null to tablePane(model.table))
            else listOf(model.caption to tablePane(model.table), model.nextCaption to tablePane(next)),
        )
    }

    /**
     * Selects [state], chosen in the trace path strip. Its instances, text and tables are prepared off the EDT the
     * first time; if another state is selected meanwhile, or another session shown, this one is not shown.
     */
    private fun goTo(state: Int) {
        val session = this.session ?: return
        val selection = ++selections
        if (session.model.isPrepared(state)) return select(session, state)
        ApplicationManager.getApplication().executeOnPooledThread {
            // A failure is the EDT's to report, as it does the work again
            runCatching { session.model.prepare(state) }
            ApplicationManager.getApplication().invokeLater({ if (selection == selections) select(session, state) }, project.disposed)
        }
    }

    private fun select(session: ViewerSession, state: Int) {
        if (this.session !== session) return
        session.model.goTo(state)
        stateChanged()
    }

    /** Rebuilds the atom selectors and the graphs, of one state or of a trace state and its successor. */
    private fun redrawGraph() {
        val model = session?.model ?: return
        // The model is shared with the other viewers of the file, which may have projected it otherwise
        model.project(projection.projections)
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
        // Only static top-level signatures can be projected (`VizState.canProject`), so both states have the atoms
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
     * visualizer, the popup gesture on the canvas around the graph shows the graph's zoom popup too.
     */
    private fun graphPane(theme: VizState, choice: AlloyProjection): JComponent {
        val graph = theme.getGraph(WindowManager.getInstance().getFrame(project), choice)
        val canvas = GraphCanvas().apply { add(graph) }
        if (graph is GraphViewer) canvas.addMouseListener(object : PopupHandler() {
            override fun invokePopup(comp: Component, x: Int, y: Int) = graph.alloyPopup(canvas, x, y)
        })
        return JBScrollPane(darkGraphs.layer(canvas))
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
            val session = this@AlloyInstancePanel.session
            e.presentation.isVisible = session != null
            e.presentation.isEnabled = viewer.isEnabled(file, kind)
            e.presentation.description = if (session != null && viewer.outdated(session) != null) AlloyInstanceViewer.RUN_AGAIN else kind.description
        }

        override fun actionPerformed(e: AnActionEvent) {
            // The new solution copies the theme, with this viewer's projection
            session?.model?.project(projection.projections)
            viewer.requestSolution(file, kind)
        }
    }

    private inner class EvaluatorToggle :
        ToggleAction(EVALUATOR, AlloyBundle.message("viewer.evaluator.toggle.description"), AllIcons.Debugger.EvaluateExpression), DumbAware {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun isSelected(e: AnActionEvent) = viewer.evaluatorVisible

        override fun setSelected(e: AnActionEvent, state: Boolean) = setEvaluatorVisible(state)
    }

    companion object {
        val EVALUATOR: String get() = AlloyBundle.message("viewer.evaluator")

        private const val EMPTY = "empty"
        private const val SESSION = "session"

        /** The ids of the tabs, in order, as [AlloyInstanceViewer.selectedTab] keeps them. */
        private val TAB_IDS = listOf(AlloyInstanceViewer.VISUALIZATION, AlloyInstanceViewer.TEXT, AlloyInstanceViewer.TABLE)
    }
}

/**
 * The toolbar line: the [controls], then the trace path [strip] beside them when it fits entirely, and otherwise
 * below them, as wide as the line, where it scrolls. The preferred height is that of the line's current width; when
 * a new width moves the strip, the line is laid out again for its new height.
 */
private class ToolbarLineLayout(private val controls: JComponent, private val strip: JScrollPane) : LayoutManager {
    private var beside: Boolean? = null

    private fun width(parent: Container) = parent.width - parent.insets.left - parent.insets.right

    /** Whether the strip goes beside the controls in [width]; before the first layout, there is no width yet. */
    private fun fitsBeside(width: Int) = width <= 0 || controls.preferredSize.width + JBUI.scale(GAP) + strip.preferredSize.width <= width

    /** The strip's height below the controls, with room for its scroll bar when the bar takes space and is needed. */
    private fun heightBelow(width: Int): Int {
        val bar = strip.horizontalScrollBar
        val scrolls = strip.preferredSize.width > width && bar.isOpaque
        return strip.preferredSize.height + if (scrolls) bar.preferredSize.height else 0
    }

    override fun preferredLayoutSize(parent: Container): Dimension {
        val insets = parent.insets
        val c = controls.preferredSize
        val size = when {
            !strip.isVisible -> c
            fitsBeside(width(parent)) -> Dimension(c.width + JBUI.scale(GAP) + strip.preferredSize.width, max(c.height, strip.preferredSize.height))
            else -> Dimension(c.width, c.height + heightBelow(width(parent)))
        }
        return Dimension(size.width + insets.left + insets.right, size.height + insets.top + insets.bottom)
    }

    override fun minimumLayoutSize(parent: Container) = Dimension(0, preferredLayoutSize(parent).height)

    override fun layoutContainer(parent: Container) {
        val insets = parent.insets
        val width = width(parent)
        val c = controls.preferredSize
        if (!strip.isVisible) return controls.setBounds(insets.left, insets.top, c.width, c.height)
        val s = strip.preferredSize
        val fits = fitsBeside(width)
        if (fits) {
            val height = max(c.height, s.height)
            controls.setBounds(insets.left, insets.top + (height - c.height) / 2, c.width, c.height)
            strip.setBounds(insets.left + c.width + JBUI.scale(GAP), insets.top + (height - s.height) / 2, s.width, s.height)
        } else {
            controls.setBounds(insets.left, insets.top, c.width, c.height)
            strip.setBounds(insets.left, insets.top + c.height, width, heightBelow(width))
        }
        if (beside != null && beside != fits) SwingUtilities.invokeLater(parent::revalidate)
        beside = fits
    }

    override fun addLayoutComponent(name: String?, comp: Component) {}

    override fun removeLayoutComponent(comp: Component) {}

    private companion object {
        const val GAP = 8
    }
}
