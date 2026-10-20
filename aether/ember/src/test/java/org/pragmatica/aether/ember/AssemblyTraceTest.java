// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.testlogging.LogCapture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// The assembly instrument (test-only): writes `target/assembly-trace.txt`, a plain-text description of what
/// `AetherNode` assembled and in what order, for a base-versus-head diff around a pure-move refactoring of the
/// assembly. There is no golden file: a feature PR that changes the assembly changes the trace and nobody has to
/// update a fixture; the verifier runs this test on the base and on the head and compares the two files byte for byte.
///
/// What it records, each section from a real 3-node Ember boot with one core held back (assembled, never started):
///
///   - **T1** the route table: for every message class, sorted by class name, the ordered handler fingerprints.
///     Reached by reflection: the node's `router` record component, its delegate, `routingTable()`.
///   - **T2** the periodic-task counts: `deferredCount()` of the assembled-but-unstarted node, then `armedCount()`
///     and `deferredCount()` of every started node.
///   - **T3** the record components of the assembled node, by name and by the runtime class of the value.
///   - **T4** the INFO lines `AetherNode` logs while the cluster boots and while it stops, in order of first
///     occurrence (see [#orderedDistinct]).
///   - **T5** the ordered listener fingerprints on the pong fan, the SWIM observation listeners and the consensus
///     `onStateRestored` list: three places where registration order is behaviour.
///
/// A fingerprint is built from what survives a move: the runtime class of whatever the lambda captured. Synthetic
/// lambda names (`lambda$assembleNode$123`) and hidden-class addresses change when code moves between files, and so do
/// anonymous-class numbers, so none of them is printed; a hidden class prints as its functional interface, an anonymous
/// class as its first supertype. Two limits follow and are stated rather than hidden: two registrations whose lambdas
/// capture the same classes in the same order have the same fingerprint, so swapping THOSE two is invisible; and a
/// non-capturing lambda prints only its interface. Swapping registrations whose captured targets differ is what T1 and T5
/// detect.
@PortBudget
class AssemblyTraceTest {
    private static final int CLUSTER_SIZE = 3;
    private static final int SLOTS = 2 * CLUSTER_SIZE;
    private static final int MGMT_OFFSET = 40;
    private static final int APP_HTTP_OFFSET = 80;
    private static final EmberTestPorts.Block PORTS = new EmberTestPorts.Block(EmberTestPorts.POOL_FIRST,
                                                                                EmberTestPorts.POOL_LAST,
                                                                                EmberTestPorts.POOL_STEP,
                                                                                SLOTS,
                                                                                MGMT_OFFSET,
                                                                                APP_HTTP_OFFSET);
    private static final String NODE_PREFIX = "trc";
    private static final String HELD_BACK_ID = NODE_PREFIX + "-3";
    private static final String STARTED_ID = NODE_PREFIX + "-1";
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final long ARMED_BOUND_MS = 60_000L;
    private static final String NODE_LOGGER_PREFIX = AetherNode.class.getName();
    private static final Pattern NODE_ID = Pattern.compile(NODE_PREFIX + "-\\d+");
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Path TRACE_FILE = Path.of("target", "assembly-trace.txt");
    private static final int MAX_CAPTURE_DEPTH = 3;

