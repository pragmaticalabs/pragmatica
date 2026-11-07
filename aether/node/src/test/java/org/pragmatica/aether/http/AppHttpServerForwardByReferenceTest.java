// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;

/// U2 forward by reference ([ForwardByReferenceScenario]): an upload taken on node A for a route node B serves is stored
/// once and forwarded as a handle; B's slice reads the same bytes through it.
///
/// Spec §4 U2: forwarded request bytes < 64 KiB for a 1 GiB upload, and the upload streams with a heap smaller than the
/// body. The 1 GiB run is a child JVM with `-Xmx256m` and `-XX:MaxDirectMemorySize=256m` (both nodes, the client and
/// the slice share that heap), so an ingress that gathers the body, or a forward that carries it, cannot finish.
///
/// Mutations: forward the body (an ingress that aggregates despite `streaming_ingest`) fails the < 64 KiB bound and the
/// child's heap; dropping the handle from the forwarded context answers 500 "no upload handle" on B.
@Timeout(value = 10, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class AppHttpServerForwardByReferenceTest {
    private static final long MIB = 1024L * 1024;
    private static final long FORWARD_BOUND = 64 * 1024;
    private static final int CHILD_HEAP_MB = 256;

    @Test
    void upload_forwardedFromAToB_byReference_readsBackTheSentBytes_inProcess() throws Exception {
        var size = 48 * MIB + 7;
        var outcome = ForwardByReferenceScenario.run(size);

        assertThat(outcome.status()).as("%s", outcome).isEqualTo(200);
        assertThat(outcome.forwards()).as("forwarded once, to B").isEqualTo(1);
        assertThat(outcome.forwardedBytes()).as("the forward carries the handle, not the body").isLessThan(FORWARD_BOUND);
        assertThat(outcome.bodyOnB()).as("B's slice is handed an empty body").isZero();
        assertThat(outcome.handleSize()).isEqualTo(size);
        assertThat(outcome.servedSha()).as("B read the bytes the client sent").isEqualTo(outcome.sentSha());
    }

    @Test
    void oneGiBUpload_forwardedByReference_underA256MiBHeap_inAChildJvm() throws Exception {
        var size = 1024 * MIB;
        var values = runChild(size);
        var diagnostics = values.toString();

        assertThat(Long.parseLong(values.getOrDefault("MAX_HEAP_MB", "0"))).as("the child ran with a small heap: " + diagnostics)
                                                                           .isLessThanOrEqualTo(CHILD_HEAP_MB);
        assertThat(size).as("the body must be larger than the heap, or this proves nothing")
                        .isGreaterThan(4L * CHILD_HEAP_MB * MIB - 1);
        assertThat(values.get("STATUS")).as(diagnostics).isEqualTo("200");
        assertThat(values.get("EXIT")).as(diagnostics).isEqualTo("0");
        assertThat(values.get("FORWARDS")).as(diagnostics).isEqualTo("1");
        assertThat(Long.parseLong(values.getOrDefault("FORWARDED_BYTES", "-1"))).as(diagnostics)
                                                                                .isBetween(1L, FORWARD_BOUND - 1);
        assertThat(values.get("BODY_ON_B")).as(diagnostics).isEqualTo("0");
        assertThat(values.get("HANDLE_SIZE")).as(diagnostics).isEqualTo(Long.toString(size));
        assertThat(values.get("SERVED_SHA")).as(diagnostics).isEqualTo(values.get("SENT_SHA"));
    }

    private static HashMap<String, String> runChild(long size) throws Exception {
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var process = new ProcessBuilder(java,
                                         "-Xmx" + CHILD_HEAP_MB + "m",
                                         "-XX:MaxDirectMemorySize=" + CHILD_HEAP_MB + "m",
                                         "-cp",
                                         System.getProperty("java.class.path"),
                                         ForwardByReferenceScenario.class.getName(),
                                         Long.toString(size)).redirectErrorStream(true).start();
        var lines = new ArrayList<String>();

        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            var reading = Thread.ofVirtual().start(() -> reader.lines().forEach(line -> {
                synchronized (lines) {
                    lines.add(line);
                }
            }));

            if (!process.waitFor(8, TimeUnit.MINUTES)) {
                process.destroyForcibly();
            }
            reading.join(10_000);
        }

        var values = new HashMap<String, String>();

        synchronized (lines) {
            lines.stream()
                 .filter(line -> line.matches("[A-Z_]+=.*"))
                 .forEach(line -> values.put(line.substring(0, line.indexOf('=')), line.substring(line.indexOf('=') + 1)));
            values.put("EXIT", process.isAlive() ? "timeout" : Integer.toString(process.exitValue()));
            values.put("TAIL", String.join(" | ", lines.subList(Math.max(0, lines.size() - 15), lines.size())));
        }

        return values;
    }
}
