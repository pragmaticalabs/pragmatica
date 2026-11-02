// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.CapacityReservationKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CapacityReservationPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.CapacityReservationValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;


/// The M-1 instrument across VMs (#2512, arm X): a `-javaagent` that installs [M1Instrument] (compiled from the SAME source file the
/// in-JVM harness uses, `aether/ember/src/test/.../M1Instrument.java`) into a stock `aether-node.jar` process, and serves its counters on
/// the loopback interface. Test scope only: it is built by `armxk/agent/build-agent.sh` against the node jar and is never part of a
/// production artifact.
///
/// Finding the node: `Main.run` keeps the `AetherNode` in a local and registers a shutdown hook whose lambda captures it, right after the
/// node is assembled and before it starts. The agent polls `java.lang.ApplicationShutdownHooks` for that hook, reads the hook thread's
/// task, and walks it with the instrument's own graph search. This needs `--add-opens java.base/java.lang=ALL-UNNAMED`.
///
/// Endpoints (text, one `name value` pair per line, loopback only):
/// - `/m1/health` — `ok <nodeId>` once installed, `waiting` before, `failed <reason>` if installation threw;
/// - `/m1/snapshot` — `now.ms`, `node`, then every counter of [M1Instrument#snapshot];
/// - `/m1/ops?since=<epoch ms>` — for each op in the commit log at or after `since`, the epoch ms of its last commit (the settle rule);
/// - `/m1/admit?node=<id>&role=<worker|core>` — on the LEADER core only: commits an OBSERVED `CapacityReservation` for a node the harness is
///   about to start, by leader transaction. Without it a hand-started node is never admitted
///   (`WorkerHierarchyAssembly.workerAdmissionAllowed` admits on an environment authorization or a DISPATCHED/OBSERVED reservation, and a
///   node with no compute provider has neither). It is the cross-VM counterpart of the admission predicate Ember hands its in-JVM nodes
///   (`EmberCluster.localWorkerAdmissions`). The harness calls it outside every measured window; the reservation records it writes are part
///   of the counted state afterwards, which the harness README states.
public final class M1ProbeAgent {
    private static final long FIND_DEADLINE_MS = 600_000;
    private static final TimeSpan ADMIT_BOUND = TimeSpan.timeSpan(20).seconds();
    private static volatile M1Instrument instrument;
    private static volatile AetherNode node;
    private static volatile String status = "waiting";
    private static volatile long baseMs;
    private static volatile long baseNanos;

    private M1ProbeAgent() {}

    public static void premain(String args) {
        var port = Integer.parseInt(argument(args, "port", "0"));
        var thread = new Thread(() -> attach(port), "m1probe-attach");

        thread.setDaemon(true);
        thread.start();
    }

    private static String argument(String args, String name, String fallback) {
        if (args == null) {
            return fallback;
        }

        for (var part : args.split(",")) {
            var kv = part.split("=", 2);

            if (kv.length == 2 && kv[0].strip().equals(name)) {
                return kv[1].strip();
            }
        }

        return fallback;
    }

    private static void attach(int port) {
        try {
            serve(port);
        } catch (IOException e) {
            System.err.println("[m1probe] cannot bind 127.0.0.1:" + port + ": " + e);

            return;
        }

        var deadline = System.currentTimeMillis() + FIND_DEADLINE_MS;

        while (System.currentTimeMillis() < deadline) {
            try {
                var found = findNode();

                if (found != null) {
                    baseMs = System.currentTimeMillis();
                    baseNanos = System.nanoTime();
                    instrument = M1Instrument.install(found);
                    node = found;
                    status = "ok " + instrument.nodeId();
                    System.err.println("[m1probe] installed on " + instrument.nodeId() + ", serving 127.0.0.1:" + port);

                    return;
                }

                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();

                return;
            } catch (Throwable t) {
                status = "failed " + t;
                System.err.println("[m1probe] installation failed: " + t);
                t.printStackTrace();

                return;
            }
        }

        status = "failed no AetherNode reachable from a shutdown hook within " + FIND_DEADLINE_MS + " ms";
        System.err.println("[m1probe] " + status);
    }

    @SuppressWarnings("unchecked")
    private static AetherNode findNode() throws ReflectiveOperationException {
        var hooksClass = Class.forName("java.lang.ApplicationShutdownHooks");
        var field = hooksClass.getDeclaredField("hooks");

        field.setAccessible(true);
        List<Thread> hooks;

        synchronized (hooksClass) {
            var map = (Map<Thread, Thread>) field.get(null);

            hooks = map == null
                    ? List.of()
                    : new ArrayList<>(map.keySet());
        }

        for (var hook : hooks) {
            var task = taskOf(hook);

            if (task != null && M1Instrument.find(task, AetherNode.class) instanceof AetherNode candidate) {
                return candidate;
            }
        }

        return null;
    }

