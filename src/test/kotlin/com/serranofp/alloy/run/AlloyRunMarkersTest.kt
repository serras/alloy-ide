package com.serranofp.alloy.run

import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.ui.AnimatedIcon
import java.util.concurrent.TimeUnit
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.serranofp.alloy.AlloyLanguage
import com.serranofp.alloy.psi.AlloyCommandDecl

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

    private fun commandAt(prefix: String): AlloyCommandDecl =
        PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(model.indexOf(prefix)), AlloyCommandDecl::class.java)!!
}
