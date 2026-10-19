package com.serranofp.alloy.settings

import com.google.gson.JsonParser
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.SystemInfo
import com.serranofp.alloy.lsp.AlloyLspClientDescriptor
import com.serranofp.alloy.lsp.AlloyServerCommandLine
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

data class AlloySolver(val id: String, val name: String)

/**
 * The solvers the bundled analyzer reports as present (`list-solvers` in the server shim): the pure-Java
 * SAT4J variants, plus solvers whose executable is on the `PATH`. Asked once per IDE session.
 */
@Service(Service.Level.APP)
class AlloySolvers {
    /** How solvers are found; replaced in tests. */
    internal var discover: () -> List<AlloySolver> = ::runListSolvers

    private var loading: CompletableFuture<List<AlloySolver>>? = null

    /** Starts discovery in the background on first use; later calls get the cached result. */
    @Synchronized
    fun load(): CompletableFuture<List<AlloySolver>> =
        loading ?: CompletableFuture.supplyAsync(
            { runCatching(discover).getOrElse { e -> thisLogger().warn("Could not list the Alloy solvers", e); FALLBACK } },
            { ApplicationManager.getApplication().executeOnPooledThread(it) },
        ).also { loading = it }

    /** The result, if discovery has finished. */
    fun cached(): List<AlloySolver>? = synchronized(this) { loading }?.getNow(null)

    /** Waits for discovery; used off the EDT before starting the server. */
    fun ids(): Set<String> =
        runCatching { load().get(DISCOVERY_TIMEOUT_SECONDS, TimeUnit.SECONDS) }.getOrDefault(FALLBACK).mapTo(mutableSetOf()) { it.id }

    companion object {
        const val DISCOVERY_TIMEOUT_SECONDS = 30L
        val FALLBACK = listOf(AlloySolver(AlloySettingsState.DEFAULT_SOLVER, AlloySettingsState.DEFAULT_SOLVER))

        fun getInstance(): AlloySolvers = service()

        /** Parses the shim's output; anything unexpected gives the SAT4J-only list. */
        fun parse(output: String): List<AlloySolver> = runCatching {
            JsonParser.parseString(output.trim()).asJsonArray.map {
                val solver = it.asJsonObject
                AlloySolver(solver["id"].asString, solver["name"]?.asString ?: solver["id"].asString)
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: FALLBACK

        private fun runListSolvers(): List<AlloySolver> {
            val commandLine = AlloyServerCommandLine.create(
                javaHome = Path.of(System.getProperty("java.home")),
                serverLib = AlloyLspClientDescriptor.serverLib(),
                preferences = emptyMap(),
                isMac = SystemInfo.isMac,
                mode = "list-solvers",
            )
            val output = CapturingProcessHandler(commandLine).runProcess(DISCOVERY_TIMEOUT_SECONDS.toInt() * 1000)
            if (output.isTimeout || output.exitCode != 0) {
                thisLogger().warn("list-solvers failed (exit ${output.exitCode}): ${output.stderr}")
                return FALLBACK
            }
            return parse(output.stdout)
        }
    }
}
