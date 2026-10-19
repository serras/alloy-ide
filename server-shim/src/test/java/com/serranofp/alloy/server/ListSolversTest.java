package com.serranofp.alloy.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class ListSolversTest {
    @Test(timeout = 60_000)
    public void printsPresentSolversAsJson() throws Exception {
        File stderr = File.createTempFile("alloy-shim", ".log");
        stderr.deleteOnExit();
        Process process = ShimProcess.start(stderr, "list-solvers");
        process.getOutputStream().close();
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        assertEquals(Files.readString(stderr.toPath()), 0, process.exitValue());

        assertEquals("exactly one line of output: " + stdout, 1, stdout.lines().count());
        JsonArray solvers = JsonParser.parseString(stdout).getAsJsonArray();
        List<String> ids = new ArrayList<>();
        for (JsonElement solver : solvers) {
            ids.add(solver.getAsJsonObject().get("id").getAsString());
            assertTrue(solver.toString(), solver.getAsJsonObject().get("name").getAsJsonPrimitive().isString());
        }
        assertTrue(ids.toString(), ids.contains("sat4j"));
    }
}
