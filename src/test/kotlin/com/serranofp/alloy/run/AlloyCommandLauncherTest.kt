package com.serranofp.alloy.run

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.serranofp.alloy.semantics.AlloyModelService
import java.util.concurrent.TimeUnit

class AlloyCommandLauncherTest : AlloyRunTestCase() {
    private val show = AlloyCommandRef("show", 0)
    private lateinit var execution: AlloyCommandExecution

    private val model = """
        sig A {}
        pred show { some A }
        run show for 3
        assert noCycles { no A }
        check noCycles for 3
        run {} for 2
        pred first {}
        assert second {}
        run first => check second for 2
    """.trimIndent()

    override fun setUp() {
        super.setUp()
        myFixture.configureByText("model.als", model)
    }

    private val file get() = myFixture.file.virtualFile

    /** Launches [command] as the background task does, off the EDT, and waits for its result. */
    private fun launch(command: AlloyCommandRef, solverWarning: String? = null, nonFatalWarnings: Boolean = false, before: () -> Unit = {}): CommandResult =
        launchAndWait(command, AlloyCommandLauncher(project, solverWarning = { solverWarning }, nonFatalWarnings = { nonFatalWarnings })) {
            execution = it
            before()
        }

    private fun CommandResult.executed(): String? = details.log.map { it.text }.firstOrNull { it.startsWith("Executing") }

    fun testExecutesTheCommandFoundByLabel() {
        val result = launch(AlloyCommandRef("noCycles", 1))
        assertEquals("Executing \"Check noCycles for 3\"", result.executed())
        assertEquals(CommandState.CounterexampleFound, result.state)
        assertInstanceOf(result.link, CommandLink.Solution::class.java)
        assertTrue((result.link as CommandLink.Solution).module!!.allCommands.any { it.label == "noCycles" })
        assertNull(service.activeExecution)
    }

    fun testLabelWinsOverAStalePosition() {
        val result = launch(AlloyCommandRef("show", 1))
        assertEquals("Executing \"Run show for 3\"", result.executed())
        assertEquals(CommandState.InstanceFound, result.state)
    }

    fun testUnlabelledCommandIsFoundByPosition() {
        val result = launch(AlloyCommandRef(null, 2))
        assertTrue(result.executed()!!, result.executed()!!.startsWith("Executing \"Run run$"))
        assertTrue(result.executed()!!, result.executed()!!.endsWith(" for 2\""))
    }

    /** The analyzer lists a `=>` chain as one command, positioned at its last part. */
    fun testChainIsFoundByItsLastPart() {
        val result = launch(AlloyCommandRef("first", 3))
        assertNotNull(result.executed())
        assertFalse(result.summary, result.state == CommandState.Failed)
    }

    fun testCommandNotFound() {
        val renamed = AlloyCommandRef("renamed", 0)
        val result = launch(renamed)
        assertEquals(CommandState.Failed, result.state)
        assertEquals("Command not found", result.summary)
        assertEquals("Command renamed was not found in model.als", result.details.failure)
        assertNull(result.executed())
    }

    fun testPositionNotFound() {
        val result = launch(AlloyCommandRef(null, 7))
        assertEquals("Command not found", result.summary)
        assertEquals("Command command 8 was not found in model.als", result.details.failure)
    }

    fun testModelWithErrors() {
        myFixture.configureByText("model.als", "sig A { f: Undefined }\nrun show for 3\n")
        val result = launch(show)
        assertEquals(CommandState.Failed, result.state)
        assertEquals("Model has errors", result.summary)
        assertEquals("model.als has errors, see the problems reported in the editor", result.details.failure)
        assertEmpty(result.details.log)
    }

    fun testAnalyzerFailure() {
        AlloyModelService.getInstance(project).parse = { _, _, _ -> throw IllegalStateException("analyzer bug") }
        myFixture.configureByText("model.als", "sig B {}\nrun show for 3\n")
        val result = launch(show)
        assertEquals("Analysis failed", result.summary)
        assertEquals("The Alloy analyzer failed: analyzer bug", result.details.failure)
    }

    fun testSolverFallbackWarningInDetails() {
        val result = launch(show, solverWarning = "The solver minisat is not available, using sat4j instead\n")
        assertEquals(listOf("The solver minisat is not available, using sat4j instead"), result.details.warnings)
        assertEquals(CommandState.InstanceFound, result.state)
    }

    private val withWarning = "sig A {}\nfact { some A & none }\npred show {}\nrun show for 3\n"

    fun testWarningsAreFatalByDefault() {
        myFixture.configureByText("model.als", withWarning)
        val result = launch(show)
        assertEquals(CommandState.Failed, result.state)
        assertEquals("Analysis failed", result.summary)
        assertEquals("model.als has warnings. ${AlloyCommandLauncher.WARNINGS_ARE_FATAL}", result.details.failure)
        val warning = result.details.log.single { it.text.startsWith("Warning") }
        assertTrue(warning.text, warning.text.startsWith("Warning & is irrelevant"))
        assertNull(result.executed())
    }

    fun testNonFatalWarningsAreShownAndTheCommandRuns() {
        myFixture.configureByText("model.als", withWarning)
        val result = launch(show, nonFatalWarnings = true)
        assertEquals(CommandState.NoInstance, result.state)
        assertEquals(1, result.details.log.count { it.text.startsWith("Warning") })
        assertEquals("Starting the solver...", result.details.log.first().text)
    }

    fun testCommandsSharingALabelRunTheirOwnScope() {
        myFixture.configureByText("model.als", "sig A {}\npred show { some A }\nrun show for 3\nrun show for 5 expect 1\n")
        val second = AlloyCommands.ref(AlloyCommands.commands(myFixture.file)[1])
        val result = launch(second)
        assertEquals("Executing \"Run show for 5 expect 1\"", result.executed())
        assertEquals(Expectation.Passed, result.expectation)
        assertNull(results.get(file, AlloyCommands.ref(AlloyCommands.commands(myFixture.file)[0])))
    }

    /** Launches `run show` with [launcher] off the EDT, and returns what the launch threw. */
    private fun launchThrowing(launcher: AlloyCommandLauncher): Throwable? {
        execution = newExecution(project, file, show)
        service.startRun(execution)
        val thrown = ApplicationManager.getApplication().executeOnPooledThread<Throwable?> {
            try {
                launcher.launch(execution, testRootDisposable)
                null
            } catch (e: Throwable) {
                e
            }
        }.get(30, TimeUnit.SECONDS)
        assertTrue(execution.isFinished())
        assertNull(service.activeExecution)
        return thrown
    }

    fun testExceptionDuringTheLaunchFailsTheExecution() {
        assertNull(launchThrowing(AlloyCommandLauncher(project, options = { throw IllegalStateException("no options") })))
        val result = results.get(file, show)!!
        assertEquals(CommandState.Failed, result.state)
        assertEquals("The Alloy analyzer failed: no options", result.details.failure)
    }

    fun testCancellationDuringTheLaunchStopsTheExecution() {
        assertInstanceOf(launchThrowing(AlloyCommandLauncher(project, options = { throw ProcessCanceledException() })), ProcessCanceledException::class.java)
        assertEquals(CommandState.Stopped, results.get(file, show)!!.state)
    }

    fun testStoppedBeforeTheAnalysisDoesNotStartIt() {
        val result = launch(show) { execution.stop() }
        assertEquals(CommandState.Stopped, result.state)
        assertNull(result.executed())
    }
}
