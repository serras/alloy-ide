package com.serranofp.alloy.viewer

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.serranofp.alloy.viewer.EvaluationResult.Tuples
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AlloyEvaluatorRunnerTest : BasePlatformTestCase() {
    private fun dispatchUntil(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (!condition()) {
            assertTrue(message, System.currentTimeMillis() < deadline)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(10)
        }
    }

    fun testOnlyTheLatestStateIsDelivered() {
        val link = link(LASSO)
        val model = AlloySolutionModel(link.solution, module = link.module)
        val runner = AlloyEvaluatorRunner(testRootDisposable)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
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

        dispatchUntil("the state 1 value did not arrive") { delivered.isNotEmpty() }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals(listOf(0, 1), synchronized(evaluated) { evaluated.toList() })
        assertEquals(listOf(Tuples(listOf("V2$0"))), delivered)
    }

    fun testDisposedRunnerDeliversNothing() {
        val link = link(STATIC)
        val parent = Disposer.newDisposable()
        val runner = AlloyEvaluatorRunner(parent)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        runner.evaluate = { m, text, state ->
            started.countDown()
            release.await(30, TimeUnit.SECONDS)
            m.evaluate(text, state).also { done.countDown() }
        }
        val delivered = mutableListOf<EvaluationResult>()
        runner.submit(AlloySolutionModel(link.solution, module = link.module), "succ") { delivered += it }
        assertTrue(started.await(30, TimeUnit.SECONDS))
        Disposer.dispose(parent)
        release.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS))
        Thread.sleep(50)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEmpty(delivered)
    }
}
