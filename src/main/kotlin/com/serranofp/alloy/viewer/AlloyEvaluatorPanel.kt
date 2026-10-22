package com.serranofp.alloy.viewer

import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.serranofp.alloy.AlloyBundle
import com.serranofp.alloy.AlloyFileType
import org.jetbrains.annotations.TestOnly
import java.awt.BorderLayout
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.KeyStroke
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * The Evaluator panel of the tool window, as the debugger's Variables view with inline watches: a caption naming
 * the state, the expression field, and a tree whose first root is the last expression evaluated from the field,
 * followed by one root per watch of the model file. [refresh] evaluates them all again for a session; a watch
 * whose value differs from its previous evaluation, in the same file, is highlighted as changed. The field is an
 * Alloy fragment (see [ALLOY_EVALUATOR_FRAGMENT]) with a history of the evaluated expressions, which Up and Down
 * go through, as in the debugger's evaluate field, which keeps what was being typed as the entry after the latest.
 * A watch is added or edited in such a field too, with the same completion.
 */
class AlloyEvaluatorPanel(private val project: Project, parent: Disposable) : JPanel(BorderLayout()) {
    private val viewer = AlloyInstanceViewer.getInstance(project)
    private val stored = AlloyEvaluatorState.getInstance(project)

    @get:TestOnly
    internal val runner = AlloyEvaluatorRunner(parent)

