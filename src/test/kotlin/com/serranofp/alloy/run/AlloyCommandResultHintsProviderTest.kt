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
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.SyntaxTraverser
import com.serranofp.alloy.AlloyLanguage
import com.serranofp.alloy.settings.AlloySettings

class AlloyCommandResultHintsProviderTest : AlloyRunTestCase() {
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
        assertEquals(show, AlloyCommandResultHintsProvider.Codec.decode((hint.action.payload as StringInlayActionPayload).text))
    }

    fun testOnlyTheExecutedCommandGetsAHint() {
        record(noCycles, CommandState.CounterexampleFound, "Counterexample found · 3 ms", instance)
        assertEquals(listOf(4), hints().map { it.line })
    }

    fun testUnlabelledCommandIsFoundByPosition() {
        val third = AlloyCommandRef(null, 2)
        record(third, CommandState.NoInstance, "No instance found · 1 ms")
        assertEquals(listOf(5), hints().map { it.line })
        assertEquals(third, AlloyCommandResultHintsProvider.Codec.decode(AlloyCommandResultHintsProvider.Codec.encode(third)))
    }

    private fun insertAbove(prefix: String, text: String) {
        val document = myFixture.editor.document
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(document.text.indexOf(prefix), text) }
        PsiDocumentManager.getInstance(project).commitDocument(document)
    }

    fun testHintFollowsItsLabelWhenACommandIsInsertedAbove() {
        record(show, CommandState.InstanceFound, "Instance found · 4 ms", instance)
        insertAbove("run show", "run {} for 1\n")
        val hint = hints().single()
        assertEquals(3, hint.line)
        assertEquals("Instance found · 4 ms (outdated)", hint.text)
        assertEquals(show, AlloyCommandResultHintsProvider.Codec.decode((hint.action!!.payload as StringInlayActionPayload).text))
    }

    fun testRunningHintFollowsItsLabelWhenACommandIsInsertedAbove() {
        record(show, CommandState.Running, "Running…")
        insertAbove("run show", "run {} for 1\n")
        val hint = hints().single()
        assertEquals(3, hint.line)
        assertNotNull(hint.action)
    }

    fun testMalformedPayloadsAreNoCommand() {
        for (payload in listOf("", "x", "3", "x:show", "3:show:x")) {
            assertNull(payload, AlloyCommandResultHintsProvider.Codec.decode(payload))
        }
        assertEquals(AlloyCommandRef(null, 3), AlloyCommandResultHintsProvider.Codec.decode("3:"))
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

    /** Records the result of a check without counterexample after a vacuity check of [vacuity], as an execution does. */
    private fun recordVacuity(vacuity: Vacuity?) {
        val log = AlloyRunLog()
        analysisEvents("sig Node { succ: lone Node }\nfact { no n: Node | n in n.^succ }\nassert noCycles { no iden & ^succ }\ncheck noCycles for 5")
            .forEach(log::accept)
        results.record(
            file,
            noCycles,
            CommandResult(
                CommandState.NoCounterexample, log.summary(vacuity), CommandDetails(log = log.lines),
                documentStamp = results.stamp(myFixture.editor.document), settingsGeneration = AlloySettings.getInstance().generation, vacuity = vacuity,
            ),
        )
    }

    fun testHintOfEachVacuityState() {
        val expected = mapOf(
            Vacuity.Checking to Regex("No counterexample found · \\d+ ms, checking vacuity…"),
            Vacuity.NotVacuous(1) to Regex("No counterexample found · \\d+ ms"),
            Vacuity.VacuousFacts(1, 0) to Regex("No counterexample found \\(vacuous\\) · \\d+ ms"),
            Vacuity.VacuousPremise("this/A", 1, 0) to Regex("No counterexample found \\(vacuous\\) · \\d+ ms"),
            Vacuity.Unknown(AlloyCommandExecution.VACUITY_STOPPED) to Regex("No counterexample found · \\d+ ms"),
        )
        for ((vacuity, text) in expected) {
            recordVacuity(vacuity)
            val hint = hints().single()
            assertTrue("$vacuity: ${hint.text}", hint.text.matches(text))
            assertTrue("$vacuity: ${hint.tooltip}", hint.tooltip!!.contains("<b>Vacuity check</b>"))
            assertTrue(hint.tooltip.contains(AlloyCommandResultTooltip.vacuity(vacuity).first().replace("'", "&#39;")))
            // A running vacuity check can be stopped from its hint, as the analysis before it
            assertEquals("$vacuity", vacuity == Vacuity.Checking, hint.action != null)
        }
    }

    fun testHintWithoutVacuityCheck() {
        recordVacuity(null)
        val hint = hints().single()
        assertTrue(hint.text, hint.text.matches(Regex("No counterexample found · \\d+ ms")))
        assertFalse(hint.tooltip!!.contains("Vacuity check"))
    }
}
