package com.serranofp.alloy.viewer

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.DropDownLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.util.ui.UIUtil
import com.serranofp.alloy.run.AlloyCommandRef
import com.serranofp.alloy.run.AlloyCommandResults
import com.serranofp.alloy.run.CommandLink
import com.serranofp.alloy.run.CommandResult
import com.serranofp.alloy.run.CommandState
import com.serranofp.alloy.settings.AlloySettings
import javax.swing.JComponent

class AlloyProjectionTest : BasePlatformTestCase() {
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
        assertEquals(setOf("State", "Item"), model.projections.map { it.name }.toSet())
        assertEquals(model.projections.joinToString(", ") { it.name }, chooser.link.text)
        assertEquals(2, redrawn)

        boxes.getValue("Item").doClick()
        assertEquals("State", chooser.link.text)

        // Clear all, so that no projection is left
        UIUtil.findComponentsOfType(content, ActionLink::class.java).single { it.text == AlloyProjectionChooser.CLEAR_ALL }.doClick()
        assertEmpty(model.projections)
        assertEquals("None", chooser.link.text)
        assertTrue(boxes.values.none { it.isSelected })
    }

    fun testChooserReflectsTheMagicLayoutsChoice() {
        val chooser = AlloyProjectionChooser {}
        chooser.show(AlloySolutionModel(solve(ORDERING)))
        assertEquals("State", chooser.link.text)
        assertTrue(chooser.createContent().checkBoxes().single { it.text == "State" }.isSelected)
    }

    fun testPanelShowsTheChooser() {
        AlloyCommandResults.getInstance(project).clear()
        val viewer = AlloyInstanceViewer.getInstance(project)
        viewer.clear()
        myFixture.configureByText("m.als", "pred show {}\nrun show for 3\n")
        val panel = AlloyInstancePanel(project, testRootDisposable)
        val results = AlloyCommandResults.getInstance(project)
        val result = CommandResult(
            CommandState.InstanceFound, "found", link = CommandLink.Solution(solve(ORDERING)),
            documentStamp = results.stamp(myFixture.editor.document), settingsGeneration = AlloySettings.getInstance().generation,
        )
        viewer.open(myFixture.file.virtualFile, AlloyCommandRef("show", 0), result)
        UIUtil.dispatchAllInvocationEvents()
        assertEquals("State", UIUtil.findComponentOfType(panel, DropDownLink::class.java)!!.text)
    }
}
