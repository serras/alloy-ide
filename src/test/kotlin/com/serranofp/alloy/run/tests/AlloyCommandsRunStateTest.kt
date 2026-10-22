package com.serranofp.alloy.run.tests

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.serranofp.alloy.run.AlloyCommandLauncher
import com.serranofp.alloy.run.AlloyCommandRef
import com.serranofp.alloy.run.AlloyCommandResults
import com.serranofp.alloy.run.AlloyRunService
import com.serranofp.alloy.run.CommandState
import com.serranofp.alloy.run.Vacuity
import com.serranofp.alloy.run.Verdict
import com.serranofp.alloy.run.newExecution
import com.serranofp.alloy.run.slowOptions
import com.serranofp.alloy.run.verdict
import com.serranofp.alloy.run.waitUntil
import edu.mit.csail.sdg.translator.A4Options
import jetbrains.buildServer.messages.serviceMessages.ServiceMessage

class AlloyCommandsRunStateTest : BasePlatformTestCase() {
    private val service get() = AlloyRunService.getInstance(project)
    private val results get() = AlloyCommandResults.getInstance(project)

    private val mixed = """
        sig Node { succ: lone Node }
        fact acyclic { no n: Node | n in n.^succ }
        pred show { some succ }
        assert noSucc { no succ }
        assert noLoops { no iden & succ }
        assert vacuous { all n: Node | n in n.succ implies no Node }
        run show for 3 expect 1
        check noSucc for 3 expect 0
        run empty { some Node and no Node } for 3
        check noLoops for 3
        check vacuous for 3
        run { some Node } for 2
    """.trimIndent()

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

    private class Suite(val handler: AlloyCommandsProcessHandler) {
        val out = StringBuffer()
        val err = StringBuffer()

        @Volatile
        var exitCode: Int? = null

        val messages: List<ServiceMessage>
            get() = out.lines().filter { it.startsWith("##teamcity[") }.map { ServiceMessage.parse(it)!! }

        fun named(name: String) = messages.filter { it.messageName == name }

        fun started() = named("testStarted").map { it.attributes["name"] }

        fun finished() = named("testFinished").map { it.attributes["name"] }

        fun failed() = named("testFailed").associate { it.attributes["name"] to it.attributes["message"] }
    }

    private fun start(model: String, only: List<AlloyCommandRef> = emptyList(), launcher: () -> AlloyCommandLauncher = { AlloyCommandLauncher(project) }): Suite {
        myFixture.configureByText("a.als", model)
        val suite = Suite(AlloyCommandsProcessHandler(project, myFixture.file.virtualFile, only, launcher))
        suite.handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                (if (outputType == ProcessOutputTypes.STDERR) suite.err else suite.out).append(event.text)
            }