    private EmberCluster cluster;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            cluster.stop().await(STOP_BOUND);
        }
    }

    @Test
    @Timeout(600)
    void assemblyTrace_isWrittenForTheBaseVersusHeadDiff() throws IOException {
        var context = (LoggerContext) LogManager.getContext(false);
        var trace = new ArrayList<String>();

        try (var logs = LogCapture.captureRoot(context.getConfiguration())) {
            cluster = EmberTestPorts.startedCluster(PORTS,
                                                    basePort -> emberCluster(CLUSTER_SIZE,
                                                                             basePort,
                                                                             basePort + MGMT_OFFSET,
                                                                             basePort + APP_HTTP_OFFSET,
                                                                             NODE_PREFIX),
                                                    started -> started.start(Set.of(HELD_BACK_ID)),
                                                    EmberCluster::stop,
                                                    START_BOUND);

            var assembled = cluster.heldBackNode(HELD_BACK_ID).unwrap();

            trace.add("# assembly-trace v1");
            trace.addAll(routeTable("T1 route table (assembled node, not started)", assembled));
            trace.addAll(periodicTasksAssembled(assembled));
            trace.addAll(recordComponents(assembled));
            trace.addAll(listeners(assembled));

            awaitArmed(cluster.getNode(STARTED_ID).unwrap());
            cluster.startHeldBackNodes().await(START_BOUND).onFailure(cause -> failWith("held-back start", cause));
            awaitArmed(assembled);
            trace.addAll(routeTable("T1 route table (started node)", cluster.getNode(STARTED_ID).unwrap()));
            trace.addAll(periodicTasksStarted());

            var bootEvents = logs.events().size();

            cluster.stop().await(STOP_BOUND).onFailure(cause -> failWith("cluster stop", cause));
            cluster = null;
            trace.addAll(nodeLog("T4 AetherNode INFO lines, boot", logs.events().subList(0, bootEvents)));
            trace.addAll(nodeLog("T4 AetherNode INFO lines, stop",
                                 logs.events().subList(bootEvents, logs.events().size())));
        }

        Files.createDirectories(TRACE_FILE.getParent());
        Files.writeString(TRACE_FILE, String.join("\n", trace) + "\n");

        assertThat(trace).as("every section must be non-empty, or the instrument cannot see what it claims to see")
                         .noneMatch(line -> line.endsWith(" (0)"));
    }

    // ---- T1 ------------------------------------------------------------------------------------

    private static List<String> routeTable(String title, AetherNode node) {
        var router = (MessageRouter.DelegateRouter) component(node, "router");
        var delegate = fieldValue(router, "delegate");
        var table = new TreeMap<String, List<String>>();

        ((MessageRouter.ImmutableRouter<?>) delegate).routingTable()
                                                      .forEach((type, handlers) -> table.put(type.getName(),
                                                                                             handlers.stream()
                                                                                                     .map(AssemblyTraceTest::fingerprint)
                                                                                                     .toList()));

        var lines = new ArrayList<String>();

        lines.add("## " + title + " (" + table.size() + ")");
        table.forEach((type, handlers) -> lines.addAll(routeLines(type, handlers)));
        return lines;
    }

    private static List<String> routeLines(String type, List<String> handlers) {
        var lines = new ArrayList<String>();

        lines.add(type + " (" + handlers.size() + ")");
        for (int i = 0; i < handlers.size(); i++) {
            lines.add("  " + i + ": " + handlers.get(i));
        }
        return lines;
    }

    // ---- T2 ------------------------------------------------------------------------------------

    private static List<String> periodicTasksAssembled(AetherNode node) {
        return List.of("## T2 periodic tasks, assembled node (1)",
                       "deferred=" + node.periodicTasks().deferredCount() + " armed=" + node.periodicTasks().armedCount());
    }

    private List<String> periodicTasksStarted() {
        var lines = new ArrayList<String>();
        var nodes = cluster.allNodes()
                           .stream()
                           .sorted(Comparator.comparing(node -> node.self().id()))
                           .toList();

        lines.add("## T2 periodic tasks, started nodes (" + nodes.size() + ")");
        nodes.forEach(node -> lines.add(node.self().id().replaceAll(".*-", "node-")
                                        + " deferred=" + node.periodicTasks().deferredCount()
                                        + " armed=" + node.periodicTasks().armedCount()));
        return lines;
    }

    // ---- T3 ------------------------------------------------------------------------------------

    private static List<String> recordComponents(AetherNode node) {
        var components = node.getClass().getRecordComponents();
        var lines = new ArrayList<String>();

        lines.add("## T3 record components of " + node.getClass().getName() + " (" + components.length + ")");
        for (var component : components) {
            lines.add(component.getName() + ": " + describe(component(node, component.getName()), MAX_CAPTURE_DEPTH));
        }
        return lines;
    }

    // ---- T5 ------------------------------------------------------------------------------------

    private static List<String> listeners(AetherNode node) {
        var lines = new ArrayList<String>();
        var engine = invokeNoArg(component(node, "clusterNode"), "consensus");

        lines.add("## T5 listeners (3)");
        lines.addAll(listenerList("pong listeners", fieldValue(component(node, "metricsCollector"), "pongListeners")));
        lines.addAll(listenerList("swim observation listeners",
                                  fieldValue(component(node, "swimHealthDetector"), "pendingObservationListeners")));
        lines.addAll(listenerList("onStateRestored", fieldValue(engine, "onStateRestored")));
        return lines;
    }

    private static List<String> listenerList(String name, Object list) {
        var listeners = List.copyOf((Collection<?>) list);
        var lines = new ArrayList<String>();

        lines.add(name + " (" + listeners.size() + ")");
        for (int i = 0; i < listeners.size(); i++) {
            lines.add("  " + i + ": " + fingerprint(listeners.get(i)));
        }
        return lines;
    }

    // ---- T4 ------------------------------------------------------------------------------------

    /// The INFO lines of the `AetherNode` loggers (the interface's, and the local record that carries `start()` and
    /// `stop()`), with node ids replaced and digits (ports, counts, durations) collapsed, in order of first occurrence.
    private static List<String> nodeLog(String title, List<LogEvent> events) {
        var messages = events.stream()
                             .filter(event -> event.getLoggerName().startsWith(NODE_LOGGER_PREFIX))
                             .filter(event -> event.getLevel() == Level.INFO)
                             .map(event -> normalise(event.getMessage().getFormattedMessage()))
                             .toList();
        var distinct = orderedDistinct(messages);
        var lines = new ArrayList<String>();

        lines.add("## " + title + " (" + distinct.size() + ")");
        distinct.forEach(message -> lines.add("  " + message));
        return lines;
    }

    /// Three nodes boot concurrently, so the arrival order of their lines interleaves differently on every run. The
    /// first occurrence of a message is not affected: every node emits its lines in the same order, so the first arrival
    /// of line X precedes the first arrival of any line Y that follows X on that node.
    private static List<String> orderedDistinct(List<String> messages) {
        return List.copyOf(new LinkedHashSet<>(messages));
    }

    private static String normalise(String message) {
        return DIGITS.matcher(NODE_ID.matcher(message).replaceAll("<node>")).replaceAll("#");
    }

    // ---- fingerprints --------------------------------------------------------------------------

    /// What a handler or listener is, in terms that survive a move of its registration to another file.
    static String fingerprint(Object handler) {
        return describe(handler, MAX_CAPTURE_DEPTH);
    }

    private static String describe(Object value, int depth) {
        if (value == null) {
            return "null";
        }

        if (value instanceof Option<?> option) {
            return option.fold(() -> "Option.none", present -> "Option.some(" + describe(present, depth) + ")");
        }

        if (value instanceof AtomicReference<?> holder) {
            return "AtomicReference(" + describe(holder.get(), depth) + ")";
        }

        if (value instanceof Map<?, ?> map) {
            return className(value.getClass()) + keysOf(map);
        }

        return value.getClass().isHidden() ? lambdaFingerprint(value, depth) : className(value.getClass());
    }

    private static String keysOf(Map<?, ?> map) {
        return map.keySet()
                  .stream()
                  .map(String::valueOf)
                  .sorted()
                  .toList()
                  .toString();
    }

    private static String lambdaFingerprint(Object lambda, int depth) {
        var captured = Arrays.stream(lambda.getClass().getDeclaredFields())
                             .filter(field -> !Modifier.isStatic(field.getModifiers()))
                             .sorted(Comparator.comparingInt(AssemblyTraceTest::captureIndex))
                             .map(field -> describeCaptured(lambda, field, depth))
                             .toList();

        return className(lambda.getClass()) + captured;
    }

    private static String describeCaptured(Object lambda, Field field, int depth) {
        return depth <= 0 ? "..." : describe(read(lambda, field), depth - 1);
    }

    private static int captureIndex(Field field) {
        return Integer.parseInt(field.getName().replaceAll("\\D+", ""));
    }

    private static String className(Class<?> type) {
        if (type.isHidden()) {
            return "lambda:" + type.getInterfaces()[0].getName();
        }

        if (type.isAnonymousClass()) {
            return "anonymous:" + supertypeName(type);
        }

        return type.getName();
    }

    private static String supertypeName(Class<?> type) {
        return type.getInterfaces().length > 0 ? type.getInterfaces()[0].getName() : type.getSuperclass().getName();
    }

    // ---- reflection ----------------------------------------------------------------------------

    private static Object component(Object record, String name) {
        try {
            for (var component : record.getClass().getRecordComponents()) {
                if (component.getName().equals(name)) {
                    var accessor = component.getAccessor();

                    accessor.setAccessible(true);
                    return accessor.invoke(record);
                }
            }
            throw new AssertionError("no record component " + name + " on " + record.getClass().getName());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot read record component " + name, e);
        }
    }

    private static Object invokeNoArg(Object target, String method) {
        try {
            var found = target.getClass().getMethod(method);

            found.setAccessible(true);
            return found.invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot call " + method + "() on " + target.getClass().getName(), e);
        }
    }

    private static Object fieldValue(Object target, String name) {
        for (var type = target.getClass(); type != null; type = type.getSuperclass()) {
            for (var field : type.getDeclaredFields()) {
                if (field.getName().equals(name)) {
                    return read(target, field);
                }
            }
        }
        throw new AssertionError("no field " + name + " on " + target.getClass().getName());
    }

    private static Object read(Object target, Field field) {
        try {
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot read field " + field.getName() + " of " + target.getClass().getName(), e);
        }
    }

    // ---- waiting -------------------------------------------------------------------------------

    private static void awaitArmed(AetherNode node) {
        awaitCondition("node " + node.self().id() + " arms its periodic work",
                       () -> node.periodicTasks().armedCount() > 0 && node.periodicTasks().deferredCount() == 0);
    }

    private static void awaitCondition(String what, BooleanSupplier condition) {
        var deadline = System.nanoTime() + ARMED_BOUND_MS * 1_000_000L;

        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("timed out waiting until %s", what).isLessThan(deadline);
            sleep(100L);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", e);
        }
    }

    private static void failWith(String what, Cause cause) {
        throw new AssertionError(what + " failed: " + cause.message());
    }
}
