/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.pragmatica.http.server;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;


/// Runs [StreamingIngestProbe] in a child JVM limited to [#HEAP_MB] of heap and of direct memory, and returns what it
/// printed. Mirrors the storage module's `LargeObjectRun`.
final class StreamingIngestRun {
    static final int HEAP_MB = 128;

    private StreamingIngestRun() {}

    record Outcome(int exitCode, Map<String, String> values, String output) {
        String get(String key) {
            return values.getOrDefault(key, "<missing>");
        }
    }

    static Outcome run(String protocol, long size, long timeoutMinutes) throws Exception {
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var process = new ProcessBuilder(java,
                                         "-Xmx" + HEAP_MB + "m",
                                         "-XX:MaxDirectMemorySize=" + HEAP_MB + "m",
                                         "-cp",
                                         System.getProperty("java.class.path"),
                                         StreamingIngestProbe.class.getName(),
                                         protocol,
                                         Long.toString(size)).redirectErrorStream(true).start();
        var lines = new ArrayList<String>();

        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            var reading = Thread.ofVirtual().start(() -> reader.lines().forEach(line -> {
                synchronized (lines) {
                    lines.add(line);
                }
            }));

            if (!process.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                synchronized (lines) {
                    lines.add("TIMEOUT after " + timeoutMinutes + " minutes");
                }
            }

            reading.join(10_000);
        }

        var values = new HashMap<String, String>();

        synchronized (lines) {
            lines.stream().filter(line -> line.matches("[A-Z_]+=.*")).forEach(line -> values.put(line.substring(0, line.indexOf('=')),
                                                                                                   line.substring(line.indexOf('=') + 1)));

            return new Outcome(process.isAlive() ? -1 : process.exitValue(), values, String.join("\n", lines));
        }
    }
}