            override fun processTerminated(event: ProcessEvent) {
                suite.exitCode = event.exitCode
            }
        })
        suite.handler.startNotify()
        return suite
    }

    private fun Suite.await(): Suite {
        waitUntil("the suite did not end") { exitCode != null }
        return this
    }

    private fun run(model: String, only: List<AlloyCommandRef> = emptyList()) = start(model, only).await()

    fun testEveryCommandIsATestInSourceOrder() {
        val suite = run(mixed)
        val names = listOf("run show", "check noSucc", "run empty", "check noLoops", "check vacuous", "run command 6")
        assertEquals(names, suite.started())
        assertEquals(names, suite.finished())
        assertEquals("6", suite.named("testCount").single().attributes["count"])
        assertEquals(0, suite.exitCode)
        assertNull(service.activeExecution)

        val failed = suite.failed()
        assertEquals(setOf("check noSucc", "check vacuous"), failed.keys)
        assertEquals("Counterexample found, contrary to expectation", failed["check noSucc"])
        assertTrue(failed["check vacuous"]!!, failed["check vacuous"]!!.startsWith("No counterexample found (vacuous). The assertion's premise "))
        assertTrue(failed["check vacuous"]!!, failed["check vacuous"]!!.endsWith(" has no instance within the scope, so the check passed vacuously."))
    }

    fun testNodesHaveTheirLocationDurationAndOutput() {
        val suite = run(mixed)
        val file = myFixture.file.virtualFile
        val started = suite.named("testStarted")
        assertEquals(AlloyTestLocator.url(file, AlloyCommandRef("show", 0)), started[0].attributes["locationHint"])
        assertEquals(AlloyTestLocator.url(file, AlloyCommandRef(null, 5)), started[5].attributes["locationHint"])
        assertTrue(suite.named("testFinished").all { (it.attributes["duration"]?.toLong() ?: -1) >= 0 })

        val outputs = suite.named("testStdOut").associate { it.attributes["name"] to it.attributes["out"]!! }
        assertTrue(outputs["run show"]!!, outputs["run show"]!!.contains("Executing \"Run show for 3 expect 1\""))
        assertTrue(outputs["run empty"]!!, outputs["run empty"]!!.contains("No instance found"))
        assertTrue(outputs["check noLoops"]!!, outputs["check noLoops"]!!.contains("Vacuity check\nThe facts have an instance"))
    }

    fun testResultsAreRecordedAsForGutterRuns() {
        run(mixed)
        val file = myFixture.file.virtualFile
        val show = results.get(file, AlloyCommandRef("show", 0))!!
        assertEquals(CommandState.InstanceFound, show.state)
        assertEquals(Verdict.Passed, verdict(show))
        assertEquals(Verdict.Failed, verdict(results.get(file, AlloyCommandRef("noSucc", 1))!!))
        assertEquals(Verdict.Run, verdict(results.get(file, AlloyCommandRef("empty", 2))!!))
        assertInstanceOf(results.get(file, AlloyCommandRef("noLoops", 3))!!.vacuity, Vacuity.NotVacuous::class.java)
        assertEquals(Verdict.Vacuous, verdict(results.get(file, AlloyCommandRef("vacuous", 4))!!))
        assertFalse(results.isOutdated(show, myFixture.editor.document))
    }

    fun testModelWithErrorsFailsEveryCommand() {
        val suite = run("sig A { f: Undefined }\npred show {}\nrun show for 3\ncheck other for 3\n")
        assertEquals(listOf("run show", "check other"), suite.started())
        assertEquals(mapOf("run show" to "Model has errors", "check other" to "Model has errors"), suite.failed())
        assertTrue(suite.named("testFailed").all { it.attributes["details"] == "a.als has errors, see the problems reported in the editor" })
    }

    fun testBusyProjectRunsNothing() {
        myFixture.configureByText("a.als", mixed)
        val running = newExecution(project, myFixture.file.virtualFile)
        assertTrue(service.startRun(running))
        val suite = run(mixed)
        assertEquals(1, suite.exitCode)
        assertEquals("${AlloyRunService.BUSY_MESSAGE}\n", suite.err.toString())
        assertEmpty(suite.started())
        assertSame(running, service.activeExecution)
    }

    fun testStopInTheMiddleLeavesTheRestUnstarted() {
        val model = "sig H {}\nsig P { h: one H }\nfact { all disj a, b: P | a.h != b.h }\npred first { some P }\n" +
            "pred show { #P = 11 and #H = 10 }\npred last {}\nrun first for 2\nrun show for 11\nrun last for 2"
        val suite = start(model, launcher = { AlloyCommandLauncher(project, options = ::slowOptions) })
        waitUntil("the second command did not start solving") {
            suite.started().size == 2 && service.activeExecution?.log?.progress == "Solving..."
        }
        suite.handler.destroyProcess()
        suite.await()

        assertEquals(listOf("run first", "run show"), suite.started())
        assertEquals(listOf("run first"), suite.finished())
        assertEmpty(suite.failed().keys)
        val file = myFixture.file.virtualFile
        assertEquals("Stopped before completion", results.get(file, AlloyCommandRef("show", 1))!!.summary)
        assertNull(results.get(file, AlloyCommandRef("last", 2)))
        assertNull(service.activeExecution)
        // The suite gave the project's analysis back
        assertNotNull(service.startSuite()?.also(service::suiteFinished))
    }

    fun testRestrictedToRefsRunsOnlyThoseInSourceOrder() {
        val suite = run(mixed, only = listOf(AlloyCommandRef("vacuous", 4), AlloyCommandRef(null, 5), AlloyCommandRef("noSucc", 1)))
        assertEquals(listOf("check noSucc", "check vacuous", "run command 6"), suite.started())
        val file = myFixture.file.virtualFile
        assertNull(results.get(file, AlloyCommandRef("show", 0)))
        assertNull(results.get(file, AlloyCommandRef("empty", 2)))
    }

    fun testCommandsAreFoundByLabelWhenTheyMoved() {
        // As after an edit: a stale position, but the same label
        val suite = run(mixed, only = listOf(AlloyCommandRef("noLoops", 0)))
        assertEquals(listOf("check noLoops"), suite.started())
    }

    private val sharedLabel = "sig A {}\npred show { some A }\nrun show for 3 expect 1\nrun show for 3 but 0 A expect 1\n"

    fun testCommandsSharingALabelAreTestsOfTheirOwn() {
        val suite = run(sharedLabel)
        assertEquals(listOf("run show", "run show"), suite.started())
        val file = myFixture.file.virtualFile
        val second = AlloyCommandRef("show", 1, occurrence = 1)
        assertEquals(AlloyTestLocator.url(file, second), suite.named("testStarted")[1].attributes["locationHint"])
        assertEquals(listOf("No instance found, contrary to expectation"), suite.named("testFailed").map { it.attributes["message"] })
        assertEquals(CommandState.InstanceFound, results.get(file, AlloyCommandRef("show", 0))!!.state)
        assertEquals(CommandState.NoInstance, results.get(file, second)!!.state)
    }

    fun testRerunOfOneOfTheCommandsSharingALabel() {
        val suite = run(sharedLabel, only = listOf(AlloyCommandRef("show", 1, occurrence = 1)))
        assertEquals(1, suite.started().size)
        assertEquals(mapOf("run show" to "No instance found, contrary to expectation"), suite.failed())
    }

    fun testVacuityOutputHasOneSolverStart() {
        val suite = run(mixed)
        val output = suite.named("testStdOut").single { it.attributes["name"] == "check noLoops" }.attributes["out"]!!
        assertEquals(output, 1, output.lines().count { it == "Starting the solver..." })
    }

    fun testExceptionDuringALaunchEndsTheTestAndTheSuiteGoesOn() {
        val suite = start("pred show {}\nrun show for 2\nrun other {} for 2\n", launcher = {
            AlloyCommandLauncher(project, options = { throw IllegalStateException("no options") })
        }).await()
        assertEquals(listOf("run show", "run other"), suite.finished())
        assertEquals(mapOf("run show" to "Analysis failed", "run other" to "Analysis failed"), suite.failed())
        assertNull(service.activeExecution)
    }

    fun testDeletedFileFinishesTheStartedTest() {
        val suite = start("pred show {}\nrun show for 2\nrun other {} for 2\n", launcher = {
            AlloyCommandLauncher(project, options = {
                val file = service.activeExecution!!.file
                ApplicationManager.getApplication().invokeAndWait { WriteAction.run<Exception> { file.delete(this) } }
                A4Options()
            })
        }).await()
        assertEquals(listOf("run show"), suite.started())
        assertEquals(listOf("run show"), suite.finished())
        assertEquals(setOf("run show"), suite.failed().keys)
        assertNull(service.activeExecution)
    }

    fun testDetachingStopsTheSuite() {
        val model = "sig H {}\nsig P { h: one H }\nfact { all disj a, b: P | a.h != b.h }\npred show { #P = 11 and #H = 10 }\nrun show for 11"
        val suite = start(model, launcher = { AlloyCommandLauncher(project, options = ::slowOptions) })
        waitUntil("the command did not start solving") { service.activeExecution?.log?.progress == "Solving..." }
        suite.handler.detachProcess()
        waitUntil("the suite did not end") { suite.handler.isProcessTerminated && service.activeExecution == null }
        assertEquals("Stopped before completion", results.get(myFixture.file.virtualFile, AlloyCommandRef("show", 0))!!.summary)
    }

    /** The Run executor runs the configuration, with the SM test console and *Rerun Failed Tests*. */
    fun testStateAttachesTheTestConsole() {
        myFixture.configureByText("a.als", "pred show {}\nrun show for 2\n")
        val configuration = AlloyCommandsConfigurationType.getInstance().factory.createTemplateConfiguration(project) as AlloyCommandsRunConfiguration
        configuration.fileUrl = myFixture.file.virtualFile.url
        val executor = DefaultRunExecutor.getRunExecutorInstance()
        assertNotNull(ProgramRunner.getRunner(executor.id, configuration))
        val result = AlloyCommandsRunState(configuration, emptyList()).execute(executor, ProgramRunner.getRunner(executor.id, configuration)!!)
        try {
            assertInstanceOf(result.executionConsole, SMTRunnerConsoleView::class.java)
            assertInstanceOf((result as DefaultExecutionResult).restartActions.single(), AlloyRerunFailedAction::class.java)
            result.processHandler.startNotify()
            waitUntil("the suite did not end") { result.processHandler.isProcessTerminated }
            assertEquals(CommandState.InstanceFound, results.get(myFixture.file.virtualFile, AlloyCommandRef("show", 0))!!.state)
        } finally {
            Disposer.dispose(result.executionConsole)
        }
    }
}
