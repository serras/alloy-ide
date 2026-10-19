package com.serranofp.alloy.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class ServeTest {
    private static final String HEADER = "Content-Length: ";

    @Test(timeout = 60_000)
    public void answersInitializeWithCapabilities() throws Exception {
        File stderr = File.createTempFile("alloy-shim", ".log");
        stderr.deleteOnExit();
        Process process = ShimProcess.start(stderr);
        try {
            send(process.getOutputStream(), """
                    {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"processId":null,"rootUri":null,"capabilities":{}}}""");

            JsonObject response;
            do {
                response = read(process.getInputStream(), Files.readString(stderr.toPath()));
            } while (!response.has("id"));

            assertEquals(1, response.get("id").getAsInt());
            JsonObject capabilities = response.getAsJsonObject("result").getAsJsonObject("capabilities");
            assertTrue(capabilities.toString(), capabilities.has("hoverProvider"));
            assertTrue(capabilities.toString(), capabilities.has("definitionProvider"));
            assertTrue(capabilities.toString(), capabilities.has("renameProvider"));
        } finally {
            process.destroy();
            if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly();
        }
    }

    private static void send(OutputStream out, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        out.write((HEADER + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    /** Reads one message, failing if stdout carries anything but LSP framing. */
    private static JsonObject read(InputStream in, String stderr) throws IOException {
        String header = readHeader(in);
        assertTrue("stdout must start with an LSP header, got: " + header + "\nstderr:\n" + stderr, header.startsWith(HEADER));
        int length = Integer.parseInt(header.substring(HEADER.length()).trim());
        byte[] body = in.readNBytes(length);
        assertEquals(length, body.length);
        return JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static String readHeader(InputStream in) throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        while (!header.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) {
            int b = in.read();
            if (b < 0) throw new IOException("Server closed stdout after: " + header.toString(StandardCharsets.US_ASCII));
            header.write(b);
        }
        return header.toString(StandardCharsets.US_ASCII);
    }
}
