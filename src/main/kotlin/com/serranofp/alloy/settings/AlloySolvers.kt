package com.serranofp.alloy.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import kodkod.engine.satlab.SATFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.pathString

data class AlloySolver(val id: String, val name: String)

/**
 * The solvers the bundled analyzer can run here: the pure-Java SAT4J variants, and the external solvers whose
 * executables are on the `PATH` (Yices, Plingeling, Electrod with NuSMV or nuXmv). Native (JNI) solvers are
 * excluded, since they could crash the IDE, and so are those that only write their input to a file. Found once
 * per IDE session.
 */
@Service(Service.Level.APP)
class AlloySolvers {
    /** How solvers are found; replaced in tests. */
    internal var discover: () -> List<AlloySolver> = { findSolvers() }

    private var loading: CompletableFuture<List<AlloySolver>>? = null

    /** Starts discovery in the background on first use; later calls get the cached result. */
    @Synchronized
    fun load(): CompletableFuture<List<AlloySolver>> =
        loading ?: CompletableFuture.supplyAsync(
            { runCatching(discover).getOrElse { e -> thisLogger().warn("Could not list the Alloy solvers", e); FALLBACK }.ifEmpty { FALLBACK } },
            { ApplicationManager.getApplication().executeOnPooledThread(it) },
        ).also { loading = it }

    /** The result, if discovery has finished. */
    fun cached(): List<AlloySolver>? = synchronized(this) { loading }?.getNow(null)

    /** Waits for discovery; used off the EDT before an analysis. */
    fun ids(): Set<String> =
        runCatching { load().get(DISCOVERY_TIMEOUT_SECONDS, TimeUnit.SECONDS) }.getOrDefault(FALLBACK).mapTo(mutableSetOf()) { it.id }

    companion object {
        const val DISCOVERY_TIMEOUT_SECONDS = 30L
        val FALLBACK = listOf(AlloySolver(AlloySettingsState.DEFAULT_SOLVER, AlloySettingsState.DEFAULT_SOLVER))

        fun getInstance(): AlloySolvers = service()

        /** Where pardinus looks for a solver's executable before the `PATH` it got at startup and its JAR. */
        private fun executableProperty(name: String) = "alloy.native.exe.$name"

        /**
         * Points pardinus at the solver executables found on the user's `PATH`: an IDE started from the desktop
         * may not have the shell's `PATH` itself. Executables that are not on it are marked as absent, so that
         * pardinus does not fall back to binaries of its own. A property set on the command line is kept.
         */
        @Synchronized
        internal fun findExecutables(findOnPath: (String) -> String?) {
            for (factory in SATFactory.getAllSolvers()) {
                for (executable in factory.executables) {
                    val property = executableProperty(executable)
                    if (System.getProperty(property) == null) System.setProperty(property, findOnPath(executable) ?: ABSENT)
                }
            }
        }

        private const val ABSENT = "NO"

        private fun onPath(name: String): String? = PathEnvironmentVariableUtil.findInPath(name)?.path

        /** Every solver that can run, as `SATFactory.getSolvers()` would list them once the executables are known. */
        private fun present(findOnPath: (String) -> String?): List<SATFactory> {
            findExecutables(findOnPath)
            return SATFactory.getAllSolvers().filter { it.libraries.isEmpty() && !it.isTransformer && it.isPresent }
        }

        internal fun findSolvers(findOnPath: (String) -> String? = ::onPath): List<AlloySolver> =
            present(findOnPath).map { AlloySolver(it.id(), it.name()) }

        /**
         * The factory of the solver [id], if it can run. Not `SATFactory.find`: its list is computed once, and may
         * predate [findExecutables].
         */
        fun factory(id: String, findOnPath: (String) -> String? = ::onPath): SATFactory? =
            if (id == SATFactory.DEFAULT.id()) SATFactory.DEFAULT else present(findOnPath).firstOrNull { it.id() == id }
    }
}
