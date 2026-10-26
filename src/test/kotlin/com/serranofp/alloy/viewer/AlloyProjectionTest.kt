package com.serranofp.alloy.viewer

import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.impl.ActionToolbarImpl
import com.intellij.openapi.ui.ComboBox
import com.intellij.testFramework.TestActionEvent
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.DropDownLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.util.ui.UIUtil
import com.serranofp.alloy.run.waitUntil
import edu.mit.csail.sdg.alloy4graph.GraphViewer
import edu.mit.csail.sdg.alloy4viz.AlloyProjection
import java.awt.event.KeyEvent
import javax.swing.JComponent

/** Two atoms in every state, one of them in `X` only in the first state. */
private const val TWO_NODES = "sig Node {}\nvar sig X in Node {}\nfact { #Node = 2 }\nrun { some X and after no X } for 2 but 2..3 steps"

class AlloyProjectionTest : AlloyViewerTestCase() {
    private val states = "sig Item {}\nsig State { holds: set Item }\nrun { some holds } for 3"

    private fun AlloySolutionModel.type(name: String) = projectableTypes.single { it.name == name }

    fun testProjectingOverSeveralSignaturesAndRemovingThem() {
        val model = AlloySolutionModel(solve(states))
        assertEmpty(model.projections)
        model.setProjected(model.type("State"), true)
        model.setProjected(model.type("Item"), true)
        assertEquals(setOf("State", "Item"), model.vizState.projectedTypes.map { it.name }.toSet())
        // Still offered, so that they can be unchecked
        assertTrue(model.projectableTypes.map { it.name }.containsAll(listOf("State", "Item")))

        model.setProjected(model.type("Item"), false)
        assertEquals(listOf("State"), model.vizState.projectedTypes.map { it.name })
        model.clearProjections()
        assertEmpty(model.vizState.projectedTypes)
    }

    fun testProjectingAppliesToBothStatesOfATrace() {
        val model = AlloySolutionModel(solve(TRACES))
        val both = listOf(model.vizState, model.nextVizState!!)
        model.setProjected(model.type("A"), true)
        for (viz in both) assertEquals(listOf("A"), viz.projectedTypes.map { it.name })
        // Stepping keeps the projection of both
        model.goTo(model.successor())
        for (viz in both) assertEquals(listOf("A"), viz.projectedTypes.map { it.name })
        model.setProjected(model.type("A"), false)
        for (viz in both) assertEmpty(viz.projectedTypes)
        model.setProjected(model.type("A"), true)
        model.clearProjections()
        for (viz in both) assertEmpty(viz.projectedTypes)
    }

    fun testMagicLayoutDefault() {
        assertEquals(listOf("State"), AlloySolutionModel(solve(ORDERING)).projections.map { it.name })
    }

    private fun JComponent.checkBoxes() = UIUtil.findComponentsOfType(this, JBCheckBox::class.java)

    fun testChooserCheckboxesAndSummary() {
        var redrawn = 0
        val chooser = AlloyProjectionChooser { redrawn++ }
        val model = AlloySolutionModel(solve(states))
        chooser.show(model)
        assertEquals("None", chooser.summary)
        assertEquals("None", chooser.link.text)

        val content = chooser.createContent()
        val boxes = content.checkBoxes().associateBy { it.text }
        assertTrue(boxes.keys.containsAll(listOf("State", "Item")))
        assertTrue(boxes.values.none { it.isSelected })
        boxes.getValue("State").doClick()
        boxes.getValue("Item").doClick()
        assertEquals(listOf("State", "Item"), chooser.projections.map { it.name })
        assertEquals("State, Item", chooser.link.text)
        assertEquals(2, redrawn)
        // The chooser's own: the model is projected by the viewer that draws it
        assertEmpty(model.projections)

        boxes.getValue("Item").doClick()
        assertEquals("State", chooser.link.text)

        // Clear all, so that no projection is left
        UIUtil.findComponentsOfType(content, ActionLink::class.java).single { it.text == AlloyProjectionChooser.CLEAR_ALL }.doClick()
        assertEmpty(chooser.projections)
        assertEquals("None", chooser.link.text)
        assertTrue(boxes.values.none { it.isSelected })
    }

    fun testChooserReflectsTheMagicLayoutsChoice() {
        val chooser = AlloyProjectionChooser {}
        chooser.show(AlloySolutionModel(solve(ORDERING)))
        assertEquals("State", chooser.link.text)
        assertTrue(chooser.createContent().checkBoxes().single { it.text == "State" }.isSelected)
    }

    private val session get() = viewer.session(myFixture.file.virtualFile)!!

    private fun JComponent.graphs() = UIUtil.findComponentsOfType(this, GraphViewer::class.java)

    private fun JComponent.atomSelectors() = UIUtil.findComponentsOfType(this, ComboBox::class.java)

    fun testPanelShowsTheChooser() {
        val panel = panelFor(ORDERING)
        assertEquals("State", UIUtil.findComponentOfType(panel, DropDownLink::class.java)!!.text)
        // The magic layout's projection has its atom selector, on the first atom
        val selector = panel.atomSelectors().single()
        assertEquals(session.model.vizState.originalInstance.type2atoms(session.model.projections.single()).sorted().first(), selector.selectedItem)
    }

    fun testATraceShowsTwoGraphsAndAStaticResultOne() {
        assertEquals(2, panelFor(LASSO).graphs().size)
        assertEquals(1, panelFor(STATIC).graphs().size)
    }

