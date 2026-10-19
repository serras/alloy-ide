package com.serranofp.alloy.lsp

import com.intellij.execution.configurations.GeneralCommandLine
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/** Builds the command line of the language server process (see `server-shim`). */
object AlloyServerCommandLine {
    const val MAIN_CLASS = "com.serranofp.alloy.server.Main"
    const val PREFERENCES_FACTORY = "com.serranofp.alloy.server.IsolatedPreferencesFactory"
    const val PREFERENCE_PROPERTY_PREFIX = "alloy.ide.pref."

    /**
     * @param javaHome runtime that runs the server; the IDE passes its own JBR, so no system Java is needed
     * @param serverLib the plugin's `lib/server` directory, which the plugin classloader never loads
     * @param preferences analyzer preference id to value, in the encoding of `A4Preferences`
     * @param mode `serve` for the language server, `list-solvers` for solver discovery
     */
    fun create(
        javaHome: Path,
        serverLib: Path,
        preferences: Map<String, String>,
        isMac: Boolean,
        mode: String = "serve",
    ): GeneralCommandLine {
        val parameters = buildList {
            add("-cp")
            add(serverLib.resolve("*").toString())
            add("-Djava.util.prefs.PreferencesFactory=$PREFERENCES_FACTORY")
            for ((id, value) in preferences.toSortedMap()) {
                add("-D$PREFERENCE_PROPERTY_PREFIX$id=$value")
            }
            // Not headless: the server opens the Alloy visualizer in its own window
            if (isMac) add("-Dapple.awt.application.name=Alloy")
            add(MAIN_CLASS)
            add(mode)
        }
        return GeneralCommandLine(javaHome.resolve("bin").resolve(javaExecutable()).toString())
            .withParameters(parameters)
            .withCharset(StandardCharsets.UTF_8)
    }

    private fun javaExecutable(): String =
        if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
}