    @get:TestOnly
    internal val caption = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }

    @get:TestOnly
    internal val field = EditorTextField(alloyEvaluatorDocument(project) { session?.model }, project, AlloyFileType, false, true)

    /** The evaluated expressions, the latest first. */
    private val history = ArrayDeque<String>()

    /** The entry of [history] shown in the field by Up and Down; -1 for none. */
    private var historyIndex = -1

    /** What the field had when Up first left it, shown again after the latest entry. */
    private var draft = ""

    private val roots = DefaultMutableTreeNode()
    private val treeModel = DefaultTreeModel(roots)

    @get:TestOnly
    internal val tree = Tree(treeModel).apply {
        isRootVisible = false
        showsRootHandles = true
        cellRenderer = Renderer()
    }

    /** Read by the field's completion, off the EDT. */
    @Volatile
    private var session: ViewerSession? = null

    /** The file whose watches are shown. */
    private var file: VirtualFile? = null

    /** The last expression evaluated from the field. */
    private var evaluation: ValueNode? = null

    private val watches = mutableListOf<ValueNode>()

    /** The compared text of each watch's last value, in [file]. */
    private val previous = mutableMapOf<String, String>()

    /** One expression and its value: null while it is being evaluated. A set's children are its tuples. */
    internal class ValueNode(val expression: String, val watch: Boolean) : DefaultMutableTreeNode() {
        var result: EvaluationResult? = null
        var changed = false
    }

    init {
        val modifier = if (SystemInfo.isMac) InputEvent.META_DOWN_MASK else InputEvent.CTRL_DOWN_MASK
        fieldAction(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)) { evaluate(field.text) }
        fieldAction(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, modifier or InputEvent.SHIFT_DOWN_MASK)) { addWatch(field.text) }
        fieldAction(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0)) { historyUp() }
        fieldAction(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0)) { historyDown() }
        val addButton = JButton(AlloyBundle.message("viewer.evaluator.add.to.watches"), AllIcons.Debugger.AddToWatch).apply { addActionListener { addWatch(field.text) } }
        val fieldLine = JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
            add(field, BorderLayout.CENTER)
            add(addButton, BorderLayout.EAST)
        }
        val north = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(4, 8)
            add(caption.apply { alignmentX = LEFT_ALIGNMENT })
            add(fieldLine.apply { alignmentX = LEFT_ALIGNMENT })
        }
        val decorated = ToolbarDecorator.createDecorator(tree)
            .setAddAction { promptWatch(AlloyBundle.message("viewer.evaluator.add.watch"), "")?.let(::addWatch) }
            .setAddActionName(AlloyBundle.message("viewer.evaluator.add.watch"))
            .setRemoveAction { selectedWatch()?.let(::removeWatch) }
            .setRemoveActionUpdater { selectedWatch() != null }
            .setEditAction { editSelectedWatch() }
            .setEditActionUpdater { selectedWatch() != null }
            .setMoveUpAction { selectedWatch()?.let { moveWatch(it, -1) } }
            .setMoveUpActionUpdater { selectedWatch()?.let { it > 0 } == true }
            .setMoveDownAction { selectedWatch()?.let { moveWatch(it, 1) } }
            .setMoveDownActionUpdater { selectedWatch()?.let { it < watches.size - 1 } == true }
            .createPanel()
        DumbAwareAction.create { editSelectedWatch() }.registerCustomShortcutSet(CustomShortcutSet(KeyEvent.VK_F2), tree)
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                if (selectedWatch() == null) return false
                editSelectedWatch()
                return true
            }
        }.installOn(tree)
        add(north, BorderLayout.NORTH)
        add(decorated, BorderLayout.CENTER)
    }

    /** Runs [perform] for [key] in the field, unless the completion popup is showing, which then gets the key. */
    private fun fieldAction(key: KeyStroke, perform: () -> Unit) {
        object : DumbAwareAction() {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = field.editor?.let { LookupManager.getActiveLookup(it) } == null
            }

            override fun actionPerformed(e: AnActionEvent) = perform()
        }.registerCustomShortcutSet(CustomShortcutSet(key), field)
    }

    /** The evaluated expressions, the latest first. */
    @TestOnly
    internal fun history(): List<String> = history.toList()

    /** Shows the previous expression of the history in the field, keeping what was typed when it leaves it. */
    fun historyUp() {
        if (historyIndex + 1 >= history.size) return
        if (historyIndex < 0) draft = field.text
        historyIndex++
        field.text = history[historyIndex]
    }

    /** Shows the next expression of the history in the field, and after the latest one what was typed before. */
    fun historyDown() {
        if (historyIndex < 0) return
        historyIndex--
        field.text = if (historyIndex < 0) draft else history[historyIndex]
    }

    /** Evaluates the expression of the field and every watch again, in [session] at its selected state. */
    fun refresh(session: ViewerSession?) {
        this.session = session
        updateCaption()
        if (session == null) return
        val file = session.origin.file
        if (file != this.file) {
            this.file = file
            previous.clear()
            watches.clear()
            stored.watches(file).mapTo(watches) { ValueNode(it, watch = true) }
            showRoots()
        }
        if (!isVisible) return
        runner.invalidate()
        (listOfNotNull(evaluation) + watches).forEach(::submit)
    }

    /** "Evaluating at state i", and whether the result is outdated, as the header says. */
    fun updateCaption() {
        val session = session ?: return
        val outdated = if (viewer.outdated(session) != null) OUTDATED else ""
        caption.text = AlloyBundle.message("viewer.evaluator.caption", session.model.state.toString()) + outdated
    }

    override fun setVisible(visible: Boolean) {
        val shown = !isVisible && visible
        super.setVisible(visible)
        if (shown) refresh(session)
    }

    /** Evaluates [text] as the field's expression, superseding the previous one; an empty text evaluates nothing. */
    fun evaluate(text: String) {
        if (text.isBlank()) return
        history.remove(text.trim())
        history.addFirst(text.trim())
        while (history.size > HISTORY_SIZE) history.removeLast()
        historyIndex = -1
        draft = ""
        evaluation = ValueNode(text.trim(), watch = false)
        showRoots()
        // Superseding the requests still running: the watches still waiting are submitted again
        runner.invalidate()
        (listOfNotNull(evaluation) + watches.filter { it.result == null }).forEach(::submit)
    }

    fun addWatch(text: String) {
        if (text.isBlank()) return
        val node = ValueNode(text.trim(), watch = true)
        watches += node
        watchesChanged()
        submit(node)
    }

    fun editWatch(index: Int, text: String) {
        if (text.isBlank()) return removeWatch(index)
        val node = ValueNode(text.trim(), watch = true)
        watches[index] = node
        watchesChanged()
        submit(node)
    }

    fun removeWatch(index: Int) {
        watches.removeAt(index)
        watchesChanged()
    }

    /** Moves the watch at [index] by [delta] places, keeping it selected. */
    fun moveWatch(index: Int, delta: Int) {
        val target = index + delta
        if (target !in watches.indices) return
        watches.add(target, watches.removeAt(index))
        watchesChanged()
        tree.selectionPath = TreePath(arrayOf(roots, watches[target]))
    }

    @TestOnly
    internal fun watchExpressions(): List<String> = watches.map { it.expression }

    /** Whether evaluations are still running. */
    @TestOnly
    internal fun isEvaluating(): Boolean = (listOfNotNull(evaluation) + watches).any { it.result == null }

    private fun watchesChanged() {
        file?.let { stored.setWatches(it, watches.map(ValueNode::expression)) }
        showRoots()
    }

    private fun showRoots() {
        val selected = tree.selectionPath?.lastPathComponent
        roots.removeAllChildren()
        (listOfNotNull(evaluation) + watches).forEach(roots::add)
        treeModel.nodeStructureChanged(roots)
        if (selected is ValueNode && selected.parent === roots) tree.selectionPath = TreePath(arrayOf(roots, selected))
    }

    private fun submit(node: ValueNode) {
        val model = session?.model ?: return
        val expanded = tree.isExpanded(TreePath(arrayOf(roots, node)))
        node.result = null
        node.removeAllChildren()
        if (node.parent === roots) treeModel.nodeStructureChanged(node)
        runner.submit(model, node.expression) { result -> deliver(node, result, expanded) }
    }

    private fun deliver(node: ValueNode, result: EvaluationResult, expanded: Boolean) {
        node.result = result
        if (result is EvaluationResult.Tuples) result.tuples.forEach { node.add(DefaultMutableTreeNode(it)) }
        if (node.watch) {
            val compared = compared(result)
            node.changed = previous[node.expression]?.let { it != compared } ?: false
            previous[node.expression] = compared
        }
        if (node.parent !== roots) return
        treeModel.nodeStructureChanged(node)
        if (expanded) tree.expandPath(TreePath(arrayOf(roots, node)))
    }

    private fun selectedWatch(): Int? {
        val node = tree.selectionPath?.path?.getOrNull(1) as? ValueNode ?: return null
        return watches.indexOf(node).takeIf { it >= 0 }
    }

    private fun editSelectedWatch() {
        val index = selectedWatch() ?: return
        promptWatch(AlloyBundle.message("viewer.evaluator.edit.watch"), watches[index].expression)?.let { editWatch(index, it) }
    }

    private fun promptWatch(title: String, initial: String): String? = WatchDialog(title, initial).takeIf { it.showAndGet() }?.text

    /** Asks for a watch's expression in an evaluator fragment, which completes the names of the shown model. */
    private inner class WatchDialog(title: String, initial: String) : DialogWrapper(project) {
        private val expression = EditorTextField(alloyEvaluatorDocument(project) { session?.model }, project, AlloyFileType, false, true).apply {
            text = initial
            setPreferredWidth(JBUI.scale(PROMPT_WIDTH))
        }

        val text: String get() = expression.text

        init {
            this.title = title
            init()
        }

        override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            add(JBLabel(AlloyBundle.message("viewer.evaluator.expression")), BorderLayout.NORTH)
            add(expression, BorderLayout.CENTER)
        }

        override fun getPreferredFocusedComponent(): JComponent = expression
    }

    private class Renderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            if (value !is ValueNode) {
                append((value as? DefaultMutableTreeNode)?.userObject?.toString().orEmpty())
                return
            }
            icon = if (value.watch) AllIcons.Debugger.Watch else AllIcons.Debugger.EvaluateExpression
            val result = value.result
            if (result is EvaluationResult.Error) {
                append("${value.expression}: ")
                append(result.message, SimpleTextAttributes.ERROR_ATTRIBUTES)
            } else {
                append("${value.expression} = ")
                if (result == null) append(EVALUATING, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                else append(rendered(result), if (value.changed) CHANGED_VALUE_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
            }
            if (!value.watch) append(AlloyBundle.message("viewer.evaluator.evaluated.suffix"), SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }

    companion object {
        val EVALUATING: String get() = AlloyBundle.message("viewer.evaluator.evaluating")
        val OUTDATED: String get() = AlloyBundle.message("viewer.evaluator.outdated.suffix")
        const val HISTORY_SIZE = 50
        private const val PROMPT_WIDTH = 400

        /** The debugger's "changed value" attributes (`XDebuggerUIConstants.CHANGED_VALUE_ATTRIBUTES`). */
        val CHANGED_VALUE_ATTRIBUTES = SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor.namedColor("Debugger.Variables.changedValueForeground", JBColor.blue))

        /** `{2 tuples}`, `{}`, `true` or `3`, as a row shows a value. */
        fun rendered(result: EvaluationResult): String = when (result) {
            is EvaluationResult.Tuples -> when (result.tuples.size) {
                0 -> "{}"
                else -> AlloyBundle.message("viewer.evaluator.tuples", result.tuples.size)
            }
            is EvaluationResult.Bool -> result.value.toString()
            is EvaluationResult.IntValue -> result.value
            is EvaluationResult.Error -> result.message
        }

        /** What tells two values apart: a set by its tuples, not only their number. */
        private fun compared(result: EvaluationResult): String =
            if (result is EvaluationResult.Tuples) result.tuples.joinToString(", ", "{", "}") else rendered(result)
    }
}
