package com.serranofp.alloy.run

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.concurrent.TimeUnit

class AlloyCommandVerdictTest : BasePlatformTestCase() {
    private val service get() = AlloyRunService.getInstance(project)
    private val results get() = AlloyCommandResults.getInstance(project)

    private val model = """
        sig Node { succ: lone Node }
        pred show { some succ }
        pred other { some succ }
        pred another { some succ }
        assert alsoAcyclic { no n: Node | n in n.^succ }
        assert noSucc { no succ }
        assert acyclic { no n: Node | n in n.^succ }
        fact { no n: Node | n in n.^succ }
        run show for 3 expect 1
        check acyclic for 3 expect 0
        run { some Node and no Node } for 3 expect 1
        check noSucc for 3 expect 1
        run other for 3
        check alsoAcyclic for 3 expect 1
        run another for 3 expect 0
    """.trimIndent()

    override fun setUp() {
        super.setUp()
        results.clear()
        myFixture.configureByText("model.als", model)
    }

    override fun tearDown() {
        try {
            service.activeExecution?.terminate()
        } finally {
            super.tearDown()
        }
    }

    /** Launches the command at [index] as the background task does, off the EDT, and waits for its result. */
    private fun launch(index: Int): CommandResult {
        val command = AlloyCommands.ref(AlloyCommands.commands(myFixture.file)[index])
        val execution = newExecution(project, myFixture.file.virtualFile, command)
        service.startRun(execution)
        ApplicationManager.getApplication().executeOnPooledThread { AlloyCommandLauncher(project).launch(execution, testRootDisposable) }
            .get(30, TimeUnit.SECONDS)
        assertTrue("the execution did not end", execution.waitFor(30_000))
        return results.get(myFixture.file.virtualFile, command)!!
    }

    private fun assertVerdict(verdict: Verdict, expectation: Expectation?, result: CommandResult) {
        assertEquals(result.toString(), expectation, result.expectation)
        assertEquals(result.toString(), verdict, verdict(result))
    }

    fun testInstanceWithExpectOnePasses() {
        val result = launch(0)
        assertEquals(CommandState.InstanceFound, result.state)
        assertVerdict(Verdict.Passed, Expectation.Passed, result)
    }

    fun testNoCounterexampleWithExpectZeroPasses() {
        val result = launch(1)
        assertEquals(CommandState.NoCounterexample, result.state)
        assertVerdict(Verdict.Passed, Expectation.Passed, result)
    }

    fun testNoInstanceWithExpectOneFails() {
        val result = launch(2)
        assertEquals(CommandState.NoInstance, result.state)
        assertVerdict(Verdict.Failed, Expectation.Failed, result)
    }

    fun testCounterexampleWithExpectZeroFails() {
        myFixture.configureByText("model.als", "sig Node { succ: lone Node }\nassert noLoops { no n: Node | n = n.succ }\ncheck noLoops for 3 expect 0\n")
        val result = launch(0)
        assertEquals(CommandState.CounterexampleFound, result.state)
        assertVerdict(Verdict.Failed, Expectation.Failed, result)
    }

    fun testCounterexampleWithExpectOnePasses() {
        val result = launch(3)
        assertEquals(CommandState.CounterexampleFound, result.state)
        assertVerdict(Verdict.Passed, Expectation.Passed, result)
    }

    fun testNoCounterexampleWithExpectOneFails() {
        assertVerdict(Verdict.Failed, Expectation.Failed, launch(5))
    }

    fun testInstanceWithExpectZeroFails() {
        assertVerdict(Verdict.Failed, Expectation.Failed, launch(6))
    }

    fun testWithoutExpectTheVerdictIsRun() {
        val result = launch(4)
        assertEquals(CommandState.InstanceFound, result.state)
        assertVerdict(Verdict.Run, null, result)
    }

    fun testModelWithErrorsFailsItsExpectation() {
        myFixture.configureByText("model.als", "sig A { f: Undefined }\nrun show for 3 expect 1\n")
        val result = launch(0)
        assertEquals("Model has errors", result.summary)
        assertVerdict(Verdict.Failed, Expectation.Failed, result)
    }

    fun testModelWithErrorsWithoutExpectIsRun() {
        myFixture.configureByText("model.als", "sig A { f: Undefined }\nrun show for 3\n")
        assertVerdict(Verdict.Run, null, launch(0))
    }

    fun testStoppedIsRun() {
        val command = AlloyCommands.ref(AlloyCommands.commands(myFixture.file)[0])
        val execution = newExecution(project, myFixture.file.virtualFile, command)
        service.startRun(execution)
        execution.expect(1)
        assertVerdict(Verdict.Run, null, results.get(myFixture.file.virtualFile, command)!!)
        execution.terminate()
        val result = results.get(myFixture.file.virtualFile, command)!!
        assertEquals(CommandState.Stopped, result.state)
        assertVerdict(Verdict.Run, null, result)
    }

    fun testExpectsOfTheExecutedPartOfAChain() {
        myFixture.configureByText(
            "model.als",
            "pred a {}\nassert b {}\nrun a expect 1 => check b\nrun a => check b expect 0\nrun a for 2 expect 3\nrun a expect 0 // expect 1\n",
        )
        assertEquals(listOf(-1, 0, 1, 0), AlloyCommands.commands(myFixture.file).map(AlloyCommands::expects))
    }

    fun testVacuityWinsOverTheExpectation() {
        val passed = CommandResult(CommandState.NoCounterexample, "", documentStamp = 0, settingsGeneration = 0, expectation = Expectation.Passed)
        assertEquals(Verdict.Vacuous, verdict(passed.copy(vacuity = Vacuity.VacuousFacts(1, 2))))
        assertEquals(Verdict.Vacuous, verdict(passed.copy(expectation = null, vacuity = Vacuity.VacuousPremise("A", 1, 2))))
        assertEquals(Verdict.Passed, verdict(passed.copy(vacuity = Vacuity.NotVacuous(1))))
        assertEquals(Verdict.Passed, verdict(passed.copy(vacuity = Vacuity.Unknown("stopped"))))
        assertEquals(Verdict.Run, verdict(passed.copy(expectation = null, vacuity = Vacuity.NotVacuous(1))))
        assertEquals(Verdict.Run, verdict(passed.copy(state = CommandState.Running)))
    }
}
