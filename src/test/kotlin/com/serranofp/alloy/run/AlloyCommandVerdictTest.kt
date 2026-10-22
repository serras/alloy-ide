package com.serranofp.alloy.run

class AlloyCommandVerdictTest : AlloyRunTestCase() {
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
        myFixture.configureByText("model.als", model)
    }

    /** Launches the command whose text starts with [prefix] as the background task does, and waits for its result. */
    private fun launch(prefix: String): CommandResult = launchAndWait(commandRef(prefix))

    private fun assertVerdict(verdict: Verdict, expectation: Expectation?, result: CommandResult) {
        assertEquals(result.toString(), expectation, result.expectation)
        assertEquals(result.toString(), verdict, verdict(result))
    }

    fun testInstanceWithExpectOnePasses() {
        val result = launch("run show")
        assertEquals(CommandState.InstanceFound, result.state)
        assertVerdict(Verdict.Passed, Expectation.Passed, result)
    }

    fun testNoCounterexampleWithExpectZeroPasses() {
        val result = launch("check acyclic")
        assertEquals(CommandState.NoCounterexample, result.state)
        assertVerdict(Verdict.Passed, Expectation.Passed, result)
    }

    fun testNoInstanceWithExpectOneFails() {
        val result = launch("run { some Node and no Node }")
        assertEquals(CommandState.NoInstance, result.state)
        assertVerdict(Verdict.Failed, Expectation.Failed, result)
    }

    fun testCounterexampleWithExpectZeroFails() {
        myFixture.configureByText("model.als", "sig Node { succ: lone Node }\nassert noLoops { no n: Node | n = n.succ }\ncheck noLoops for 3 expect 0\n")
        val result = launch("check noLoops")
        assertEquals(CommandState.CounterexampleFound, result.state)
        assertVerdict(Verdict.Failed, Expectation.Failed, result)
    }

    fun testCounterexampleWithExpectOnePasses() {
        val result = launch("check noSucc")
        assertEquals(CommandState.CounterexampleFound, result.state)
        assertVerdict(Verdict.Passed, Expectation.Passed, result)
    }

    fun testNoCounterexampleWithExpectOneFails() {
        assertVerdict(Verdict.Failed, Expectation.Failed, launch("check alsoAcyclic"))
    }

    fun testInstanceWithExpectZeroFails() {
        assertVerdict(Verdict.Failed, Expectation.Failed, launch("run another"))
    }

    fun testWithoutExpectTheVerdictIsRun() {
        val result = launch("run other")
        assertEquals(CommandState.InstanceFound, result.state)
        assertVerdict(Verdict.Run, null, result)
    }

    fun testModelWithErrorsFailsItsExpectation() {
        myFixture.configureByText("model.als", "sig A { f: Undefined }\nrun show for 3 expect 1\n")
        val result = launch("run show")
        assertEquals("Model has errors", result.summary)
        assertVerdict(Verdict.Failed, Expectation.Failed, result)
    }

    fun testModelWithErrorsWithoutExpectIsRun() {
        myFixture.configureByText("model.als", "sig A { f: Undefined }\nrun show for 3\n")
        assertVerdict(Verdict.Run, null, launch("run show"))
    }

    fun testStoppedIsRun() {
        val command = commandRef("run show")
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
