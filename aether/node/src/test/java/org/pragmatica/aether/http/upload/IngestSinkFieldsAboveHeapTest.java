// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.upload;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.http.routing.UploadSpec;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.StorageInstance;

import static org.assertj.core.api.Assertions.assertThat;

/// P2 (#2841 round A): the field values of one multipart body are held whole in memory until the request is dispatched, so
/// the fields budget must refuse a body before the heap is at risk. A child JVM with `-Xmx256m` is sent 120 fields of
/// 2 MiB each (240 MiB, under the default 256 MiB size ceiling) on a route declaring `max_field_size` 2 MiB: with the
/// node's byte budget (a quarter of the heap, 64 MiB) as the cap, the body is refused 413 with the heap intact. The
/// control arm runs the same body with no cap and must NOT be refused 413 (it dies of OutOfMemoryError), so the test can
/// fail.
@Timeout(value = 5, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class IngestSinkFieldsAboveHeapTest {
    private static final int MIB = 1024 * 1024;
    private static final int HEAP_MB = 256;
    private static final int FIELDS = 120;
    private static final int FIELD_SIZE = 2 * MIB;
    private static final String BOUNDARY = "fieldsAboveHeap";

    @Test
    void fieldsPastTheBudget_areRefused413_underA256MiBHeap() throws Exception {
        var values = runChild(HEAP_MB * MIB / 4);

        assertThat(values.get("EXIT")).as(values.toString()).isEqualTo("0");
        assertThat(values.get("OUTCOME")).as(values.toString()).isEqualTo("REFUSED_413");
        assertThat(values.get("LIMIT")).as(values.toString()).isEqualTo("the fields total");
        assertThat(Long.parseLong(values.getOrDefault("FIELDS_SEEN", "-1"))).as("refused before the body was read through: " + values)
                                                                              .isLessThan(FIELDS);
    }

    @Test
    void control_withNoCap_theSameBodyIsNotRefused413() throws Exception {
        var values = runChild(Long.MAX_VALUE);

        assertThat(values.get("OUTCOME")).as(values.toString()).isNotEqualTo("REFUSED_413");
    }

    /// The child: a real decoder and ingest sink, 120 fields pushed chunk by chunk as the server would.
    public static void main(String[] args) throws Exception {
        var cap = Long.parseLong(args[0]);
        var storage = StorageInstance.storageInstance("fields-above-heap", List.of(MemoryTier.memoryTier(MIB)));
        var store = UploadIngest.uploadIngest(storage, TimeSpan.timeSpan(1).hours())
                                .store(org.pragmatica.storage.Namespace.namespace("com.example.svc").unwrap())
                                .unwrap();
        var spec = UploadSpec.uploadSpec(true, 0, 0, 0, 0, 0, FIELD_SIZE);
        var decoder = MultipartDecoder.multipartDecoder("multipart/form-data; boundary=" + BOUNDARY, spec, cap).unwrap();
        var sink = IngestSink.ingestSink(store,
                                         new IngestSink.Body.Multipart(decoder, Option.none()),
                                         () -> {},
                                         (_, _) -> {},
                                         (_, _) -> {});

        try {
            System.out.println("OUTCOME=" + push(sink));
        } catch (OutOfMemoryError e) {
            System.out.println("OUTCOME=OOM");
        }
        System.out.flush();
        System.exit(0);
    }

    private static String push(org.pragmatica.http.server.BodySink sink) {
        var chunk = new byte[256 * 1024];
        var seen = 0;

        Arrays.fill(chunk, (byte) 'x');
        for (var field = 0; field < FIELDS; field++) {
            var head = ("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"f" + field + "\"\r\n\r\n")
                       .getBytes(StandardCharsets.ISO_8859_1);
            var refusal = accept(sink, head);

            if (refusal.isPresent()) {
                return refused(refusal.unwrap(), seen);
            }
            for (var sent = 0; sent < FIELD_SIZE; sent += chunk.length) {
                var refused = accept(sink, chunk.clone());

                if (refused.isPresent()) {
                    return refused(refused.unwrap(), seen);
                }
            }
            seen++;
            accept(sink, "\r\n".getBytes(StandardCharsets.ISO_8859_1));
        }

        return "ACCEPTED";
    }

    private static Option<org.pragmatica.lang.Cause> accept(org.pragmatica.http.server.BodySink sink, byte[] bytes) {
        return sink.accept(ByteBuffer.wrap(bytes))
                   .await(TimeSpan.timeSpan(30).seconds())
                   .fold(Option::some, _ -> Option.<org.pragmatica.lang.Cause>none());
    }

    private static String refused(org.pragmatica.lang.Cause cause, int seen) {
        System.out.println("FIELDS_SEEN=" + seen);

        return cause instanceof UploadIngestError.LimitExceeded exceeded
               ? printLimit(exceeded)
               : "OTHER:" + cause.message();
    }

    private static String printLimit(UploadIngestError.LimitExceeded exceeded) {
        System.out.println("LIMIT=" + exceeded.limit());

        return exceeded.status().code() == 413
               ? "REFUSED_413"
               : "REFUSED_OTHER";
    }

    private static HashMap<String, String> runChild(long cap) throws Exception {
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var process = new ProcessBuilder(java,
                                         "-Xmx" + HEAP_MB + "m",
                                         "-cp",
                                         System.getProperty("java.class.path"),
                                         IngestSinkFieldsAboveHeapTest.class.getName(),
                                         Long.toString(cap)).redirectErrorStream(true).start();
        var lines = new ArrayList<String>();

        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            var reading = Thread.ofVirtual().start(() -> reader.lines().forEach(line -> {
                synchronized (lines) {
                    lines.add(line);
                }
            }));

            if (!process.waitFor(4, TimeUnit.MINUTES)) {
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
            values.put("TAIL", String.join(" | ", lines.subList(Math.max(0, lines.size() - 8), lines.size())));
        }

        return values;
    }
}
