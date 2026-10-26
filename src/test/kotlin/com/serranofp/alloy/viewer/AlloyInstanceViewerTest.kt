package com.serranofp.alloy.viewer

import com.intellij.codeInsight.hints.declarative.StringInlayActionPayload
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.ui.UIUtil
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.serranofp.alloy.AlloyBundle
import com.serranofp.alloy.run.AlloyCommandRef
import com.serranofp.alloy.run.AlloyCommandResultClickHandler
import com.serranofp.alloy.run.AlloyCommandResultHintsProvider
import com.serranofp.alloy.run.CommandResult
import com.serranofp.alloy.run.CommandState
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.DropDownLink
import java.awt.CardLayout
import com.intellij.ui.components.JBLabel
import javax.swing.JComponent

class AlloyInstanceViewerTest : AlloyViewerTestCase() {
    /** `run show` on line 11 and `check noCycles` on line 12. */
    private val graph = "sig Node { succ: set Node }\n" + "\n".repeat(8) + "pred show { some succ }\nrun show for 3\ncheck noCycles { no iden & ^succ } for 3\n"
    private val show = SHOW
    private val noCycles = AlloyCommandRef("noCycles", 1)

    override fun setUp() {
        super.setUp()
        myFixture.configureByText("graph.als", graph)
    }

    private val file get() = myFixture.file.virtualFile

    /** `run enqueue` on line 3. */
    private val enqueue = AlloyCommandRef("enqueue", 0)

    private fun queue(): VirtualFile = myFixture.addFileToProject("queue.als", "sig Elem {}\npred enqueue { some Elem }\nrun enqueue for 3\n").virtualFile

    /** Records a result of [command] in [file] that found a solution, as an execution does, and returns it. */
    private fun found(command: AlloyCommandRef, counterexample: Boolean, file: VirtualFile = this.file): CommandResult {
        val model = if (counterexample) "sig Node { succ: set Node }\ncheck { no iden & ^succ } for 3" else STATIC
        val state = if (counterexample) CommandState.CounterexampleFound else CommandState.InstanceFound
        val result = result(model, file, state, if (counterexample) COUNTEREXAMPLE_HINT else INSTANCE_HINT)
        results.record(file, command, result)
        return result
    }

    fun testOpeningASecondResultReplacesTheFirst() {
        viewer.open(file, show, found(show, counterexample = false))
        val first = viewer.session(file)!!
        viewer.open(file, noCycles, found(noCycles, counterexample = true))
        val second = viewer.session(file)!!
        assertNotSame(first, second)
        assertEquals(noCycles, second.origin.command)
        assertTrue(second.origin.counterexample)
    }

    fun testEachFileHasItsOwnSession() {
        val queue = queue()
        viewer.open(file, show, found(show, counterexample = false))
        viewer.open(queue, enqueue, found(enqueue, counterexample = false, queue))
        assertEquals("graph.als:11: run show for 3 · $INSTANCE_HINT", viewer.header(viewer.session(file)))
        assertEquals("queue.als:3: run enqueue for 3 · $INSTANCE_HINT", viewer.header(viewer.session(queue)))
    }

    fun testOpeningReplacesOnlyThatFilesSession() {
        val queue = queue()
        viewer.open(file, show, found(show, counterexample = false))
        viewer.open(queue, enqueue, found(enqueue, counterexample = false, queue))
        val queued = viewer.session(queue)
        viewer.open(file, noCycles, found(noCycles, counterexample = true))
        assertEquals(noCycles, viewer.session(file)!!.origin.command)
        assertSame(queued, viewer.session(queue))
    }

    fun testListenersHearOnlyTheirFile() {
        val queue = queue()
        var graphCalls = 0
        var queueCalls = 0
        viewer.addListener(file, testRootDisposable) { graphCalls++ }
        viewer.addListener(queue, testRootDisposable) { queueCalls++ }
        viewer.open(file, show, found(show, counterexample = false))
        UIUtil.dispatchAllInvocationEvents()
        assertTrue(graphCalls > 0)
        assertEquals(0, queueCalls)
    }

    fun testASessionIsKeptWhileAViewerIsAttached() {
        val first = Disposer.newDisposable(testRootDisposable, "first")
        val second = Disposer.newDisposable(testRootDisposable, "second")
        viewer.attach(file, first)
        viewer.attach(file, second)
        viewer.open(file, show, found(show, counterexample = false))
        val anchor = viewer.session(file)!!.anchor!!
        Disposer.dispose(first)
        assertNotNull(viewer.session(file))
        assertTrue(anchor.isValid)
        Disposer.dispose(second)
        assertNull(viewer.session(file))
        assertFalse(anchor.isValid)
    }

    fun testASessionWithoutViewersStaysUntilCleared() {
        viewer.open(file, show, found(show, counterexample = false))
        val anchor = viewer.session(file)!!.anchor!!
        UIUtil.dispatchAllInvocationEvents()
        assertNotNull(viewer.session(file))
        viewer.clear()
        assertNull(viewer.session(file))
        assertFalse(anchor.isValid)
    }

