package com.serranofp.alloy.run

import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.ui.AnimatedIcon
import java.util.concurrent.TimeUnit
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.serranofp.alloy.AlloyLanguage
import com.serranofp.alloy.psi.AlloyCommandDecl
import com.serranofp.alloy.settings.AlloySettings

class AlloyRunMarkersTest : BasePlatformTestCase() {
    private val model = """
        sig Node { succ: lone Node }
        pred show { some succ }
        run show for 3
        fact { no n: Node | n in n.^succ }
        check noCycles { no iden & ^succ } for 5
        run { some Node } for 2
        run first => check second
        pred first {}
        assert second {}
    """.trimIndent()

    private val service get() = AlloyRunService.getInstance(project)
    private val results get() = AlloyCommandResults.getInstance(project)
    private val contributor
        get() = RunLineMarkerContributor.EXTENSION.allForLanguage(AlloyLanguage)
            .filterIsInstance<AlloyRunLineMarkerContributor>().single()

    override fun setUp() {
        super.setUp()
        results.clear()
    }

    override fun tearDown() {
        try {
            service.activeExecution?.terminate()
        } finally {
            super.tearDown()
        }
    }

    fun testMarkersOnlyOnCommandKeywords() {
        myFixture.configureByText("model.als", model)
        val marked = PsiTreeUtil.collectElements(myFixture.file) { it.firstChild == null }
            .filter { contributor.getInfo(it) != null }
        val document = myFixture.editor.document
        assertEquals(listOf(2, 4, 5, 6), marked.map { document.getLineNumber(it.textOffset) })
        assertTrue(marked.all { it.text == "run" || it.text == "check" })
        assertEquals("run", marked.last().text)
    }

    fun testMarkerActionLaunchesItsCommand() {
        myFixture.configureByText("model.als", model)
        val file = myFixture.file.virtualFile

        val action = contributor.getInfo(commandAt("check noCycles").firstChild)!!.actions.single()
        assertEquals("Run noCycles", action.templatePresentation.text)
        val context = SimpleDataContext.builder().add(CommonDataKeys.EDITOR, myFixture.editor).build()
        action.actionPerformed(TestActionEvent.createTestEvent(action, context))
        waitUntilIdle(project)

        assertEquals(listOf(AlloyCommandRef("noCycles", 1)), results.results(file).keys.toList())
        val result = results.get(file, AlloyCommandRef("noCycles", 1))!!
        assertEquals(result.toString(), CommandState.NoCounterexample, result.state)
        assertEquals("Executing \"Check noCycles for 5\"", result.details.log[2].text)
    }

    fun testRunningMarkerOpensAMenuWithStop() {
        myFixture.configureByText("model.als", PIGEONHOLE)
        val file = myFixture.file.virtualFile
        val show = AlloyCommandRef("show", 0)
        val execution = newExecution(project, file, show)
        service.startRun(execution)
        val task = AlloyCommandTask(project, execution, AlloyCommandLauncher(project, options = ::slowOptions), testRootDisposable)
        val running = ApplicationManager.getApplication().executeOnPooledThread { task.run(EmptyProgressIndicator()) }
        waitUntil("the analysis did not start solving") { execution.log.progress == "Solving..." }

        val command = PsiTreeUtil.findChildOfType(myFixture.file, AlloyCommandDecl::class.java)!!
        val info = contributor.getInfo(command.firstChild)!!
        assertSame(AnimatedIcon.Default.INSTANCE, info.icon)
        // A single action is what a click runs: it opens the menu
        val menu = info.actions.single() as AlloyRunningCommandMenuAction
        val stop = menu.group.getChildren(null).single()
        assertEquals("Stop", stop.templatePresentation.text)

        stop.actionPerformed(TestActionEvent.createTestEvent(stop, SimpleDataContext.getProjectContext(project)))
        running.get(10, TimeUnit.SECONDS)
        assertTrue(execution.isFinished())
        assertEquals("Stopped before completion", results.get(file, show)!!.summary)
        assertSame(AllIcons.RunConfigurations.TestState.Run, contributor.getInfo(command.firstChild)!!.icon)
    }

