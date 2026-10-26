package com.serranofp.alloy.viewer

import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.impl.ActionToolbarImpl
import com.intellij.openapi.util.SystemInfo
import com.intellij.testFramework.TestActionEvent
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ui.UIUtil
import com.serranofp.alloy.run.Outdated
import com.serranofp.alloy.run.waitUntil
import com.serranofp.alloy.viewer.AlloyEvaluatorPanel.ValueNode
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.KeyStroke
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

class AlloyEvaluatorPanelTest : AlloyViewerTestCase() {
    private val stored get() = AlloyEvaluatorState.getInstance(project)

    override fun setUp() {
        super.setUp()
        viewer.evaluatorVisible = true
        myFixture.configureByText("m.als", SHOW_FILE)
        stored.setWatches(myFixture.file.virtualFile, emptyList())
    }

    private fun AlloyInstancePanel.evaluator() = UIUtil.findComponentOfType(this, AlloyEvaluatorPanel::class.java)!!

    /** Waits for the evaluations to be delivered. */
    private fun AlloyEvaluatorPanel.settle(): AlloyEvaluatorPanel {
        waitUntil("the evaluations did not end") { !isEvaluating() }
        return this
    }

    private fun AlloyEvaluatorPanel.rows(): List<ValueNode> {
        val root = tree.model.root as DefaultMutableTreeNode
        return (0 until root.childCount).map { root.getChildAt(it) as ValueNode }
    }

    private fun AlloyEvaluatorPanel.watchRows() = rows().filter { it.watch }

    private fun AlloyEvaluatorPanel.renderer(node: Any): ColoredTreeCellRenderer =
        tree.cellRenderer.getTreeCellRendererComponent(tree, node, false, false, false, 0, false) as ColoredTreeCellRenderer

    private fun AlloyEvaluatorPanel.text(node: Any): String = renderer(node).getCharSequence(false).toString()

    private fun AlloyEvaluatorPanel.attributes(node: Any): List<SimpleTextAttributes> {
        val iterator = renderer(node).iterator()
        return buildList { while (iterator.hasNext()) { iterator.next(); add(iterator.textAttributes) } }
    }

    private fun AlloyEvaluatorPanel.watch(expression: String) = watchRows().single { it.expression == expression }

    private fun toggle(panel: AlloyInstancePanel): ToggleAction {
        val group = UIUtil.findComponentOfType(panel, ActionToolbarImpl::class.java)!!.actionGroup as DefaultActionGroup
        return group.childActionsOrStubs.filterIsInstance<ToggleAction>().single { it.templateText == AlloyInstancePanel.EVALUATOR }
    }

    fun testToggleShowsAndHidesThePanelForTheNextSession() {
        viewer.evaluatorVisible = false
        val panel = panel()
        open(RING)
        val evaluator = panel.evaluator()
        assertFalse(evaluator.isVisible)
        toggle(panel).setSelected(TestActionEvent.createTestEvent(), true)
        assertTrue(evaluator.isVisible)
        assertTrue(viewer.evaluatorVisible)
        // A new tool window, as in a new IDE session
        assertTrue(panel().evaluator().isVisible)
        toggle(panel).setSelected(TestActionEvent.createTestEvent(), false)
        assertFalse(evaluator.isVisible)
        assertFalse(panel().evaluator().isVisible)
    }

    /** Wraps the runner's evaluation, to count the evaluations it starts. */
    private fun AlloyEvaluatorPanel.counted(): () -> Int {
        val count = AtomicInteger()
        val evaluate = runner.evaluate
        runner.evaluate = { model, text, state -> count.incrementAndGet(); evaluate(model, text, state) }
        return count::get
    }

    fun testHiddenPanelEvaluatesOnlyWhenShown() {
        viewer.evaluatorVisible = false
        stored.setWatches(myFixture.file.virtualFile, listOf("Cur"))
        val panel = panel()
        val evaluator = panel.evaluator()
        val evaluations = evaluator.counted()
        open(LASSO)
        assertFalse(evaluator.isVisible)
        // As a click on state 1 in the trace path strip does
        viewer.session(myFixture.file.virtualFile)!!.model.goTo(1)
        panel.stateChanged()
        UIUtil.dispatchAllInvocationEvents()
        assertEquals(0, evaluations())
        assertNull(evaluator.watch("Cur").result)

        panel.setEvaluatorVisible(true)
        assertEquals(EvaluationResult.Tuples(listOf("V2$0")), evaluator.settle().watch("Cur").result)
        assertEquals(1, evaluations())
    }