    fun testPickingAnAtomAppliesToBothGraphs() {
        val panel = panelFor(TWO_NODES)
        val model = session.model
        val node = model.type("Node")
        panel.setProjected(node, true)
        val selector = panel.atomSelectors().single()
        val atoms = model.vizState.originalInstance.type2atoms(node).sorted()
        assertEquals(2, atoms.size)
        assertEquals(atoms[0], selector.selectedItem)

        selector.selectedItem = atoms[1]
        val choice = AlloyProjection(mapOf(node to atoms[1]))
        // The visualizer caches one graph per projection, so these are the graphs for that atom
        assertEquals(listOf(model.vizState.getGraph(null, choice), model.nextVizState!!.getGraph(null, choice)), panel.graphs())
        assertEquals(1, panel.atomSelectors().size)

        // Stepping keeps the atom, which still exists
        UIUtil.findComponentOfType(panel, AlloyTraceStrip::class.java)!!.press(KeyEvent.VK_RIGHT)
        waitUntil("state 1 was not shown") { model.state == 1 }
        assertEquals(atoms[1], panel.atomSelectors().single().selectedItem)
    }

    fun testClickingTheStripKeepsTheProjectionAndTheAtom() {
        val panel = panelFor(TWO_NODES)
        val model = session.model
        val node = model.type("Node")
        panel.setProjected(node, true)
        val atoms = model.vizState.originalInstance.type2atoms(node).sorted()
        panel.atomSelectors().single().selectedItem = atoms[1]

        UIUtil.findComponentOfType(panel, AlloyTraceStrip::class.java)!!.pressState(1)
        waitUntil("state 1 was not shown") { model.state == 1 }
        for (viz in listOf(model.vizState, model.nextVizState!!)) assertEquals(listOf(node), viz.projectedTypes.toList())
        assertEquals(atoms[1], panel.atomSelectors().single().selectedItem)
        val choice = AlloyProjection(mapOf(node to atoms[1]))
        assertEquals(listOf(model.vizState.getGraph(null, choice), model.nextVizState!!.getGraph(null, choice)), panel.graphs())
    }

    /** So the atom chosen in one state of a trace is in the other too: the two graphs can share the choice. */
    fun testMutableSignaturesCannotBeProjected() {
        val model = AlloySolutionModel(solve("var sig P {}\nsig Q {}\nfact { one P and always P' != P }\nrun { some Q } for 2 but 2 steps"))
        assertEquals(listOf("Q"), model.projectableTypes.map { it.name }.filter { it == "P" || it == "Q" })
    }

    private fun JComponent.projectionLink() = UIUtil.findComponentOfType(this, DropDownLink::class.java)!!

    private fun JComponent.header() = UIUtil.findComponentsOfType(this, ActionLink::class.java).single { it !is DropDownLink<*> }.text

    /** Two editors of one file show its session, each with its own projection. */
    fun testTwoViewersOfOneFileHaveTheirOwnProjection() {
        myFixture.configureByText("m.als", SHOW_FILE)
        val first = panel()
        val second = panel()
        open(states)
        assertEquals(first.header(), second.header())
        val model = session.model
        first.setProjected(model.type("State"), true)
        assertEquals("State", first.projectionLink().text)
        assertEquals(1, first.atomSelectors().size)
        // Drawing the other one again leaves it unprojected, and the first stays projected
        second.stateChanged()
        assertEquals("None", second.projectionLink().text)
        assertEmpty(second.atomSelectors())
        assertEquals(listOf(model.vizState.getGraph(null, AlloyProjection())), second.graphs())
        first.stateChanged()
        assertEquals(1, first.atomSelectors().size)
    }

    /** The new solution keeps the projection of the viewer that asked for it, and every other viewer keeps its own. */
    fun testANewSolutionKeepsEachViewersProjection() {
        myFixture.configureByText("m.als", SHOW_FILE)
        val first = panel()
        val second = panel()
        val before = open(states)
        first.setProjected(before.model.type("State"), true)
        val group = UIUtil.findComponentOfType(first, ActionToolbarImpl::class.java)!!.actionGroup as DefaultActionGroup
        val newConfig = group.childActionsOrStubs.single { it.templateText == SolutionKind.Config.label }
        newConfig.actionPerformed(TestActionEvent.createTestEvent(newConfig))
        waitUntil("the new solution did not arrive") { !viewer.enumerating && session !== before }
        UIUtil.dispatchAllInvocationEvents()
        assertEquals(listOf("State"), session.model.initialProjections.map { it.name })
        assertEquals("State", first.projectionLink().text)
        assertEquals("None", second.projectionLink().text)
    }

    /** A projected signature without atoms has nothing to choose: its selector is disabled, and the graph shows. */
    fun testASignatureWithoutAtomsHasADisabledSelector() {
        val panel = panelFor("sig Item {}\nsig State { holds: set Item }\nrun { no Item and some State } for 3")
        val model = session.model
        val item = model.type("Item")
        assertEmpty(model.vizState.originalInstance.type2atoms(item))
        panel.setProjected(item, true)
        val selector = panel.atomSelectors().single()
        assertFalse(selector.isEnabled)
        assertNull(selector.selectedItem)
        assertEquals(listOf(model.vizState.getGraph(null, AlloyProjection())), panel.graphs())
    }
}
