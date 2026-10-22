package com.serranofp.alloy.viewer

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.serranofp.alloy.run.waitUntil
import com.serranofp.alloy.viewer.EvaluationResult.Tuples
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class AlloyEvaluatorRunnerTest : BasePlatformTestCase() {
    /** Released when the test ends, also when it fails, so that no evaluator thread stays blocked. */
    private val release = CountDownLatch(1)

    override fun tearDown() {
        try {
            release.countDown()
        } finally {
            super.tearDown()
        }
    }

    private fun model(text: String) = link(text).let { AlloySolutionModel(it.solution, module = it.module) }

    fun testOnlyTheLatestStateIsDelivered() {
        val model = model(LASSO)
        val runner = AlloyEvaluatorRunner(testRootDisposable)
        val started = CountDownLatch(1)
        val evaluated = mutableListOf<Int>()
        runner.evaluate = { m, text, state ->
            if (state == 0) {
                started.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
            synchronized(evaluated) { evaluated += state }
            m.evaluate(text, state)
        }
        val delivered = mutableListOf<EvaluationResult>()

        runner.submit(model, "Cur") { delivered += it }
        assertTrue(started.await(30, TimeUnit.SECONDS))
        model.goTo(1)
        runner.invalidate()
        runner.submit(model, "Cur") { delivered += it }
        release.countDown()

        waitUntil("the state 1 value did not arrive") { delivered.isNotEmpty() }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        // A Kodkod evaluation cannot be interrupted: the one running when it is superseded ends, and is dropped
        assertEquals(listOf(0, 1), synchronized(evaluated) { evaluated.toList() })
        assertEquals(listOf(Tuples(listOf("V2$0"))), delivered)
    }

    fun testSupersededRequestsThatWaitAreSkipped() {
        val model = model(LASSO)
        val runner = AlloyEvaluatorRunner(testRootDisposable)
        val started = CountDownLatch(1)
        val evaluated = mutableListOf<String>()
        runner.evaluate = { m, text, state ->
            if (text == "first") {
                started.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
            synchronized(evaluated) { evaluated += text }
            m.evaluate("Cur", state)
        }
        val delivered = mutableListOf<String>()
        runner.submit(model, "first") { delivered += "first" }
        assertTrue(started.await(30, TimeUnit.SECONDS))
        // Queued behind the first, then superseded before their turn
        for (i in 1..5) runner.submit(model, "waiting $i") { delivered += "waiting $i" }
        runner.invalidate()
        runner.submit(model, "latest") { delivered += "latest" }
        release.countDown()

        waitUntil("the latest value did not arrive") { delivered.isNotEmpty() }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals(listOf("first", "latest"), synchronized(evaluated) { evaluated.toList() })
        assertEquals(listOf("latest"), delivered)
    }

    fun testAThrowingEvaluationDeliversAnError() {
        val runner = AlloyEvaluatorRunner(testRootDisposable)
        runner.evaluate = { _, _, _ -> throw StackOverflowError() }
        val delivered = mutableListOf<EvaluationResult>()
        runner.submit(model(STATIC), "succ") { delivered += it }
        waitUntil("no result arrived") { delivered.isNotEmpty() }
        val error = delivered.single() as EvaluationResult.Error
        assertTrue(error.message, error.message.contains("StackOverflowError"))
    }

    fun testEvaluationsHaveALargeStack() {
        val runner = AlloyEvaluatorRunner(testRootDisposable)
        // As deep as the analysis thread allows, far beyond a pool thread's default stack
        fun depth(n: Int): Int = if (n == 0) 0 else 1 + depth(n - 1)
        runner.evaluate = { _, _, _ -> EvaluationResult.IntValue(depth(DEEP).toString()) }
        val delivered = mutableListOf<EvaluationResult>()
        runner.submit(model(STATIC), "deep") { delivered += it }
        waitUntil("no result arrived") { delivered.isNotEmpty() }
        assertEquals(EvaluationResult.IntValue(DEEP.toString()), delivered.single())
    }

    /**
     * A runner disposed while it evaluates "running", with "waiting" queued behind it: neither delivers. Disposing
     * the runner shuts its executor down, so its thread ends once it has run every request it took and posted what
     * they deliver: joining the thread waits for those posts, which are then dispatched.
     */
    fun testDisposedRunnerDeliversNothing() {
        val model = model(STATIC)
        val parent = Disposer.newDisposable()
        val runner = AlloyEvaluatorRunner(parent)
        val thread = AtomicReference<Thread>()
        val started = CountDownLatch(1)
        val evaluated = mutableListOf<String>()
        runner.evaluate = { m, text, state ->
            if (text == "running") {
                thread.set(Thread.currentThread())
                started.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
            synchronized(evaluated) { evaluated += text }
            m.evaluate("succ", state)
        }
        val delivered = mutableListOf<String>()
        runner.submit(model, "running") { delivered += "running" }
        assertTrue(started.await(30, TimeUnit.SECONDS))
        runner.submit(model, "waiting") { delivered += "waiting" }
        Disposer.dispose(parent)
        release.countDown()
        thread.get().join(30_000)
        assertFalse("the evaluator thread did not end", thread.get().isAlive)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        // The one waiting was skipped when its turn came
        assertEquals(listOf("running"), synchronized(evaluated) { evaluated.toList() })
        assertEmpty(delivered)
    }

    fun testADisposedRunnerTakesNoRequest() {
        val parent = Disposer.newDisposable()
        val runner = AlloyEvaluatorRunner(parent)
        val evaluated = mutableListOf<String>()
        runner.evaluate = { m, text, state -> synchronized(evaluated) { evaluated += text }; m.evaluate(text, state) }
        Disposer.dispose(parent)
        val delivered = mutableListOf<EvaluationResult>()
        runner.submit(model(STATIC), "succ") { delivered += it }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEmpty(synchronized(evaluated) { evaluated.toList() })
        assertEmpty(delivered)
    }

    private companion object {
        const val DEEP = 200_000
    }
}
