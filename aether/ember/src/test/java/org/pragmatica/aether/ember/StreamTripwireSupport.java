// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.config.StreamingConfig;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.segment.RetentionEnforcer;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;

/// Shared harness for the streams-overhaul tripwires (#2201): the 3-node Ember boot, the management-API call, the
/// log capture and the one reach into node internals the tripwires need.
///
/// **The reach into the node.** `AetherNode`'s interface exposes the partition manager and the read router, but not the
/// consumer runtime, the retention enforcer or the segment index: those are components of the node's local record
/// (`AetherNode.aetherNode`), reachable only by reflection on the record accessor. That is the one wiring-level seam
/// these tests have, and it is deliberate: the objects returned are the node's real, wired instances (real reader,
/// cursor store, retention policy), not rebuilt ones. If a record component is renamed, [#component] fails loudly with
/// the name it looked for.
final class StreamTripwireSupport {
    static final int CLUSTER_SIZE = 3;
    static final int SLOTS = 2 * CLUSTER_SIZE;
    static final int MGMT_OFFSET = 40;
    static final int APP_HTTP_OFFSET = 80;
    static final EmberTestPorts.Block PORTS = new EmberTestPorts.Block(EmberTestPorts.POOL_FIRST,
                                                                        EmberTestPorts.POOL_LAST,
                                                                        EmberTestPorts.POOL_STEP,
                                                                        SLOTS,
                                                                        MGMT_OFFSET,
                                                                        APP_HTTP_OFFSET);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    static final long LEADER_BUDGET_MS = 90_000L;
    static final String NAMESPACE = "ember";
    static final String VERSION = "1.0.0";
    static final int PARTITION = 0;

    record Response(int status, String body, long elapsedMs) {
        boolean acked() {
            return status >= 200 && status < 300;
        }
    }

    private StreamTripwireSupport() {}

    static String engineKey(String stream) {
        return NAMESPACE + ":" + stream + ":" + VERSION;
    }

    /// A stream config with a small ring (`maxCount`) and the cluster-default replication, for tests that need the ring to
    /// evict. `maxAgeMs` is the stream's declared max age.
    static StreamConfig smallRingStream(String stream, int partitions, long maxCount, long maxAgeMs) {
        return StreamConfig.streamConfig(engineKey(stream),
                                         partitions,
                                         RetentionPolicy.retentionPolicy(maxCount, 64L * 1024 * 1024, maxAgeMs),
                                         "earliest");
    }

    @SuppressWarnings("JBCT-EX-01")
    static Response post(int mgmtPort, String path, String json) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://127.0.0.1:" + mgmtPort + path))
                                 .timeout(REQUEST_TIMEOUT)
                                 .header("Content-Type", "application/json")
                                 .POST(HttpRequest.BodyPublishers.ofString(json))
                                 .build();
        var started = System.nanoTime();

        try (var client = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build()) {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());

