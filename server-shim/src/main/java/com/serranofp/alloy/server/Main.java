package com.serranofp.alloy.server;

import kodkod.engine.satlab.SATFactory;
import org.alloytools.alloy.lsp.provider.AlloyLanguageServer;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.prefs.Preferences;

/**
 * Entry point of the language server process started by the IDE.
 *
 * <ul>
 *   <li>{@code serve} (default): runs the Alloy language server over stdin/stdout;</li>
 *   <li>{@code list-solvers}: prints the solvers available on this machine as one line of JSON.</li>
 * </ul>
 */
public final class Main {
    private static final String FACTORY_PROPERTY = "java.util.prefs.PreferencesFactory";

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "serve" : args[0];
        switch (mode) {
            case "serve" -> serve();
            case "list-solvers" -> listSolvers();
            default -> {
                System.err.println("Unknown mode: " + mode + " (expected serve or list-solvers)");
                System.exit(2);
            }
        }
    }

    private static void serve() throws Exception {
        // stdout belongs to the LSP stream, so nothing may be printed before the server takes it over
        installPreferences();
        AlloyLanguageServer.main(new String[0]);
    }

    private static void listSolvers() {
        PrintStream stdout = System.out;
        // Keep anything the analyzer prints while probing solvers out of the JSON output
        System.setOut(System.err);
        String json;
        try {
            installPreferences();
            json = toJson(SATFactory.getAllSolvers().stream().filter(SATFactory::isPresent).toList());
        } finally {
            System.setOut(stdout);
        }
        PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
        out.println(json);
        out.flush();
    }

    /**
     * Makes sure the isolated preference store is in use. The IDE passes the factory on the command line;
     * setting it here only helps if no {@link Preferences} has been used yet, so fail if that is too late.
     */
    private static void installPreferences() {
        if (System.getProperty(FACTORY_PROPERTY) == null) {
            System.setProperty(FACTORY_PROPERTY, IsolatedPreferencesFactory.class.getName());
        }
        if (!(Preferences.userRoot() instanceof MemoryPreferences)) {
            throw new IllegalStateException("Start the server with -Djava.util.prefs.PreferencesFactory="
                    + IsolatedPreferencesFactory.class.getName());
        }
    }

    static String toJson(List<SATFactory> solvers) {
        StringBuilder json = new StringBuilder("[");
        for (SATFactory solver : solvers) {
            if (json.length() > 1) json.append(',');
            json.append("{\"id\":");
            appendString(json, solver.id());
            json.append(",\"name\":");
            appendString(json, solver.name());
            json.append('}');
        }
        return json.append(']').toString();
    }

    private static void appendString(StringBuilder json, String value) {
        json.append('"');
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (c < 0x20) json.append(String.format("\\u%04x", (int) c));
                    else json.append(c);
                }
            }
        }
        json.append('"');
    }
}
