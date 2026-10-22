package com.serranofp.alloy.run

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.util.ui.UIUtil
import com.serranofp.alloy.analysis.AlloyAnalysisEngine
import com.serranofp.alloy.analysis.AlloyAnalysisEvent
import com.serranofp.alloy.settings.AlloySettings
import edu.mit.csail.sdg.alloy4.A4Reporter
import edu.mit.csail.sdg.parser.CompUtil
import edu.mit.csail.sdg.translator.A4Options
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** An execution of [command] in [file] that analyzes the current document and settings, as a launch does. */
fun newExecution(project: Project, file: VirtualFile, command: AlloyCommandRef = AlloyCommandRef("show", 0)): AlloyCommandExecution =
    AlloyCommandExecution(
        AlloyRunService.getInstance(project),
        AlloyCommandResults.getInstance(project),
        file,
        command,
        AlloyCommandResults.getInstance(project).stamp(FileDocumentManager.getInstance().getDocument(file)!!),
        AlloySettings.getInstance().generation,
    )

/** Executes the first command of [model] and returns every event of the analysis. */
fun analysisEvents(model: String): List<AlloyAnalysisEvent> {
    val module = CompUtil.parseEverything_fromString(A4Reporter.NOP, model)
    val events = CopyOnWriteArrayList<AlloyAnalysisEvent>()
    val disposable = Disposer.newDisposable()
    try {
        val analysis = AlloyAnalysisEngine.getInstance()
            .execute("test", disposable, module.allReachableSigs, module.allCommands.first(), A4Options()) { events += it }
        check(analysis.await(30, TimeUnit.SECONDS)) { "the analysis did not end" }
    } finally {
        Disposer.dispose(disposable)
    }
    return events
}

/** A real instance, for results that link to one. */
fun instanceLink(): CommandLink.Solution =
    CommandLink.Solution((analysisEvents("sig A {}\nrun { some A } for 1").last() as AlloyAnalysisEvent.Sat).solution)

/** Options without symmetry breaking, under which [PIGEONHOLE] solves for more than 20 s. */
fun slowOptions(): A4Options = A4Options().apply { symmetry = 0 }

/** Its `run show` solves for more than 20 s under [slowOptions]. */
const val PIGEONHOLE = "sig H {}\nsig P { h: one H }\nfact { all disj a, b: P | a.h != b.h }\npred show { #P = 11 and #H = 10 }\nrun show for 11"

/** Its `run show` translates for several seconds, without any analyzer callback after the `Translating` one. */
const val HEAVY_TRANSLATION =
    "sig A { r: A -> A }\npred show { all a, b, c, d: A | a->b->c in r implies (b->c->d in r or c->d->a in r) }\nrun show for 32"

/** Whether [stack] is that of a thread in SAT4J's search. */
fun isSearching(stack: Array<StackTraceElement>): Boolean = stack.any { it.className.startsWith("org.sat4j.") && it.methodName == "search" }

/**
 * Whether an analysis is in SAT4J's search, where a stop takes effect. Its `Solving` event, and the
 * "Solving..." progress, come just before, when a stop is not yet seen by the solver.
 */
fun analysisIsSearching(): Boolean =
    Thread.getAllStackTraces().any { (thread, stack) -> thread.name.startsWith("Alloy analysis: ") && isSearching(stack) }

/** Waits for [project]'s execution to end, dispatching the EDT events meanwhile, as the task may run on it. */
fun waitUntilIdle(project: Project) {
    val service = AlloyRunService.getInstance(project)
    val deadline = System.currentTimeMillis() + 30_000
    while (service.activeExecution != null) {
        check(System.currentTimeMillis() < deadline) { "execution did not finish" }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        Thread.sleep(10)
    }
    UIUtil.dispatchAllInvocationEvents()
}

/** Waits until [condition] holds, dispatching the EDT events meanwhile. */
fun waitUntil(message: String, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + 30_000
    while (!condition()) {
        check(System.currentTimeMillis() < deadline) { message }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        Thread.sleep(10)
    }
}

/**
 * Ends the execution that a test left running in [project], and releases the project's analysis if a suite
 * or an enumeration still holds it; that fails, as only a test that lost track of one leaves it held.
 * `AlloyRunService` has no reset of its own, so its fields are reached by reflection.
 */
fun releaseRunService(project: Project) {
    val service = AlloyRunService.getInstance(project)
    service.activeExecution?.terminate()
    val leaks = mutableListOf<String>()
    synchronized(service) {
        val enumerating = AlloyRunService::class.java.getDeclaredField("enumerating").apply { isAccessible = true }
        if (enumerating.getBoolean(service)) {
            leaks += "an enumeration"
            enumerating.setBoolean(service, false)
        }
        val suite = AlloyRunService::class.java.getDeclaredField("suite").apply { isAccessible = true }
        if (suite.get(service) != null) {
            leaks += "a suite"
            suite.set(service, null)
        }
    }
    service.activeExecution?.let {
        leaks += "an execution that started meanwhile"
        it.terminate()
    }
    check(leaks.isEmpty()) { "The test left ${leaks.joinToString(" and ")} holding the project's analysis" }
}