    /** Such as a stack overflow: the row shows the failure, rather than evaluating forever. */
    fun testAThrowingEvaluationShowsAnError() {
        val panel = panel()
        open(RING)
        val evaluator = panel.evaluator()
        evaluator.runner.evaluate = { _, _, _ -> throw StackOverflowError() }
        evaluator.evaluate("succ")
        val row = evaluator.settle().rows().single()
        val error = row.result as EvaluationResult.Error
        assertTrue(error.message, error.message.contains("StackOverflowError"))
        assertTrue(SimpleTextAttributes.ERROR_ATTRIBUTES in evaluator.attributes(row))
    }

    fun testTheFieldsKeys() {
        val evaluator = panel().also { open(RING) }.evaluator()
        val field = evaluator.field
        field.text = "succ"
        assertTrue(field.press(KeyEvent.VK_ENTER))
        assertEquals("succ", evaluator.settle().rows().single { !it.watch }.expression)
        field.text = "#Node"
        val modifier = if (SystemInfo.isMac) InputEvent.META_DOWN_MASK else InputEvent.CTRL_DOWN_MASK
        assertTrue(field.press(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, modifier or InputEvent.SHIFT_DOWN_MASK)))
        assertEquals(listOf("#Node"), evaluator.watchExpressions())
        assertTrue(field.press(KeyEvent.VK_UP))
        assertEquals("succ", field.text)
        assertTrue(field.press(KeyEvent.VK_DOWN))
        assertEquals("#Node", field.text)
        evaluator.settle()
    }

    /** While the completion popup shows, Enter and the arrows are its own: they accept or move the selection. */
    fun testTheFieldsKeysAreThePopupsWhileItShows() {
        val evaluator = panel().also { open(RING) }.evaluator()
        val field = evaluator.field
        field.addNotify()
        val editor = field.getEditor(true)!!
        // Before the popup: an edit that is not typing closes it
        field.text = "su"
        val lookup = LookupManager.getInstance(project).showLookup(editor, LookupElementBuilder.create("succ"))!!
        try {
            assertSame(lookup, LookupManager.getActiveLookup(editor))
            for (key in listOf(KeyEvent.VK_ENTER, KeyEvent.VK_UP, KeyEvent.VK_DOWN)) assertFalse(field.press(key))
            assertEmpty(evaluator.rows())
        } finally {
            lookup.hideLookup(true)
            field.removeNotify()
        }
        assertTrue(field.press(KeyEvent.VK_ENTER))
        evaluator.settle()
    }

    fun testEvaluatingARelation() {
        val panel = panel()
        open(RING)
        val evaluator = panel.evaluator()
        evaluator.evaluate("succ")
        evaluator.settle()
        val row = evaluator.rows().single()
        assertFalse(row.watch)
        assertTrue(evaluator.text(row), evaluator.text(row).startsWith("succ = {2 tuples}"))
        val tuples = (0 until row.childCount).map { (row.getChildAt(it) as DefaultMutableTreeNode).userObject }
        assertEquals(listOf("Node$0->Node$1", "Node$1->Node$0"), tuples)
        evaluator.tree.expandPath(TreePath(arrayOf(evaluator.tree.model.root, row)))
        assertEquals("Node$0->Node$1", evaluator.text(row.getChildAt(0)))
    }

    fun testEmptyFieldEvaluatesNothing() {
        val evaluator = panel().also { open(RING) }.evaluator()
        evaluator.evaluate("  ")
        assertEmpty(evaluator.rows())
    }

    fun testHistoryOffersTheLatestFirst() {
        val evaluator = panel().also { open(RING) }.evaluator()
        evaluator.evaluate("succ")
        evaluator.evaluate("#Node")
        assertEquals(listOf("#Node", "succ"), evaluator.history())
        evaluator.historyUp()
        assertEquals("#Node", evaluator.field.text)
        evaluator.historyUp()
        assertEquals("succ", evaluator.field.text)
        evaluator.historyUp()
        assertEquals("succ", evaluator.field.text)
        evaluator.historyDown()
        assertEquals("#Node", evaluator.field.text)
        evaluator.historyDown()
        assertEquals("", evaluator.field.text)
        // Evaluating again moves an expression to the front
        evaluator.evaluate("succ")
        assertEquals(listOf("succ", "#Node"), evaluator.history())
    }

    fun testHistoryKeepsWhatWasBeingTyped() {
        val evaluator = panel().also { open(RING) }.evaluator()
        evaluator.evaluate("succ")
        evaluator.field.text = "#No"
        evaluator.historyUp()
        assertEquals("succ", evaluator.field.text)
        evaluator.historyDown()
        assertEquals("#No", evaluator.field.text)
    }

    fun testHistoryKeepsFiftyEntries() {
        val evaluator = panel().also { open(RING) }.evaluator()
        for (i in 1..60) evaluator.evaluate("#Node + $i")
        assertEquals(AlloyEvaluatorPanel.HISTORY_SIZE, evaluator.history().size)
        assertEquals("#Node + 60", evaluator.history().first())
        evaluator.settle()
    }

    fun testValueRendering() {
        assertEquals("{}", AlloyEvaluatorPanel.rendered(EvaluationResult.Tuples(emptyList())))
        assertEquals("{1 tuple}", AlloyEvaluatorPanel.rendered(EvaluationResult.Tuples(listOf("A$0"))))
        assertEquals("true", AlloyEvaluatorPanel.rendered(EvaluationResult.Bool(true)))
        assertEquals("3 (OF)", AlloyEvaluatorPanel.rendered(EvaluationResult.IntValue("3 (OF)")))
    }

    fun testErrorRow() {
        val panel = panel()
        open(RING)
        val evaluator = panel.evaluator()
        val session = viewer.session(myFixture.file.virtualFile)
        evaluator.evaluate("Undefined")
        val row = evaluator.settle().rows().single()
        assertInstanceOf(row.result, EvaluationResult.Error::class.java)
        assertTrue(evaluator.text(row), evaluator.text(row).contains("cannot be found"))
        assertTrue(SimpleTextAttributes.ERROR_ATTRIBUTES in evaluator.attributes(row))
        assertSame(session, viewer.session(myFixture.file.virtualFile))
        assertTrue(evaluator.isVisible)
    }

    fun testCaptionNamesTheStateAndOutdatedResult() {
        val panel = panel()
        open(LASSO)
        val evaluator = panel.evaluator()
        assertEquals("Evaluating at state 0", evaluator.caption.text)
        viewer.session(myFixture.file.virtualFile)!!.model.goTo(2)
        panel.stateChanged()
        assertEquals("Evaluating at state 2", evaluator.caption.text)
        myFixture.type("x")
        assertEquals("Evaluating at state 2" + AlloyEvaluatorPanel.OUTDATED, evaluator.caption.text)
    }

    /** A click in the trace path strip asks for the caption without read access, as this thread has none. */
    fun testOutdatedNeedsNoReadAccess() {
        panel()
        open(LASSO)
        val session = viewer.session(myFixture.file.virtualFile)!!
        val outdated = ApplicationManager.getApplication().executeOnPooledThread<Outdated?> { viewer.outdated(session) }
        assertNull(outdated.get(30, TimeUnit.SECONDS))
        myFixture.type("x")
        val edited = ApplicationManager.getApplication().executeOnPooledThread<Outdated?> { viewer.outdated(session) }
        assertEquals(Outdated.FileChanged, edited.get(30, TimeUnit.SECONDS))
    }

    fun testOutdatedResultIsEvaluatedAsAnalyzed() {
        val panel = panel()
        open(RING)
        myFixture.type("x")
        val evaluator = panel.evaluator()
        evaluator.evaluate("succ")
        assertEquals(2, (evaluator.settle().rows().single().result as EvaluationResult.Tuples).tuples.size)
    }

    fun testWatchesAreEditedAndStored() {
        val panel = panel()
        open(RING)
        val evaluator = panel.evaluator()
        val file = myFixture.file.virtualFile
        evaluator.field.text = "Elected"
        evaluator.addWatch(evaluator.field.text)
        evaluator.addWatch("b")
        evaluator.addWatch("c")
        assertEquals(listOf("Elected", "b", "c"), stored.watches(file))
        evaluator.editWatch(0, "#Node")
        assertEquals(listOf("#Node", "b", "c"), evaluator.watchExpressions())
        assertEquals(EvaluationResult.IntValue("2"), evaluator.settle().watch("#Node").result)
        evaluator.moveWatch(2, -1)
        evaluator.removeWatch(0)
        assertEquals(listOf("c", "b"), evaluator.watchExpressions())
        assertEquals(listOf("c", "b"), evaluator.watchRows().map { it.expression })
        assertEquals(listOf("c", "b"), stored.watches(file))
    }

    fun testEditingAWatchToNothingRemovesItAndMovingPastTheEndsDoesNothing() {
        val evaluator = panel().also { open(RING) }.evaluator()
        evaluator.addWatch("a")
        evaluator.addWatch("b")
        evaluator.moveWatch(0, -1)
        evaluator.moveWatch(1, 1)
        assertEquals(listOf("a", "b"), evaluator.watchExpressions())
        evaluator.editWatch(0, "  ")
        assertEquals(listOf("b"), evaluator.watchExpressions())
        assertEquals(listOf("b"), stored.watches(myFixture.file.virtualFile))
        evaluator.settle()
    }

    fun testWatchesFollowTheSelectedState() {
        val panel = panel()
        open(LASSO)
        val evaluator = panel.evaluator()
        evaluator.addWatch("Cur")
        evaluator.addWatch("V1")
        evaluator.settle()
        assertEquals(EvaluationResult.Tuples(listOf("V1$0")), evaluator.watch("Cur").result)
        // As a click on state 1 in the trace path strip does
        viewer.session(myFixture.file.virtualFile)!!.model.goTo(1)
        panel.stateChanged()
        evaluator.settle()
        val cur = evaluator.watch("Cur")
        assertEquals(EvaluationResult.Tuples(listOf("V2$0")), cur.result)
        assertTrue(cur.changed)
        assertTrue(AlloyEvaluatorPanel.CHANGED_VALUE_ATTRIBUTES in evaluator.attributes(cur))
        assertFalse(evaluator.watch("V1").changed)
        assertFalse(AlloyEvaluatorPanel.CHANGED_VALUE_ATTRIBUTES in evaluator.attributes(evaluator.watch("V1")))
    }

    fun testNewForkEvaluatesEveryWatch() {
        val panel = panel()
        open(TRACES)
        val evaluator = panel.evaluator()
        evaluator.addWatch("X")
        evaluator.addWatch("#A")
        viewer.session(myFixture.file.virtualFile)!!.model.goTo(1)
        panel.stateChanged()
        evaluator.settle()
        val before = viewer.session(myFixture.file.virtualFile)
        val evaluated = mutableListOf<Pair<AlloySolutionModel, String>>()
        val evaluate = evaluator.runner.evaluate
        evaluator.runner.evaluate = { model, text, state -> synchronized(evaluated) { evaluated += model to text }; evaluate(model, text, state) }
        assertTrue(viewer.requestSolution(myFixture.file.virtualFile, SolutionKind.Fork))
        waitUntil("the new solution did not arrive") { !viewer.enumerating }
        UIUtil.dispatchAllInvocationEvents()
        evaluator.settle()
        assertNotSame(before, viewer.session(myFixture.file.virtualFile))
        val model = viewer.session(myFixture.file.virtualFile)!!.model
        assertEquals(listOf("X", "#A"), synchronized(evaluated) { evaluated.filter { it.first === model }.map { it.second } })
        for (watch in evaluator.watchRows()) assertEquals(model.evaluate(watch.expression), watch.result)
    }

    fun testEachFileShowsItsWatches() {
        val ring = myFixture.file.virtualFile
        val graph = myFixture.addFileToProject("graph.als", "pred show {}\nrun show for 3\n").virtualFile
        stored.setWatches(graph, listOf("#Node"))
        val ringPanel = panel(ring)
        val graphPanel = panel(graph)
        open(RING)
        val ringEvaluator = ringPanel.evaluator()
        ringEvaluator.addWatch("succ")
        open(RING, graph)
        val graphEvaluator = graphPanel.evaluator()
        assertEquals(listOf("#Node"), graphEvaluator.watchExpressions())
        assertEquals(EvaluationResult.IntValue("2"), graphEvaluator.settle().watch("#Node").result)
        assertEquals(listOf("succ"), ringEvaluator.watchExpressions())
    }

    fun testEachFileEvaluatesInItsOwnResult() {
        val graph = myFixture.addFileToProject("graph.als", SHOW_FILE).virtualFile
        val queue = myFixture.addFileToProject("queue.als", SHOW_FILE).virtualFile
        val graphEvaluator = panel(graph).evaluator()
        val queueEvaluator = panel(queue).evaluator()
        open(RING, graph)
        open("one sig A {}\nrun {} for 1", queue)
        val graphValue = viewer.session(graph)!!.model.evaluate("#univ")
        val queueValue = viewer.session(queue)!!.model.evaluate("#univ")
        assertFalse(graphValue == queueValue)
        graphEvaluator.evaluate("#univ")
        assertEquals(graphValue, graphEvaluator.settle().rows().single().result)
        queueEvaluator.evaluate("#univ")
        assertEquals(queueValue, queueEvaluator.settle().rows().single().result)
        // The other file's panel is unchanged
        assertEquals(graphValue, graphEvaluator.rows().single().result)
    }

    fun testWatchOfARemovedFieldShowsItsError() {
        val panel = panel()
        open(RING)
        val evaluator = panel.evaluator()
        evaluator.addWatch("succ")
        evaluator.settle()
        open("sig Node {}\nrun { some Node } for 2")
        val watch = evaluator.settle().watch("succ")
        val result = watch.result as EvaluationResult.Error
        assertTrue(result.message, result.message.contains("cannot be found"))
        assertEquals(listOf("succ"), stored.watches(myFixture.file.virtualFile))
    }

    private companion object {
        /** `succ = {Node$0->Node$1, Node$1->Node$0}`. */
        const val RING = "sig Node { succ: one Node }\nfact { all n: Node | n.succ != n }\nrun { #Node = 2 } for 2"
    }
}