    fun testHeader() {
        viewer.open(file, noCycles, found(noCycles, counterexample = true))
        assertEquals("graph.als:12: check noCycles {...} for 3 · $COUNTEREXAMPLE_HINT", viewer.header(viewer.session(file)))
        viewer.open(file, show, found(show, counterexample = false))
        assertEquals("graph.als:11: run show for 3 · $INSTANCE_HINT", viewer.header(viewer.session(file)))
    }

    fun testHeaderLineFollowsEdits() {
        viewer.open(file, noCycles, found(noCycles, counterexample = true))
        myFixture.editor.caretModel.moveToOffset(0)
        myFixture.type("// a comment\n")
        assertTrue(viewer.header(viewer.session(file)), viewer.header(viewer.session(file)).startsWith("graph.als:13: check noCycles {...} for 3 · $COUNTEREXAMPLE_HINT"))
    }

    fun testHeaderWithoutTheCommandHasNoLine() {
        val gone = AlloyCommandRef("gone", 5)
        viewer.open(file, gone, found(gone, counterexample = false))
        assertEquals("graph.als: ${gone.displayName} · $INSTANCE_HINT", viewer.header(viewer.session(file)))
    }

    /** The command is found in the PSI, whose offsets are the document's only once it is committed. */
    fun testOpeningSeesUncommittedEdits() {
        val document = myFixture.editor.document
        val result = found(noCycles, counterexample = true)
        WriteCommandAction.runWriteCommandAction(project) { document.deleteString(0, document.getLineEndOffset(0) + 1) }
        assertFalse(PsiDocumentManager.getInstance(project).isCommitted(document))
        viewer.open(file, noCycles, result)
        assertTrue(viewer.header(viewer.session(file)), viewer.header(viewer.session(file)).startsWith("graph.als:11: check noCycles {...} for 3 · $COUNTEREXAMPLE_HINT"))
        assertEquals(document.text.indexOf("check noCycles"), viewer.session(file)!!.commandOffset)
    }

    fun testNavigatingToTheCommand() {
        viewer.open(file, noCycles, found(noCycles, counterexample = true))
        myFixture.editor.caretModel.moveToOffset(0)
        viewer.navigateToOrigin(file)
        val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
        assertEquals(graph.indexOf("check noCycles"), editor.caretModel.offset)
    }

    fun testNavigationFollowsEdits() {
        viewer.open(file, noCycles, found(noCycles, counterexample = true))
        myFixture.editor.caretModel.moveToOffset(0)
        myFixture.type("// a comment\n")
        viewer.navigateToOrigin(file)
        assertEquals(myFixture.editor.document.text.indexOf("check noCycles"), myFixture.editor.caretModel.offset)
    }

    fun testEditMarksTheSessionOutdated() {
        viewer.open(file, show, found(show, counterexample = false))
        assertNull(viewer.outdated(viewer.session(file)))
        myFixture.type("x")
        assertNotNull(viewer.outdated(viewer.session(file)))
        assertTrue(viewer.header(viewer.session(file)), viewer.header(viewer.session(file)).endsWith(AlloyBundle.message("result.outdated.suffix")))
        // The solution stays visible
        assertNotNull(viewer.session(file))
    }

    fun testHintClickOpensTheViewer() {
        found(show, counterexample = false)
        AlloyCommandResultClickHandler().click(myFixture.editor, StringInlayActionPayload(AlloyCommandResultHintsProvider.Codec.encode(show)))
        assertEquals(show, viewer.session(file)!!.origin.command)
    }

    fun testUnsatisfiableResultsDoNotOpen() {
        val before = viewer.session(file)
        val result = CommandResult(CommandState.NoCounterexample, "none", documentStamp = 0, settingsGeneration = 0)
        viewer.open(file, show, result)
        assertSame(before, viewer.session(file))
    }

    fun testPanelShowsTheEmptyStateThenTheSession() {
        val panel = AlloyInstancePanel(project, file, testRootDisposable)
        assertInstanceOf(panel.layout, CardLayout::class.java)
        val empty = panel.components.single { it.isVisible }
        assertEquals(AlloyInstanceViewer.EMPTY_TEXT, (empty as JBLabel).text)
        viewer.open(file, show, found(show, counterexample = false))
        UIUtil.dispatchAllInvocationEvents()
        val shown = panel.components.single { it.isVisible }
        // The header, not the projection chooser's link
        val links = UIUtil.findComponentsOfType(shown as JComponent, ActionLink::class.java).filter { it !is DropDownLink<*> }
        assertEquals(viewer.header(viewer.session(file)), links.single().text)
    }

    /** The header ends as the hint reads, also when it is outdated. */
    fun testHeaderEndsWithTheHintsText() {
        viewer.open(file, show, found(show, counterexample = false))
        myFixture.type("x")
        assertEquals("graph.als:11: run show for 3 · $INSTANCE_HINT" + AlloyBundle.message("result.outdated.suffix"), viewer.header(viewer.session(file)))
    }

    fun testNewSolutionKindsHaveTheirOwnIcons() {
        assertEquals(SolutionKind.entries.size, SolutionKind.entries.map { it.icon }.toSet().size)
    }

    private companion object {
        /** The texts of the hints of the results here, as an execution writes them. */
        const val INSTANCE_HINT = "Instance found, as expected · 4 ms"
        const val COUNTEREXAMPLE_HINT = "Counterexample found · 9 ms"
    }
}
