package com.serranofp.alloy.server;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Starts the shim in a fresh JVM, the way the IDE does. */
final class ShimProcess {
    private ShimProcess() {
    }

    static Process start(File stderr, String... args) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add("-Djava.util.prefs.PreferencesFactory=" + IsolatedPreferencesFactory.class.getName());
        command.add("-Djava.awt.headless=true");
        command.add(Main.class.getName());
        command.addAll(List.of(args));
        return new ProcessBuilder(command).redirectError(stderr).start();
    }
}
