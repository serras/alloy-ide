package com.serranofp.alloy.analysis

import kodkod.engine.config.ExtendedOptions
import kodkod.engine.satlab.SATFactory
import kodkod.engine.satlab.SATSolver
import org.sat4j.specs.ISolver
import java.lang.reflect.Field
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Stands for [original] in `A4Options.solver`, so that the solvers Kodkod creates can be stopped (see the
 * spike results in the design, Decision 8): the `ISolver` inside the SAT4J wrappers is told to expire, and
 * external solver processes, which pardinus keeps out of reach, are destroyed.
 */
internal class StoppableSatFactory private constructor(
    private val original: SATFactory,
    private val solvers: MutableList<ISolver>,
    private val started: Instant,
) : SATFactory() {
    constructor(original: SATFactory) : this(original, CopyOnWriteArrayList(), Instant.now())

    @Volatile
    private var stopped = false

    override fun id(): String = original.id()
    override fun name(): String = original.name()
    override fun type(): String = original.type()
    override fun prover(): Boolean = original.prover()
    override fun incremental(): Boolean = original.incremental()
    override fun unbounded(): Boolean = original.unbounded()
    override fun maxsat(): Boolean = original.maxsat()
    override fun isTransformer(): Boolean = original.isTransformer
    override fun getExecutables(): Array<String> = original.executables
    override fun getLibraries(): Array<String> = original.libraries

    override fun doOptions(options: ExtendedOptions): SATFactory {
        val chosen = original.doOptions(options)
        return if (chosen === original) this else StoppableSatFactory(chosen, solvers, started)
    }

    override fun createSolver(): SATSolver {
        val solver = original.instance()
        sat4jSolver(solver)?.let {
            solvers += it
            if (stopped) it.expireTimeout()
        }
        return solver
    }

    /**
     * Makes the solvers stoppable again for another analysis. An enumeration (`A4Solution.fork`) solves again
     * with the solver of the analysis that found the solution, and so with this factory.
     */
    fun rearm() {
        stopped = false
    }

    fun stop() {
        stopped = true
        // SAT4J 2.3.1 then fails inside the analysis with a NullPointerException, which still ends it at once
        solvers.forEach { runCatching { it.expireTimeout() } }
        destroySolverProcesses()
    }

    /**
     * The processes started since this analysis began that run one of [original]'s executables, either
     * directly or as a script (then the process is the interpreter, and the script is its first argument).
     */
    private fun destroySolverProcesses() {
        val names = original.executables.mapNotNullTo(mutableSetOf(), ::fileName)
        if (names.isEmpty()) return
        ProcessHandle.current().descendants()
            .filter { process ->
                val info = process.info()
                val program = listOfNotNull(info.command().orElse(null), info.arguments().orElse(null)?.firstOrNull())
                program.any { fileName(it) in names } && info.startInstant().map { !it.isBefore(started) }.orElse(true)!!
            }
            .forEach { it.destroyForcibly() }
    }

    private fun fileName(path: String): String? = runCatching { Path.of(path).fileName?.toString() }.getOrNull()

    companion object {
        private const val serialVersionUID = 1L

        /** The `ISolver` that a Kodkod SAT4J wrapper (`SAT4J`, `PMaxSAT4J`) keeps in a private field. */
        fun sat4jSolver(solver: SATSolver): ISolver? =
            solverField(solver.javaClass)?.let { runCatching { it.get(solver) as? ISolver }.getOrNull() }

        private fun solverField(type: Class<*>): Field? =
            generateSequence(type) { it.superclass }
                .flatMap { it.declaredFields.asSequence() }
                .firstOrNull { ISolver::class.java.isAssignableFrom(it.type) }
                ?.also { it.isAccessible = true }
    }
}
