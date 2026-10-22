package com.serranofp.alloy.analysis

import kodkod.engine.config.ExtendedOptions
import kodkod.engine.satlab.ExternalSolver
import kodkod.engine.satlab.SATFactory
import kodkod.engine.satlab.SATSolver
import org.sat4j.specs.ISolver
import java.io.Serializable
import java.lang.reflect.Field
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Stands for [original] in `A4Options.solver`, so that the solvers Kodkod creates can be stopped (see the
 * spike results in the design, Decision 8): the `ISolver` inside the SAT4J wrappers is told to expire, and
 * external solver processes, which pardinus keeps out of reach, are destroyed. A factory that [original]
 * chooses for some options (`doOptions`) is wrapped too, and stops with this one.
 */
internal class StoppableSatFactory private constructor(
    private val original: SATFactory,
    private val state: State,
) : SATFactory() {
    constructor(original: SATFactory) : this(original, State())

    /** What this factory and those it wraps for other options share: one analysis's solvers. */
    private class State : Serializable {
        @Volatile
        var stopped = false

        /** The processes that ran before this analysis started, which cannot be its solvers. */
        @Volatile
        var before: Set<Long> = children()

        val solvers: MutableList<ISolver> = CopyOnWriteArrayList()

        /** The CNF files of the external solvers, which each passes to its process as its last argument. */
        val cnfFiles: MutableList<String> = CopyOnWriteArrayList()

        /** The executables of the factories, for the processes that no CNF file identifies. */
        val executables: MutableSet<String> = ConcurrentHashMap.newKeySet()

        companion object {
            private const val serialVersionUID = 1L
        }
    }

    init {
        state.executables += original.executables.mapNotNull(::fileName)
    }

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
        return if (chosen === original) this else StoppableSatFactory(chosen, state)
    }

    override fun createSolver(): SATSolver {
        val solver = original.instance()
        sat4jSolver(solver)?.let {
            state.solvers += it
            if (state.stopped) it.expireTimeout()
        }
        if (solver is ExternalSolver) cnfFile(solver)?.let { state.cnfFiles += it }
        return solver
    }

    /**
     * Makes the solvers stoppable again for another analysis. An enumeration (`A4Solution.fork`) solves again
     * with the solver of the analysis that found the solution, and so with this factory.
     */
    fun rearm() {
        state.stopped = false
        state.before = children()
    }

    fun stop() {
        state.stopped = true
        // SAT4J 2.3.1 then fails inside the analysis with a NullPointerException, which still ends it at once
        state.solvers.forEach { runCatching { it.expireTimeout() } }
        destroySolverProcesses()
    }

    /**
     * The solver processes of this analysis. Kodkod runs them as children of the IDE (a process started from
     * the IDE's terminal is a grandchild), so only children are candidates. A process of one of the external
     * solvers is known by its CNF file, which is unique to it. A process that nothing identifies, such as
     * Electrod's, runs one of the executables (either directly or as a script: then the process is the
     * interpreter, and the script is its first argument) and did not run yet when this analysis started.
     * That one can still belong to another analysis that started later and runs the same executable.
     */
    private fun destroySolverProcesses() {
        val cnfFiles = state.cnfFiles.toSet()
        ProcessHandle.current().children()
            .filter { process ->
                val info = process.info()
                val arguments = info.arguments().orElse(null).orEmpty()
                if (arguments.any { it in cnfFiles }) return@filter true
                if (cnfFiles.isNotEmpty()) return@filter false
                val program = listOfNotNull(info.command().orElse(null), arguments.firstOrNull())
                process.pid() !in state.before && program.any { fileName(it) in state.executables }
            }
            .forEach { it.destroyForcibly() }
    }

    companion object {
        private const val serialVersionUID = 1L

        private fun children(): Set<Long> = ProcessHandle.current().children().map { it.pid() }.toList().toSet()

        private fun fileName(path: String): String? = runCatching { Path.of(path).fileName?.toString() }.getOrNull()

        /** The `ISolver` that a Kodkod SAT4J wrapper (`SAT4J`, `PMaxSAT4J`) keeps in a private field. */
        fun sat4jSolver(solver: SATSolver): ISolver? =
            solverField(solver.javaClass)?.let { runCatching { it.get(solver) as? ISolver }.getOrNull() }

        private fun solverField(type: Class<*>): Field? =
            generateSequence(type) { it.superclass }
                .flatMap { it.declaredFields.asSequence() }
                .firstOrNull { ISolver::class.java.isAssignableFrom(it.type) }
                ?.also { it.isAccessible = true }

        /** The file an `ExternalSolver` writes its CNF to, and gives its process; private in pardinus. */
        private fun cnfFile(solver: ExternalSolver): String? = runCatching {
            ExternalSolver::class.java.getDeclaredField("inTemp").apply { isAccessible = true }.get(solver) as? String
        }.getOrNull()
    }
}