    /** Records a finished result for the command at [prefix], current for the editor's document. */
    private fun recordAt(prefix: String, expectation: Expectation?, vacuity: Vacuity? = null, state: CommandState = CommandState.NoCounterexample) {
        val stamp = results.stamp(myFixture.editor.document)
        val result = CommandResult(state, "", documentStamp = stamp, settingsGeneration = AlloySettings.getInstance().generation, expectation = expectation, vacuity = vacuity)
        results.record(myFixture.file.virtualFile, AlloyCommands.ref(commandAt(prefix)), result)
    }

    private fun infoAt(prefix: String): RunLineMarkerContributor.Info = contributor.getInfo(commandAt(prefix).firstChild)!!

    private fun tooltipAt(prefix: String): String? = infoAt(prefix).tooltipProvider!!.apply(commandAt(prefix).firstChild)

    fun testIconsFollowTheVerdict() {
        myFixture.configureByText("model.als", model)
        recordAt("run show", Expectation.Passed, state = CommandState.InstanceFound)
        recordAt("check noCycles", Expectation.Failed)
        recordAt("run { some Node }", null, Vacuity.VacuousFacts(3, 2))
        recordAt("run first", null, state = CommandState.InstanceFound)

        assertSame(AllIcons.RunConfigurations.TestState.Green2, infoAt("run show").icon)
        assertEquals("Run show — passed last time", tooltipAt("run show"))
        assertSame(AllIcons.RunConfigurations.TestState.Red2, infoAt("check noCycles").icon)
        assertEquals("Run noCycles — failed last time", tooltipAt("check noCycles"))
        assertSame(AllIcons.RunConfigurations.TestState.Yellow2, infoAt("run { some Node }").icon)
        assertEquals("Run command 3 — passed vacuously last time", tooltipAt("run { some Node }"))
        assertSame(AllIcons.RunConfigurations.TestState.Run, infoAt("run first").icon)
        assertEquals("Run first", tooltipAt("run first"))
    }

    fun testVacuousWinsOverAPassedExpectation() {
        myFixture.configureByText("model.als", model)
        recordAt("check noCycles", Expectation.Passed, Vacuity.VacuousPremise("some succ", 3, 2))
        assertSame(AllIcons.RunConfigurations.TestState.Yellow2, infoAt("check noCycles").icon)
        recordAt("check noCycles", Expectation.Passed, Vacuity.Unknown("stopped"))
        assertSame(AllIcons.RunConfigurations.TestState.Green2, infoAt("check noCycles").icon)
    }

    fun testVerdictIconStaysWhenOutdated() {
        myFixture.configureByText("model.als", model)
        recordAt("run show", Expectation.Passed, state = CommandState.InstanceFound)
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.textLength)
        myFixture.type("\n// edited")
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertSame(AllIcons.RunConfigurations.TestState.Green2, infoAt("run show").icon)
        assertEquals("Run show — passed last time (outdated)", tooltipAt("run show"))
    }

    fun testSpinnerWhileCheckingVacuity() {
        myFixture.configureByText("model.als", model)
        recordAt("check noCycles", Expectation.Passed, Vacuity.Checking)
        val info = infoAt("check noCycles")
        assertSame(AnimatedIcon.Default.INSTANCE, info.icon)
        assertInstanceOf(info.actions.single(), AlloyRunningCommandMenuAction::class.java)
    }

    fun testClickingAVerdictIconRunsTheCommandAgain() {
        myFixture.configureByText("model.als", model)
        val file = myFixture.file.virtualFile
        recordAt("check noCycles", Expectation.Failed)
        assertSame(AllIcons.RunConfigurations.TestState.Red2, infoAt("check noCycles").icon)

        val action = infoAt("check noCycles").actions.single()
        assertEquals("Run noCycles", action.templatePresentation.text)
        val context = SimpleDataContext.builder().add(CommonDataKeys.EDITOR, myFixture.editor).build()
        action.actionPerformed(TestActionEvent.createTestEvent(action, context))
        waitUntilIdle(project)

        val result = results.get(file, AlloyCommandRef("noCycles", 1))!!
        assertEquals(result.toString(), CommandState.NoCounterexample, result.state)
        assertEquals("Executing \"Check noCycles for 5\"", result.details.log[2].text)
        // Without `expect`, a fresh pass shows the run icon again
        assertSame(AllIcons.RunConfigurations.TestState.Run, infoAt("check noCycles").icon)
    }

    private fun commandAt(prefix: String): AlloyCommandDecl =
        PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(model.indexOf(prefix)), AlloyCommandDecl::class.java)!!
}
