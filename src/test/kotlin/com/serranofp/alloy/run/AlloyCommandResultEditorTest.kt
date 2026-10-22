package com.serranofp.alloy.run

import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.AnimatedIcon
import com.intellij.util.ui.UIUtil
import com.serranofp.alloy.AlloyLanguage
import com.serranofp.alloy.psi.AlloyCommandDecl
import com.serranofp.alloy.settings.AlloySettings
import java.awt.event.InputEvent
import java.awt.event.MouseEvent

/** Results as the user sees them in a real editor: hints, gutter icons and plain clicks. */
class AlloyCommandResultEditorTest : AlloyRunTestCase() {
    private val show = AlloyCommandRef("show", 0)
    private val noCycles = AlloyCommandRef("noCycles", 1)

    override fun setUp() {
        super.setUp()
        myFixture.configureByText("model.als", "pred show {}\nrun show for 3\ncheck noCycles {} for 3\n")
    }

    private val file get() = myFixture.file.virtualFile

    private fun record(command: AlloyCommandRef, state: CommandState, summary: String, link: CommandLink? = null, vacuity: Vacuity? = null) {
        results.record(
            file,
            command,
            CommandResult(
                state, summary, link = link, documentStamp = results.stamp(myFixture.editor.document),
                settingsGeneration = AlloySettings.getInstance().generation, vacuity = vacuity,
            ),
        )
        UIUtil.dispatchAllInvocationEvents()
    }

    private fun hintLines(): List<Int> {
        myFixture.doHighlighting()
        val document = myFixture.editor.document
        return myFixture.editor.inlayModel.getAfterLineEndElementsInRange(0, document.textLength)
            .map { document.getLineNumber(it.offset) }
    }

    fun testHintAppearsWithoutReopeningTheFile() {
        assertEmpty(hintLines())
        record(show, CommandState.Running, "Running…")
        assertEquals(listOf(1), hintLines())
        record(noCycles, CommandState.NoCounterexample, "No counterexample found · 3 ms")
        assertEquals(listOf(1, 2), hintLines())
    }

    fun testHintBecomesOutdatedAfterASettingsChange() {
        record(show, CommandState.InstanceFound, "Instance found · 4 ms", instance)
        assertEquals(listOf(1), hintLines())
        AlloySettings.getInstance().nextGeneration()
        results.refreshHints()
        UIUtil.dispatchAllInvocationEvents()
        assertEquals(listOf(1), hintLines())
        assertTrue(results.isOutdated(results.get(file, show)!!, myFixture.editor.document))
    }

    private fun gutterIcon(prefix: String) =
        RunLineMarkerContributor.EXTENSION.allForLanguage(AlloyLanguage).filterIsInstance<AlloyRunLineMarkerContributor>().single()
            .getInfo(command(prefix).firstChild)!!.icon

