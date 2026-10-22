package com.serranofp.alloy.settings

import kodkod.engine.satlab.SATFactory

/**
 * The JVM-wide `alloy.native.exe.*` properties, where solver discovery records the executables it found:
 * a test saves them all, changes them as it needs, and [restore]s them, so that no test sees what another
 * found, nor what is on the developer's `PATH`.
 */
class SolverProperties {
    /** Every property that discovery may set, one per executable of a solver, and any set before. */
    val names: Set<String> =
        SATFactory.getAllSolvers().flatMap { it.executables.toList() }.mapTo(sortedSetOf()) { "$PREFIX$it" } +
            System.getProperties().stringPropertyNames().filter { it.startsWith(PREFIX) }

    private val saved = names.associateWith { System.getProperty(it) }

    /** No executable is known yet: discovery looks for each of them. */
    fun clear() = names.forEach(System::clearProperty)

    /** Every executable is known to be absent: discovery does not look for any, on the real `PATH` or elsewhere. */
    fun allAbsent() = names.forEach { System.setProperty(it, ABSENT) }

    fun restore() {
        for ((name, value) in saved) {
            if (value == null) System.clearProperty(name) else System.setProperty(name, value)
        }
    }

    companion object {
        const val PREFIX = "alloy.native.exe."

        /** How discovery marks an executable that is not on the `PATH`. */
        const val ABSENT = "NO"
    }
}