    /// A platform thread's Runnable: `holder.task` since JDK 19, `target` before.
    private static Object taskOf(Thread thread) throws ReflectiveOperationException {
        try {
            var holder = readField(thread, Thread.class, "holder");

            return holder == null
                   ? null
                   : readField(holder, holder.getClass(), "task");
        } catch (NoSuchFieldException e) {
            return readField(thread, Thread.class, "target");
        }
    }

    private static Object readField(Object target, Class<?> type, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);

        field.setAccessible(true);

        return field.get(target);
    }

    // ---- loopback server -----------------------------------------------------------------------------------------------------------
    private static void serve(int port) throws IOException {
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 16);

        server.createContext("/m1/health", exchange -> reply(exchange, status + "\n"));
        server.createContext("/m1/snapshot", M1ProbeAgent::snapshot);
        server.createContext("/m1/ops", M1ProbeAgent::ops);
        server.createContext("/m1/admit", M1ProbeAgent::admit);
        server.setExecutor(null);
        server.start();
    }

    private static void snapshot(HttpExchange exchange) throws IOException {
        var current = instrument;

        if (current == null) {
            reply(exchange, 503, status + "\n");

            return;
        }

        var out = new StringBuilder();

        out.append("now.ms ").append(System.currentTimeMillis()).append('\n');
        out.append("node ").append(current.nodeId()).append('\n');
        current.snapshot().forEach((name, value) -> out.append(name)
                                                       .append(' ')
                                                       .append(value)
                                                       .append('\n'));
        reply(exchange, out.toString());
    }

    private static void ops(HttpExchange exchange) throws IOException {
        var current = instrument;

        if (current == null) {
            reply(exchange, 503, status + "\n");

            return;
        }

        var sinceMs = Long.parseLong(query(exchange, "since", "0"));
        var sinceNanos = baseNanos + (sinceMs - baseMs) * 1_000_000L;
        var last = new TreeMap<String, Long>();

        current.logSince(sinceNanos).forEach(commit -> last.merge(commit.op(), toMs(commit.nanos()), Math::max));
        var out = new StringBuilder();

        out.append("now.ms ").append(System.currentTimeMillis()).append('\n');
        last.forEach((op, ms) -> out.append(op)
                                    .append(' ')
                                    .append(ms)
                                    .append('\n'));
        reply(exchange, out.toString());
    }

    /// Replies `accepted`, `refused` or `failed <cause>`; a refusal (this core is not the leader, or the key already moved) is the
    /// caller's to retry against the current leader.
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void admit(HttpExchange exchange) throws IOException {
        var current = node;

        if (current == null) {
            reply(exchange, 503, status + "\n");

            return;
        }

        var key = new CapacityReservationKey(new NodeId(query(exchange, "node", "")));
        var value = new CapacityReservationValue("armxk",
                                                 "",
                                                 query(exchange, "role", "worker"),
                                                 CapacityReservationPhase.OBSERVED);
        var authority = current.kvStore().getTyped(LeaderKey.INSTANCE, LeaderValue.class);

        if (authority.isEmpty()) {
            reply(exchange, 409, "refused no committed leader\n");

            return;
        }

        var id = UUID.randomUUID().toString();
        var transaction = new KVCommand.LeaderTransaction<AetherKey, AetherValue>(key,
                                                                                  id,
                                                                                  authority.unwrap(),
                                                                                  List.of(),
                                                                                  List.of(new KVCommand.Mutation<AetherKey, AetherValue>(key,
                                                                                                                                         Option.none(),
                                                                                                                                         Option.some(value))));
        var result = current.<Object> apply(List.of(transaction)).await(ADMIT_BOUND);
        var accepted = result.map(list -> list.stream()
                                              .anyMatch(o -> o instanceof KVCommand.TransactionResult r
                                                             && r.transactionId()
                                                                 .equals(id)
                                                             && r.accepted()))
                             .or(false);

        reply(exchange,
              accepted
              ? 200
              : 409,
              (accepted
               ? "accepted"
               : "refused " + result) + "\n");
    }

    private static long toMs(long nanos) {
        return baseMs + (nanos - baseNanos) / 1_000_000L;
    }

    private static String query(HttpExchange exchange, String name, String fallback) {
        var raw = exchange.getRequestURI().getRawQuery();

        if (raw == null) {
            return fallback;
        }

        for (var part : raw.split("&")) {
            var kv = part.split("=", 2);

            if (kv.length == 2 && kv[0].equals(name)) {
                return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            }
        }

        return fallback;
    }

    private static void reply(HttpExchange exchange, String body) throws IOException {
        reply(exchange, 200, body);
    }

    private static void reply(HttpExchange exchange, int code, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(code, bytes.length);
        try (var stream = exchange.getResponseBody()) {
            stream.write(bytes);
        }
    }
}
