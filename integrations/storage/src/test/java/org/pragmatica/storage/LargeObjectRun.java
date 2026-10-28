package org.pragmatica.storage;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;


/// Runs [LargeObjectProbe] in a child JVM limited to 256 MiB of heap and returns what it printed.
final class LargeObjectRun {
    static final int HEAP_MB = 256;

    private LargeObjectRun() {}

    record Outcome(int exitCode, Map<String, String> values, String output) {
        String get(String key) {
            return values.getOrDefault(key, "<missing>");
        }
    }

    static Outcome run(long size, Path dir, long timeoutMinutes) throws Exception {
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var process = new ProcessBuilder(java,
                                         "-Xmx" + HEAP_MB + "m",
                                         "-cp",
                                         System.getProperty("java.class.path"),
                                         LargeObjectProbe.class.getName(),
                                         Long.toString(size),
                                         dir.toString()).redirectErrorStream(true).start();
        var lines = new ArrayList<String>();

        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            var reading = Thread.ofVirtual().start(() -> reader.lines().forEach(lines::add));

            if (!process.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                lines.add("TIMEOUT after " + timeoutMinutes + " minutes");
            }

            reading.join(10_000);
        }

        var values = new HashMap<String, String>();

        synchronized (lines) {
            lines.stream().filter(line -> line.matches("[A-Z_]+=.*")).forEach(line -> values.put(line.substring(0, line.indexOf('=')),
                                                                                                   line.substring(line.indexOf('=') + 1)));
        }

        return new Outcome(process.isAlive() ? -1 : process.exitValue(), values, String.join("\n", lines));
    }
}
