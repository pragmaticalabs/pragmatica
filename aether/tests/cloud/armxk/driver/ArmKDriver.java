// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.


import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;


/// Arm K load driver (#2512, q8 P6.1): closed-loop HTTP clients against `POST /api/v1/config`, one op = one CONFIG_SET over a rotating set
/// of 16 keys with a constant-size value (the P6 pre-flight pins it at exactly one `ConfigKey` put per op). Runs on a NON-core host.
///
/// `java ArmKDriver.java <url> <api-key-file> <concurrency> <warmupSeconds> <measureSeconds> <keyPrefix>`
///
/// Prints one JSON line for the measured phase: ops accepted (HTTP 200 with `config_set`), errors by kind, p50/p99/max latency in ms, the
/// measured phase's UTC start and end in epoch ms. A client waits for its reply before sending again (closed loop), so concurrency, not a
/// target rate, is the input. The warm-up phase runs the same clients and is discarded.
public final class ArmKDriver {
    private static final int KEYS = 16;

    public static void main(String[] args) throws Exception {
        var url = URI.create(args[0]);
        var apiKey = Files.readString(Path.of(args[1])).strip().split(":", 2) [0];
        var concurrency = Integer.parseInt(args[2]);
        var warmupMs = Long.parseLong(args[3]) * 1000;
        var measureMs = Long.parseLong(args[4]) * 1000;
        var prefix = args[5];
        var client = HttpClient.newBuilder()
                               .connectTimeout(Duration.ofSeconds(5))
                               .version(HttpClient.Version.HTTP_1_1)
                               .build();
        var start = System.currentTimeMillis();
        var measureFrom = start + warmupMs;
        var measureTo = measureFrom + measureMs;
        var serial = new AtomicLong();
        // The orchestrator waits for this line before it starts its warm-up clock: the source launch itself takes seconds, and core
        // readings taken before the clients run would widen the reading window with unloaded time.
        System.err.println("armk-driver started; measure starts in " + warmupMs + " ms");
        var workers = new ArrayList<Worker>();

        for (int i = 0; i < concurrency; i++) {
            workers.add(new Worker(client, url, apiKey, prefix, serial, measureFrom, measureTo));
        }

        var threads = workers.stream().map(w -> Thread.ofVirtual().start(w)).toList();

        for (var thread : threads) {
            thread.join();
        }

        long ok = 0, http = 0, timeout = 0, io = 0, body = 0, okAll = 0, failedAll = 0;
        var latencies = new ArrayList<long[]>();

        for (var w : workers) {
            ok += w.ok;
            http += w.httpErrors;
            timeout += w.timeouts;
            io += w.ioErrors;
            body += w.bodyErrors;
            okAll += w.okAll;
            failedAll += w.failedAll;
            latencies.add(Arrays.copyOf(w.latencyMicros, w.count));
        }

        var all = latencies.stream().flatMapToLong(Arrays::stream).sorted().toArray();

        System.out.println("{\"concurrency\":" + concurrency
                          + ",\"measureStartMs\":" + measureFrom
                          + ",\"measureEndMs\":" + measureTo
                          + ",\"measureSeconds\":" + measureMs / 1000
                          + ",\"ok\":" + ok
                          + ",\"opsPerSec\":" + String.format("%.2f", ok * 1000.0 / measureMs)
                          + ",\"errors\":{\"http\":" + http
                          + ",\"timeout\":" + timeout
                          + ",\"io\":" + io
                          + ",\"body\":" + body
                          + "}"
                          + ",\"p50Ms\":" + pct(all, 0.50)
                          + ",\"p99Ms\":" + pct(all, 0.99)
                          + ",\"maxMs\":" + pct(all, 1.0)
                          + ",\"samples\":" + all.length
                          + ",\"okAll\":" + okAll
                          + ",\"failedAll\":" + failedAll
                          + "}");
    }

    private static String pct(long[] sorted, double q) {
        if (sorted.length == 0) {
            return "null";
        }

        var index = Math.min(sorted.length - 1, (int) Math.ceil(q * sorted.length) - 1);

        return String.format("%.3f", sorted[Math.max(0, index)] / 1000.0);
    }

    private static final class Worker implements Runnable {
        private final HttpClient client;
        private final URI url;
        private final String apiKey;
        private final String prefix;
        private final AtomicLong serial;
        private final long measureFrom;
        private final long measureTo;
        long ok, httpErrors, timeouts, ioErrors, bodyErrors;
        /// Every phase, warm-up and window edges included: the exact op count the orchestrator compares with the commits it brackets around
        /// the whole run. A failed op (timeout, I/O, refused) may still have committed, so it widens the bound rather than counting as 0.
        long okAll, failedAll;
        long[] latencyMicros = new long[1 << 12];
        int count;

        Worker(HttpClient client,
               URI url,
               String apiKey,
               String prefix,
               AtomicLong serial,
               long measureFrom,
               long measureTo) {
            this.client = client;
            this.url = url;
            this.apiKey = apiKey;
            this.prefix = prefix;
            this.serial = serial;
            this.measureFrom = measureFrom;
            this.measureTo = measureTo;
        }

        @Override
        public void run() {
            while (System.currentTimeMillis() < measureTo) {
                var n = serial.incrementAndGet();
                var payload = "{\"key\":\"" + prefix + (n % KEYS)
                            + "\",\"value\":\"v-" + String.format("%012d", n)
                            + "\"}";
                var request = HttpRequest.newBuilder(url)
                                         .timeout(Duration.ofSeconds(10))
                                         .header("Content-Type", "application/json")
                                         .header("X-API-Key", apiKey)
                                         .POST(HttpRequest.BodyPublishers.ofString(payload))
                                         .build();
                var sent = System.nanoTime();
                var counted = System.currentTimeMillis() >= measureFrom;

                try {
                    var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    var micros = (System.nanoTime() - sent) / 1000;

                    if (response.statusCode() == 200 && response.body().contains("config_set")) {
                        okAll++;
                    } else {
                        failedAll++;
                    }

                    if (!counted || System.currentTimeMillis() > measureTo) {
                        continue;
                    }

                    if (response.statusCode() != 200) {
                        httpErrors++;
                    } else if (!response.body().contains("config_set")) {
                        bodyErrors++;
                    } else {
                        ok++;
                        record(micros);
                    }
                } catch (HttpTimeoutException e) {
                    failedAll++;
                    if (counted) {
                        timeouts++;
                    }
                } catch (IOException e) {
                    failedAll++;
                    if (counted) {
                        ioErrors++;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();

                    return;
                }
            }
        }

        private void record(long micros) {
            if (count == latencyMicros.length) {
                latencyMicros = Arrays.copyOf(latencyMicros, count * 2);
            }

            latencyMicros[count++] = micros;
        }
    }
}
