package com.serranofp.alloy.viewer

import com.intellij.codeInsight.hints.declarative.StringInlayActionPayload
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.wm.ToolWindowEP
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import com.serranofp.alloy.run.AlloyCommandRef
import com.serranofp.alloy.run.AlloyCommandResultClickHandler
import com.serranofp.alloy.run.AlloyCommandResultHintsProvider
import com.serranofp.alloy.run.AlloyCommandResults
import com.serranofp.alloy.run.CommandLink
import com.serranofp.alloy.run.CommandResult
import com.serranofp.alloy.run.CommandState
import com.serranofp.alloy.settings.AlloySettings
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.DropDownLink
import java.awt.CardLayout
import com.intellij.ui.components.JBLabel
import javax.swing.JComponent

class AlloyInstanceViewerTest : BasePlatformTestCase() {
    private val viewer get() = AlloyInstanceViewer.getInstance(project)
    private val results get() = AlloyCommandResults.getInstance(project)

    /** `run show` on line 11 and `check noCycles` on line 12. */
    private val graph = "sig Node { succ: set Node }\n" + "\n".repeat(8) + "pred show { some succ }\nrun show for 3\ncheck noCycles { no iden & ^succ } for 3\n"
    private val show = AlloyCommandRef("show", 0)
    private val noCycles = AlloyCommandRef("noCycles", 1)

    override fun setUp() {
        super.setUp()
        results.clear()
        viewer.clear()
        myFixture.configureByText("graph.als", graph)
    }

    private val file get() = myFixture.file.virtualFile

    /** Records a result of [command] that found a solution, as an execution does, and returns it. */
    private fun found(command: AlloyCommandRef, counterexample: Boolean): CommandResult {
        val model = if (counterexample) "sig Node { succ: set Node }\ncheck { no iden & ^succ } for 3" else STATIC
        val state = if (counterexample) CommandState.CounterexampleFound else CommandState.InstanceFound
        val result = CommandResult(
            state, "found", link = CommandLink.Solution(solve(model)),
            documentStamp = results.stamp(myFixture.editor.document), settingsGeneration = AlloySettings.getInstance().generation,
        )
        results.record(file, command, result)
        return result
    }

    fun testOpeningASecondResultReplacesTheFirst() {
        viewer.open(file, show, found(show, counterexample = false))
        val first = viewer.session!!
        viewer.open(file, noCycles, found(noCycles, counterexample = true))
        val second = viewer.session!!
        assertNotSame(first, second)
        assertEquals(noCycles, second.origin.command)
        assertTrue(second.origin.counterexample)
    }

    fun testHeader() {
        viewer.open(file, noCycles, found(noCycles, counterexample = true))
        assertEquals("graph.als: check noCycles {...} for 3 (line 12) · Counterexample", viewer.header())
        viewer.open(file, show, found(show, counterexample = false))
        assertEquals("graph.als: run show for 3 (line 11) · Instance", viewer.header())
    }

    fun testNavigatingToTheCommand() {
        viewer.open(file, noCycles, found(noCycles, counterexample = true))
        myFixture.editor.caretModel.moveToOffset(0)
        viewer.navigateToOrigin()
        val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
        assertEquals(graph.indexOf("check noCycles"), editor.caretModel.offset)
    }

    fun testNavigationFollowsEdits() {
        viewer.open(file, noCycles, found(noCycles, counterexample = true))
        myFixture.editor.caretModel.moveToOffset(0)
        myFixture.type("// a comment\n")
        viewer.navigateToOrigin()
        assertEquals(myFixture.editor.document.text.indexOf("check noCycles"), myFixture.editor.caretModel.offset)
    }

    fun testEditMarksTheSessionOutdated() {
        viewer.open(file, show, found(show, counterexample = false))
        assertNull(viewer.outdated())
        myFixture.type("x")
        assertNotNull(viewer.outdated())
        assertTrue(viewer.header(), viewer.header().endsWith(AlloyInstanceViewer.OUTDATED_SUFFIX))
        // The solution stays visible
        assertNotNull(viewer.session)
    }

    fun testHintClickOpensTheViewer() {
        found(show, counterexample = false)
        AlloyCommandResultClickHandler().click(myFixture.editor, StringInlayActionPayload(AlloyCommandResultHintsProvider.encode(show)))
        assertEquals(show, viewer.session!!.origin.command)
    }

    fun testUnsatisfiableResultsDoNotOpen() {
        val before = viewer.session
        val result = CommandResult(CommandState.NoCounterexample, "none", documentStamp = 0, settingsGeneration = 0)
        viewer.open(file, show, result)
        assertSame(before, viewer.session)
    }

    fun testPanelShowsTheEmptyStateThenTheSession() {
        val panel = AlloyInstancePanel(project, testRootDisposable)
        assertInstanceOf(panel.layout, CardLayout::class.java)
        val empty = panel.components.single { it.isVisible }
        assertEquals(AlloyInstanceViewer.EMPTY_TEXT, (empty as JBLabel).text)
        viewer.open(file, show, found(show, counterexample = false))
        UIUtil.dispatchAllInvocationEvents()
        val shown = panel.components.single { it.isVisible }
        // The header, not the projection chooser's link
        val links = UIUtil.findComponentsOfType(shown as JComponent, ActionLink::class.java).filter { it !is DropDownLink<*> }
        assertEquals(viewer.header(), links.single().text)
    }

    fun testToolWindowIsOnTheRight() {
        val window = ToolWindowEP.EP_NAME.extensionList.single { it.id == AlloyInstanceViewer.TOOL_WINDOW_ID }
        assertEquals("right", window.anchor)
    }

    fun testNewSolutionKindsHaveTheirOwnIcons() {
        assertEquals(SolutionKind.entries.size, SolutionKind.entries.map { it.icon }.toSet().size)
    }
}