    private fun command(prefix: String): AlloyCommandDecl =
        PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(myFixture.editor.document.text.indexOf(prefix)), AlloyCommandDecl::class.java)!!

    fun testGutterIconSpinsWhileRunning() {
        assertSame(AllIcons.RunConfigurations.TestState.Run, gutterIcon("run show"))
        record(show, CommandState.Running, "Running…")
        assertSame(AnimatedIcon.Default.INSTANCE, gutterIcon("run show"))
        assertSame(AllIcons.RunConfigurations.TestState.Run, gutterIcon("check noCycles"))
        record(show, CommandState.InstanceFound, "Instance found · 4 ms", instance)
        assertSame(AllIcons.RunConfigurations.TestState.Run, gutterIcon("run show"))
    }

    private val instance by lazy { instanceLink() }

    /** The result hint at the end of [line], or another inlay when it has none. */
    private fun hintOfLine(line: Int): Inlay<*> {
        myFixture.doHighlighting()
        return myFixture.editor.inlayModel.getAfterLineEndElementsForLogicalLine(line).firstOrNull() ?: otherInlay(line)
    }

    /** An end-of-line inlay that is not a hint, such as Git's inline blame. */
    private fun otherInlay(line: Int): Inlay<*> =
        myFixture.editor.inlayModel.addAfterLineEndElement(myFixture.editor.document.getLineEndOffset(line), false, object : EditorCustomElementRenderer {
            override fun calcWidthInPixels(inlay: Inlay<*>): Int = 10
        })!!

    private fun clickHintOfLine(line: Int, modifiers: Int = 0, inlay: Inlay<*> = hintOfLine(line)): EditorMouseEvent {
        val editor = myFixture.editor
        val offset = inlay.offset
        val mouse = MouseEvent(editor.contentComponent, MouseEvent.MOUSE_CLICKED, 0, modifiers or InputEvent.BUTTON1_DOWN_MASK, 0, 0, 1, false, MouseEvent.BUTTON1)
        val event = EditorMouseEvent(
            editor, mouse, EditorMouseEventArea.EDITING_AREA, offset,
            editor.offsetToLogicalPosition(offset), editor.offsetToVisualPosition(offset), false, null, inlay, null,
        )
        AlloyCommandResultMouseListener(AlloyCommandResultClickHandler()).mouseClicked(event)
        return event
    }

    fun testPlainClickOnAnInstanceIsHandled() {
        record(show, CommandState.InstanceFound, "Instance found · 4 ms", instance)
        assertTrue(clickHintOfLine(1).isConsumed)
    }

    fun testPlainClickOnACounterexampleIsHandled() {
        record(noCycles, CommandState.CounterexampleFound, "Counterexample found · 3 ms", instance)
        assertTrue(clickHintOfLine(2).isConsumed)
    }

    fun testPlainClickOnARunningHintStops() {
        val execution = newExecution(project, file, show)
        service.startRun(execution)
        UIUtil.dispatchAllInvocationEvents()
        assertTrue(clickHintOfLine(1).isConsumed)
        UIUtil.dispatchAllInvocationEvents()
        assertTrue(execution.isFinished())
        assertEquals(CommandState.Stopped, results.get(file, show)!!.state)
    }

    fun testPlainClickOnAHintWithNothingToOpenIsIgnored() {
        record(noCycles, CommandState.NoCounterexample, "No counterexample found · 3 ms")
        assertFalse(clickHintOfLine(2).isConsumed)
    }

    fun testCtrlClickIsLeftToThePlatform() {
        record(show, CommandState.InstanceFound, "Instance found · 4 ms", instance)
        assertFalse(clickHintOfLine(1, InputEvent.CTRL_DOWN_MASK).isConsumed)
        assertFalse(clickHintOfLine(1, InputEvent.META_DOWN_MASK).isConsumed)
    }

    fun testClickOnAnotherInlayIsIgnored() {
        record(show, CommandState.InstanceFound, "Instance found · 4 ms", instance)
        assertFalse(clickHintOfLine(1, inlay = otherInlay(1)).isConsumed)
    }

    fun testClickBeforeTheEditIsCommittedIsIgnored() {
        record(show, CommandState.InstanceFound, "Instance found · 4 ms", instance)
        val hint = hintOfLine(1)
        val document = myFixture.editor.document
        // The hint moves to the first line, where the stale PSI still has `run show`
        WriteCommandAction.runWriteCommandAction(project) { document.deleteString(0, document.getLineStartOffset(1)) }
        assertFalse(PsiDocumentManager.getInstance(project).isCommitted(document))
        assertFalse(clickHintOfLine(0, inlay = hint).isConsumed)
    }

    fun testPlainClickOnARunningVacuityCheckStops() {
        val execution = newExecution(project, file, noCycles)
        service.startRun(execution)
        record(noCycles, CommandState.NoCounterexample, "No counterexample found · 3 ms, checking vacuity…", vacuity = Vacuity.Checking)
        assertTrue(clickHintOfLine(2).isConsumed)
        UIUtil.dispatchAllInvocationEvents()
        assertTrue(execution.isFinished())
    }

    fun testClickOnALineWithoutResultIsIgnored() {
        assertFalse(clickHintOfLine(0).isConsumed)
        assertFalse(clickHintOfLine(1).isConsumed)
    }
}
