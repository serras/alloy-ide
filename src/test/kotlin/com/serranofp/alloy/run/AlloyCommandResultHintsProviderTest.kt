package com.serranofp.alloy.run

import com.intellij.codeInsight.hints.declarative.CollapseState
import com.intellij.codeInsight.hints.declarative.CollapsiblePresentationTreeBuilder
import com.intellij.codeInsight.hints.declarative.EndOfLinePosition
import com.intellij.codeInsight.hints.declarative.HintColorKind
import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayActionData
import com.intellij.codeInsight.hints.declarative.InlayHintsProviderFactory
import com.intellij.codeInsight.hints.declarative.InlayPayload
import com.intellij.codeInsight.hints.declarative.InlayPosition
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.PresentationTreeBuilder
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.codeInsight.hints.declarative.StringInlayActionPayload
import com.intellij.psi.SyntaxTraverser
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.serranofp.alloy.AlloyLanguage
import com.serranofp.alloy.settings.AlloySettings

class AlloyCommandResultHintsProviderTest : BasePlatformTestCase() {
    private val results get() = AlloyCommandResults.getInstance(project)
    private val show = AlloyCommandRef("show", 0)
    private val noCycles = AlloyCommandRef("noCycles", 1)

    private val model = """
        sig Node { next: lone Node }
        pred show { some next }
        run show
          for 3
        check noCycles { no iden & ^next } for 5
        run { some Node } for 2
    """.trimIndent()

    data class Hint(val line: Int, val text: String, val tooltip: String?, val format: HintFormat, val action: InlayActionData?)

    private class RecordingSink : InlayTreeSink {
        val hints = mutableListOf<Hint>()

        override fun addPresentation(
            position: InlayPosition,
            payloads: List<InlayPayload>?,
            tooltip: String?,
            hintFormat: HintFormat,
            builder: PresentationTreeBuilder.() -> Unit,
        ) {
            val recorder = RecordingBuilder().apply(builder)
            hints += Hint((position as EndOfLinePosition).line, recorder.text.toString(), tooltip, hintFormat, recorder.action)
        }

        override fun whenOptionEnabled(optionId: String, block: () -> Unit) = block()
    }

    private class RecordingBuilder : PresentationTreeBuilder {
        val text = StringBuilder()
        var action: InlayActionData? = null

        override fun list(builder: PresentationTreeBuilder.() -> Unit) = builder()

        override fun collapsibleList(
            state: CollapseState,
            expandedState: CollapsiblePresentationTreeBuilder.() -> Unit,
            collapsedState: CollapsiblePresentationTreeBuilder.() -> Unit,
        ) = error("not used")

        override fun text(text: String, actionData: InlayActionData?) {
            this.text.append(text)
            if (actionData != null) action = actionData
        }

        override fun clickHandlerScope(actionData: InlayActionData, builder: PresentationTreeBuilder.() -> Unit) {
            action = actionData
            builder()
        }
    }

    private val instance by lazy { instanceLink() }

    override fun setUp() {
        super.setUp()
        results.clear()
        myFixture.configureByText("model.als", model)
    }

    private val file get() = myFixture.file.virtualFile

    private fun record(command: AlloyCommandRef, state: CommandState, summary: String, link: CommandLink? = null) =
        results.record(
            file,
            command,
            CommandResult(state, summary, link = link, documentStamp = results.stamp(myFixture.editor.document), settingsGeneration = AlloySettings.getInstance().generation),
        )

    private fun hints(): List<Hint> {
        val collector = AlloyCommandResultHintsProvider().createCollector(myFixture.file, myFixture.editor) ?: return emptyList()
        val sink = RecordingSink()
        SyntaxTraverser.psiTraverser(myFixture.file).forEach { (collector as SharedBypassCollector).collectFromElement(it, sink) }
        return sink.hints
    }

    fun testProviderIsRegisteredAndEnabledByDefault() {
        val info = InlayHintsProviderFactory.getProviderInfo(AlloyLanguage, AlloyCommandResultHintsProvider.PROVIDER_ID)!!
        assertInstanceOf(info.provider, AlloyCommandResultHintsProvider::class.java)
        assertTrue(info.isEnabledByDefault)
        assertEquals("Command results", info.providerName)
    }

    fun testNoHintsWithoutResults() {
        assertEmpty(hints())
    }

    fun testHintAtTheEndOfTheFirstLineOfAMultiLineCommand() {
        record(show, CommandState.InstanceFound, "Instance found · 4 ms", instance)
        val hint = hints().single()
        assertEquals(2, hint.line)
        assertEquals("Instance found · 4 ms", hint.text)
        assertEquals(HintFormat.default, hint.format)
        assertEquals(AlloyCommandResultTooltip.html(results.get(file, show)!!, null), hint.tooltip)
        assertEquals(AlloyCommandResultClickHandler.HANDLER_ID, hint.action!!.handlerId)
        assertEquals(show, AlloyCommandResultHintsProvider.decode((hint.action!!.payload as StringInlayActionPayload).text))
    }

    fun testOnlyTheExecutedCommandGetsAHint() {
        record(noCycles, CommandState.CounterexampleFound, "Counterexample found · 3 ms", instance)
        assertEquals(listOf(4), hints().map { it.line })
    }

    fun testUnlabelledCommandIsFoundByPosition() {
        val third = AlloyCommandRef(null, 2)
        record(third, CommandState.NoInstance, "No instance found · 1 ms")
        assertEquals(listOf(5), hints().map { it.line })
        assertEquals(third, AlloyCommandResultHintsProvider.decode(AlloyCommandResultHintsProvider.encode(third)))
    }

    fun testOutdatedAfterAnEdit() {
        record(show, CommandState.InstanceFound, "Instance found · 4 ms", instance)
        myFixture.type("x")
        val hint = hints().single()
        assertEquals("Instance found · 4 ms (outdated)", hint.text)
        assertEquals(HintColorKind.TextWithoutBackground, hint.format.colorKind)
        assertTrue(hint.tooltip!!.contains(AlloyCommandResultTooltip.FILE_CHANGED))
        assertNotNull(hint.action)
    }

    fun testNoActionWithoutALink() {
        record(noCycles, CommandState.NoCounterexample, "No counterexample found · 3 ms")
        assertNull(hints().single().action)
    }

    fun testRunningIsClickable() {
        record(show, CommandState.Running, "Running…")
        val hint = hints().single()
        assertEquals("Running…", hint.text)
        assertNotNull(hint.action)
    }

    fun testStoppingIsStillRunning() {
        record(show, CommandState.Running, AlloyRunLog.STOPPING)
        val hint = hints().single()
        assertEquals("Stopping…", hint.text)
        assertNotNull(hint.action)
    }

    fun testStoppedBeforeCompletionKeepsTheOutputInItsTooltip() {
        results.record(
            file,
            show,
            CommandResult(
                CommandState.Stopped,
                AlloyRunLog.STOPPED,
                CommandDetails(log = listOf(AlloyRunLog.Line(listOf(AlloyRunLog.Span("   Solving..."))))),
                documentStamp = results.stamp(myFixture.editor.document),
                settingsGeneration = AlloySettings.getInstance().generation,
            ),
        )
        val hint = hints().single()
        assertEquals("Stopped before completion", hint.text)
        assertNull(hint.action)
        assertEquals("<html>&nbsp;&nbsp;&nbsp;Solving...</html>", hint.tooltip)
    }
}
