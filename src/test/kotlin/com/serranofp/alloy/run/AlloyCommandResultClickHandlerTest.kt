package com.serranofp.alloy.run

import com.intellij.codeInsight.hints.declarative.InlayActionHandler
import com.intellij.codeInsight.hints.declarative.StringInlayActionPayload
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import com.serranofp.alloy.settings.AlloySettings

class AlloyCommandResultClickHandlerTest : BasePlatformTestCase() {
    private val service get() = AlloyRunService.getInstance(project)
    private val results get() = AlloyCommandResults.getInstance(project)
    private val show = AlloyCommandRef("show", 0)
    private val noCycles = AlloyCommandRef("noCycles", 1)
    private lateinit var connection: FakeServerConnection

    override fun setUp() {
        super.setUp()
        results.clear()
        connection = FakeServerConnection(service)
        myFixture.configureByText("model.als", "run show for 3\ncheck noCycles for 3\n")
    }

    override fun tearDown() {
        try {
            service.activeExecution?.terminate()
        } finally {
            super.tearDown()
        }
    }

    private val file get() = myFixture.file.virtualFile

    private fun record(command: AlloyCommandRef, state: CommandState, summary: String, link: String?) =
        results.record(
            file,
            command,
            CommandResult(state, summary, link = link, documentStamp = results.stamp(myFixture.editor.document), settingsGeneration = AlloySettings.getInstance().generation),
        )

    private fun click(command: AlloyCommandRef, server: AlloyServerConnection? = connection) =
        AlloyCommandResultClickHandler { server }
            .click(myFixture.editor, StringInlayActionPayload(AlloyCommandResultHintsProvider.encode(command)))

    fun testHandlerIsRegistered() {
        assertInstanceOf(
            InlayActionHandler.getActionHandler(AlloyCommandResultClickHandler.HANDLER_ID),
            AlloyCommandResultClickHandler::class.java,
        )
    }

    fun testInstanceClickOpensTheInstance() {
        record(show, CommandState.InstanceFound, "Instance found · 4 ms", "XML: /tmp/0.cnf.xml")
        click(show)
        assertEquals(listOf("XML: /tmp/0.cnf.xml"), connection.opened)
    }

    fun testCoreClickOpensTheCore() {
        record(noCycles, CommandState.NoCounterexample, "No counterexample found · 3 ms", "CORE: /tmp/core.cnf")
        click(noCycles)
        assertEquals(listOf("CORE: /tmp/core.cnf"), connection.opened)
    }

    fun testOutdatedInstanceStillOpens() {
        record(show, CommandState.InstanceFound, "Instance found · 4 ms", "XML: /tmp/0.cnf.xml")
        myFixture.type("x")
        assertTrue(results.isOutdated(results.get(file, show)!!, myFixture.editor.document))
        click(show)
        assertEquals(listOf("XML: /tmp/0.cnf.xml"), connection.opened)
    }

    fun testRunningClickSendsOneStop() {
        val execution = newExecution(project, file, show)
        service.startRun(execution)
        execution.connection = connection
        click(show)
        UIUtil.dispatchAllInvocationEvents()
        assertEquals(1, connection.stops)
        assertTrue(execution.isFinished())
        assertEquals(CommandState.Stopped, results.get(file, show)!!.state)
        assertTrue(connection.opened.isEmpty())
    }

    fun testResultWithoutLinkDoesNothing() {
        record(noCycles, CommandState.NoCounterexample, "No counterexample found · 3 ms", null)
        click(noCycles)
        assertTrue(connection.opened.isEmpty())
        assertEquals(0, connection.stops)
    }

    fun testNoServerShowsAnErrorInsteadOfOpening() {
        record(show, CommandState.InstanceFound, "Instance found · 4 ms", "XML: /tmp/0.cnf.xml")
        click(show, server = null)
        assertTrue(connection.opened.isEmpty())
    }
}
