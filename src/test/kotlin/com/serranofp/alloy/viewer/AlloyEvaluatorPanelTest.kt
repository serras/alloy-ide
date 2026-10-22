package com.serranofp.alloy.viewer

import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.impl.ActionToolbarImpl
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ui.UIUtil
import com.serranofp.alloy.run.AlloyCommandRef
import com.serranofp.alloy.run.AlloyCommandResults
import com.serranofp.alloy.run.AlloyRunService
import com.serranofp.alloy.run.CommandResult
import com.serranofp.alloy.run.CommandState
import com.serranofp.alloy.run.Outdated
import com.serranofp.alloy.run.waitUntil
import com.serranofp.alloy.settings.AlloySettings
import com.serranofp.alloy.viewer.AlloyEvaluatorPanel.ValueNode
import java.util.concurrent.TimeUnit
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

class AlloyEvaluatorPanelTest : BasePlatformTestCase() {
    private val viewer get() = AlloyInstanceViewer.getInstance(project)
    private val show = AlloyCommandRef("show", 0)
    private val stored get() = AlloyEvaluatorState.getInstance(project)

    override fun setUp() {
        super.setUp()
        AlloyCommandResults.getInstance(project).clear()
        viewer.clear()
        viewer.evaluatorVisible = true
        myFixture.configureByText("m.als", "pred show {}\nrun show for 3\n")
        stored.setWatches(myFixture.file.virtualFile, emptyList())
    }

    override fun tearDown() {
        try {
            viewer.stopEnumeration()
            AlloyRunService.getInstance(project).activeExecution?.terminate()
            viewer.clear()
            viewer.evaluatorVisible = false
            stored.loadState(AlloyEvaluatorWatches())
        } finally {
            super.tearDown()
        }
    }

    private fun open(model: String, file: VirtualFile = myFixture.file.virtualFile) {
        val results = AlloyCommandResults.getInstance(project)
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        val result = CommandResult(
            CommandState.InstanceFound, "found", link = link(model),
            documentStamp = results.stamp(document), settingsGeneration = AlloySettings.getInstance().generation,
        )
        viewer.open(file, show, result)
        UIUtil.dispatchAllInvocationEvents()
    }

    private fun panel() = AlloyInstancePanel(project, testRootDisposable)

    private fun AlloyInstancePanel.evaluator() = UIUtil.findComponentOfType(this, AlloyEvaluatorPanel::class.java)!!

    /** Waits for the evaluations to be delivered. */
    private fun AlloyEvaluatorPanel.settle(): AlloyEvaluatorPanel {
        val deadline = System.currentTimeMillis() + 30_000
        do {
            assertTrue("the evaluations did not end", System.currentTimeMillis() < deadline)
            Thread.sleep(5)
            UIUtil.dispatchAllInvocationEvents()
        } while (isEvaluating())
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

    fun testHiddenPanelEvaluatesWhenShown() {
        viewer.evaluatorVisible = false
        val panel = panel()
        open(RING)
        val evaluator = panel.evaluator()
        evaluator.addWatch("#Node")
        panel.setEvaluatorVisible(true)
        assertEquals(EvaluationResult.IntValue("2"), evaluator.settle().watch("#Node").result)
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
        val session = viewer.session
        evaluator.evaluate("Undefined")
        val row = evaluator.settle().rows().single()
        assertInstanceOf(row.result, EvaluationResult.Error::class.java)
        assertTrue(evaluator.text(row), evaluator.text(row).contains("cannot be found"))
        assertTrue(SimpleTextAttributes.ERROR_ATTRIBUTES in evaluator.attributes(row))
        assertSame(session, viewer.session)
        assertTrue(evaluator.isVisible)
    }

    fun testCaptionNamesTheStateAndOutdatedResult() {
        val panel = panel()
        open(LASSO)
        val evaluator = panel.evaluator()
        assertEquals("Evaluating at state 0", evaluator.caption.text)
        viewer.session!!.model.goTo(2)
        panel.stateChanged()
        assertEquals("Evaluating at state 2", evaluator.caption.text)
        myFixture.type("x")
        assertEquals("Evaluating at state 2" + AlloyEvaluatorPanel.OUTDATED, evaluator.caption.text)
    }

    /** A click in the trace path strip asks for the caption without read access, as this thread has none. */
    fun testOutdatedNeedsNoReadAccess() {
        panel()
        open(LASSO)
        val session = viewer.session!!
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

    fun testWatchesFollowTheSelectedState() {
        val panel = panel()
        open(LASSO)
        val evaluator = panel.evaluator()
        evaluator.addWatch("Cur")
        evaluator.addWatch("V1")
        evaluator.settle()
        assertEquals(EvaluationResult.Tuples(listOf("V1$0")), evaluator.watch("Cur").result)
        // As a click on state 1 in the trace path strip does
        viewer.session!!.model.goTo(1)
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
        viewer.session!!.model.goTo(1)
        panel.stateChanged()
        evaluator.settle()
        val before = viewer.session
        val evaluated = mutableListOf<Pair<AlloySolutionModel, String>>()
        val evaluate = evaluator.runner.evaluate
        evaluator.runner.evaluate = { model, text, state -> synchronized(evaluated) { evaluated += model to text }; evaluate(model, text, state) }
        assertTrue(viewer.requestSolution(SolutionKind.Fork))
        waitUntil("the new solution did not arrive") { !viewer.enumerating }
        UIUtil.dispatchAllInvocationEvents()
        evaluator.settle()
        assertNotSame(before, viewer.session)
        val model = viewer.session!!.model
        assertEquals(listOf("X", "#A"), synchronized(evaluated) { evaluated.filter { it.first === model }.map { it.second } })
        for (watch in evaluator.watchRows()) assertEquals(model.evaluate(watch.expression), watch.result)
    }

    fun testEachFileShowsItsWatches() {
        val panel = panel()
        val ring = myFixture.file.virtualFile
        val graph = myFixture.addFileToProject("graph.als", "pred show {}\nrun show for 3\n").virtualFile
        stored.setWatches(graph, listOf("#Node"))
        open(RING)
        val evaluator = panel.evaluator()
        evaluator.addWatch("succ")
        open(RING, graph)
        assertEquals(listOf("#Node"), evaluator.watchExpressions())
        assertEquals(EvaluationResult.IntValue("2"), evaluator.settle().watch("#Node").result)
        open(RING, ring)
        assertEquals(listOf("succ"), evaluator.watchExpressions())
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