            return new Response(response.statusCode(), response.body(), elapsedMs(started));
        } catch (Exception e) {
            // A transport failure is not a 2xx and is reported verbatim rather than read as a refusal.
            return new Response(-1, e.toString(), elapsedMs(started));
        }
    }

    static Response publishVia(int mgmtPort, String stream, String data) {
        return post(mgmtPort, "/api/v1/streams/" + NAMESPACE + "/" + stream + "/" + VERSION + "/publish", "{\"data\":\"" + data + "\"}");
    }

    static void awaitLeader(EmberCluster cluster) {
        var deadline = System.currentTimeMillis() + LEADER_BUDGET_MS;

        while (System.currentTimeMillis() < deadline && cluster.getLeaderManagementPort().isEmpty()) {
            sleepQuietly(500L);
        }

        assertThat(cluster.getLeaderManagementPort().isPresent()).as("a leader is elected").isTrue();
    }

    static void awaitUntil(BooleanSupplier condition, long budgetMs, String what) {
        var deadline = System.currentTimeMillis() + budgetMs;

        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            sleepQuietly(200L);
        }
        assertThat(condition.getAsBoolean()).as("within %dms: %s", budgetMs, what).isTrue();
    }

    /// The node's own wired instance of a record component of `AetherNode.aetherNode` that the interface does not expose.
    @SuppressWarnings({"JBCT-EX-01", "unchecked"})
    static <T> T component(AetherNode node, String name, Class<T> type) {
        try {
            var accessor = node.getClass().getDeclaredMethod(name);

            accessor.setAccessible(true);

            return type.cast(accessor.invoke(node));
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new AssertionError("AetherNode record has no readable component '" + name + "' (" + e + "): a rename needs this seam updated", e);
        }
    }

    /// Captures every log event at `minimum` or above, from any logger, as `LEVEL logger: message`; the returned runnable
    /// detaches the appender.
    @SuppressWarnings("JBCT-RET-01")
    static Runnable captureLogs(Level minimum, List<String> sink) {
        var context = (LoggerContext) LogManager.getContext(false);
        var rootConfig = context.getConfiguration().getRootLogger();
        var name = "tripwire-capture-" + System.nanoTime();
        var appender = new AbstractAppender(name, null, PatternLayout.createDefaultLayout(), true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                if (event.getLevel().isMoreSpecificThan(minimum)) {
                    sink.add(event.getLevel() + " " + event.getLoggerName() + ": " + event.getMessage().getFormattedMessage());
                }
            }
        };

        appender.start();
        rootConfig.addAppender(appender, minimum, null);
        context.updateLoggers();

        return () -> {
            rootConfig.removeAppender(name);
            context.updateLoggers();
            appender.stop();
        };
    }

    /// A three-node cluster over a restart-stable data directory: the directory is what gives each node its WAL and its
    /// sealed tier. Without one an evicted event is never sealed.
    static EmberCluster threeNodes(int basePort, String nodeIdPrefix, Path dataDir) {
        var built = EmberCluster.emberCluster(CLUSTER_SIZE,
                                              basePort,
                                              basePort + MGMT_OFFSET,
                                              basePort + APP_HTTP_OFFSET,
                                              nodeIdPrefix);

        built.withDataBaseDir(dataDir);

        return built;
    }

    /// As [#threeNodes(int, String, Path)] with the node-default streaming configuration except `reshuffleConcurrency`: for a
    /// test about something other than the pacing of replica materialization (default 2 slots, each held through a 20 s
    /// source wait on an empty partition), which otherwise decides how long the test waits.
    static EmberCluster threeNodes(int basePort, String nodeIdPrefix, Path dataDir, int reshuffleConcurrency) {
        var built = threeNodes(basePort, nodeIdPrefix, dataDir);
        var defaults = StreamingConfig.streamingConfig();

        built.withStreamingConfig(StreamingConfig.streamingConfig(defaults.publishForwardTimeout(),
                                                                  defaults.readForwardTimeout(),
                                                                  defaults.maxReadResponseBytes(),
                                                                  defaults.readLinearization(),
                                                                  reshuffleConcurrency));

        return built;
    }

    @SuppressWarnings("JBCT-EX-01")
    static Path newDataDir(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// The committed ownership record for partition 0 once all three nodes agree on it and its ISR holds all three.
    static StreamPartitionOwnershipValue awaitFullIsr(EmberCluster cluster, String engineKey, long budgetMs) {
        var deadline = System.currentTimeMillis() + budgetMs;

        while (System.currentTimeMillis() < deadline) {
            var record = committedRecord(cluster, engineKey, PARTITION).filter(value -> value.isrVersion() > 0 && value.isr().size() == 3);

            if (record.isPresent()) {
                return record.unwrap();
            }

            sleepQuietly(250L);
        }

        throw new AssertionError("no committed ISR of three for " + engineKey + " within " + budgetMs + "ms; last: " + committedRecord(cluster, engineKey, PARTITION));
    }

    static Option<StreamPartitionOwnershipValue> committedRecord(EmberCluster cluster, String engineKey, int partition) {
        var seen = new HashSet<StreamPartitionOwnershipValue>();

        cluster.allNodes()
               .forEach(node -> node.kvStore()
                                    .getTyped(AetherKey.StreamPartitionOwnershipKey.streamPartitionOwnershipKey(engineKey, partition),
                                              StreamPartitionOwnershipValue.class)
                                    .onPresent(seen::add));

        return seen.size() == 1
               ? Option.some(seen.iterator().next())
               : Option.none();
    }

    /// One retention pass on this node's real enforcer (package-private `enforceNow`, reached by reflection: the production
    /// tick is every five minutes). Resolves with the number of segment refs the pass dropped.
    @SuppressWarnings({"JBCT-EX-01", "unchecked"})
    static int enforceRetentionPass(AetherNode node) {
        try {
            var enforcer = component(node, "streamRetentionEnforcer", RetentionEnforcer.class);
            var method = RetentionEnforcer.class.getDeclaredMethod("enforceNow");

            method.setAccessible(true);

            return ((Promise<Integer>) method.invoke(enforcer)).await(TimeSpan.timeSpan(60).seconds()).fold(cause -> -1, removed -> removed);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new AssertionError("RetentionEnforcer.enforceNow is not reachable (" + e + "): the retention-pass seam needs updating", e);
        }
    }

    static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    @SuppressWarnings("JBCT-EX-01")
    static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
