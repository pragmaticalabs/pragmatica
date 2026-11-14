// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.pragmatica.aether.resource.SharedPoolFixtures.FakeConnector;
import org.pragmatica.aether.resource.SharedPoolFixtures.FakeShareableFactory;
import org.pragmatica.aether.resource.SpiResourceProvider.Provisioned;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;

/// Records traces of the closeAll / acquire race and validates them against the extracted spec (`tools/tla/trace.py`,
/// spec `SharedPools`).
/// Opt-in: runs only with `-Dtla.traces=<dir>`; writes each DISTINCT event sequence once, as `trace-<n>.txt`, and
/// `counts.tsv` (file, rounds that produced it). Every recorded trace must be non-empty and carry the race's two
/// anchor events (`CA_Flag`, `Acq_Admit`). With `-Dtla.spec=<dir holding the extracted SharedPools.tla and
/// SharedPools_code.cfg>` (what `tools/tla/check.sh` writes) it then runs `trace.py` over them and FAILS when any is
/// REJECTED; `-Dtla.jar=<tla2tools.jar>` names the jar, `-Dtla.trace.py=<path>` the script (default: the repository's).
/// Without `tla.spec` it only records. The race is `SharedPoolContainmentTest.closeAll_racingAcquire_...`.
@EnabledIfSystemProperty(named = "tla.traces", matches = ".+")
class SharedPoolsTraceTest {
    /// Maps one SharedPools instance's objects to the spec's names: scopes a, b; keys k1, k2; pools 1.. in creation order.
    private static final class Recorder implements SharedPools.TraceSink {
        private final List<String> lines = new ArrayList<>();
        private final Map<Object, String> scopes = new HashMap<>();
        private final Map<Object, String> keys = new HashMap<>();
        private final Map<Object, Integer> pools = new IdentityHashMap<>();

        @Override
        public Unit event(Object... event) {
            if (event.length == 0) {
                return Unit.unit();
            }
            var action = (String) event[0];
            var line = new StringBuilder(action);

            for (int i = 1; i < event.length; i++) {
                line.append(' ').append(name(action, i, event[i]));
            }
            lines.add(line.toString().strip());
            return Unit.unit();
        }

        private String name(String action, int position, Object value) {
            if (value instanceof Promise<?>) {
                return String.valueOf(pools.computeIfAbsent(value, _ -> pools.size() + 1));
            }
            if (action.startsWith("Acq_") && position == 1) {
                return scopes.computeIfAbsent(value, _ -> scopes.isEmpty() ? "a" : "b");
            }
            if ((action.startsWith("Acq_") && position == 2) || (action.equals("CA_Drain") && position == 1)) {
                return keys.computeIfAbsent(value, _ -> keys.isEmpty() ? "k1" : "k2");
            }
            return String.valueOf(value);
        }
    }

    @Test
    void closeAll_racingAcquire_recordsTraces() throws Exception {
        var dir = Path.of(System.getProperty("tla.traces"));
        var rounds = Integer.getInteger("tla.rounds", 2000);
        var factory = new FakeShareableFactory();
        var go = new AtomicLong();
        var done = new AtomicLong();
        var holder = new SharedPools[1];
        var stop = new AtomicBoolean();
        var distinct = new LinkedHashMap<List<String>, Integer>();

        Runnable acquirer = () -> {
            for (long r = 1; !stop.get(); r++) {
                while (go.get() < r) {
                    if (stop.get()) {
                        return;
                    }
                    Thread.onSpinWait();
                }
                holder[0].acquire("k",
                                  "slice-a",
                                  factory,
                                  () -> Promise.<Provisioned<?>> success(Provisioned.provisioned((FakeConnector) factory.newPool(),
                                                                                                 factory)),
                                  "label");
                done.incrementAndGet();
            }
        };
        var thread = Thread.ofPlatform().start(acquirer);

        try {
            for (long r = 1; r <= rounds; r++) {
                var recorder = new Recorder();

                holder[0] = SharedPools.sharedPools(_ -> Promise.unitPromise());
                holder[0].traceTo(recorder);
                go.set(r);
                awaitAll(holder[0].closeAll());
                while (done.get() < r) {
                    Thread.onSpinWait();
                }
                synchronized (recorder) {
                    distinct.merge(List.copyOf(recorder.lines), 1, Integer::sum);
                }
            }
        } finally {
            stop.set(true);
            thread.join(5_000);
        }
        write(dir, distinct);
        assertThat(distinct).isNotEmpty();
        distinct.keySet()
                .forEach(SharedPoolsTraceTest::assertRecorded);
        Optional.ofNullable(System.getProperty("tla.spec"))
                .ifPresent(spec -> assertAccepted(dir, spec));
    }

    private static void awaitAll(List<Promise<Unit>> closes) {
        closes.forEach(Promise::await);
    }

    private static void assertRecorded(List<String> trace) {
        assertThat(trace).as("a round that recorded no events: the hook is not reaching the code")
                         .isNotEmpty();
        assertThat(trace).anyMatch(line -> line.startsWith("CA_Flag"));
        assertThat(trace).anyMatch(line -> line.startsWith("Acq_Admit"));
    }

    private static void assertAccepted(Path dir, String spec) {
        var script = System.getProperty("tla.trace.py", "../../../tools/tla/trace.py");
        var command = new ArrayList<>(List.of("python3", script, "--spec", spec, "--traces", dir.toString()));

        Optional.ofNullable(System.getProperty("tla.jar"))
                .ifPresent(jar -> command.addAll(List.of("--jar", jar)));
        assertThat(runTraceCheck(command)).as("trace.py verdict")
                                          .contains("ACCEPTED")
                                          .doesNotContain("REJECTED");
    }

    private static String runTraceCheck(List<String> command) {
        try {
            var process = new ProcessBuilder(command).redirectErrorStream(true)
                                                     .start();
            var output = new String(process.getInputStream()
                                           .readAllBytes());

            assertThat(process.waitFor()).as("trace.py exit code, output: " + output)
                                         .isZero();
            return output;
        } catch (IOException e) {
            throw new IllegalStateException("cannot run trace.py", e);
        } catch (InterruptedException e) {
            Thread.currentThread()
                  .interrupt();
            throw new IllegalStateException("interrupted running trace.py", e);
        }
    }

    private static void write(Path dir, Map<List<String>, Integer> distinct) throws IOException {
        Files.createDirectories(dir);
        var counts = new StringBuilder();
        int n = 0;

        for (var trace : distinct.entrySet()) {
            var file = "trace-" + (++n) + ".txt";

            Files.write(dir.resolve(file), trace.getKey());
            counts.append(file).append('\t').append(trace.getValue()).append('\n');
        }
        Files.writeString(dir.resolve("counts.tsv"), counts);
    }
}
