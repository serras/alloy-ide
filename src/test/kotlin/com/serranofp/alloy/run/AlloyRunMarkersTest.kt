package com.serranofp.alloy.run

import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import com.serranofp.alloy.AlloyLanguage
import com.serranofp.alloy.lsp.CommandsListResult
import com.serranofp.alloy.psi.AlloyCommandDecl

class AlloyRunMarkersTest : BasePlatformTestCase() {
    private val model = """
        sig Node { next: lone Node }
        pred show { some next }
        run show for 3
        fact { no n: Node | n in n.^next }
        check noCycles { no iden & ^next } for 5
        run { some Node } for 2
        run first => check second
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
            service.connectionProvider = { null }
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
        val uri = "file://${file.path}"
        val connection = FakeServerConnection(
            service,
            CommandsListResult(
                listOf(
                    item("Run show for 3", uri, 0, 2),
                    item("Check noCycles for 5", uri, 1, 4),
                    item("Run run\$3 for 2", uri, 2, 5),
                    item("Run first", uri, 3, 6),
                ),
            ),
        )
        connection.onExecute = { capturedOutput().subList(8, 16).forEach(service::executionOutput) }
        service.connectionProvider = { connection }

        val action = contributor.getInfo(commandAt("check noCycles").firstChild)!!.actions.single()
        assertEquals("Run noCycles", action.templatePresentation.text)
        val context = SimpleDataContext.builder().add(CommonDataKeys.EDITOR, myFixture.editor).build()
        action.actionPerformed(TestActionEvent.createTestEvent(action, context))
        val deadline = System.currentTimeMillis() + 10_000
        while (service.activeExecution != null) {
            assertTrue(System.currentTimeMillis() < deadline)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(10)
        }
        UIUtil.dispatchAllInvocationEvents()

        assertEquals(listOf(listOf<Any?>(uri, 1, 4, 0)), connection.executed)
        assertEquals(listOf(AlloyCommandRef("noCycles", 1)), results.results(file).keys.toList())
        assertEquals(CommandState.CounterexampleFound, results.get(file, AlloyCommandRef("noCycles", 1))!!.state)
    }

    private fun commandAt(prefix: String): AlloyCommandDecl =
        PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(model.indexOf(prefix)), AlloyCommandDecl::class.java)!!
}
