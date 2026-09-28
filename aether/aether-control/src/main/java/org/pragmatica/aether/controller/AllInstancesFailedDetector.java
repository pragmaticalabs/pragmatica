// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.controller;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.invoke.SliceFailureEvent.AllInstancesFailed;
import org.pragmatica.aether.metrics.ExecutionOutcomeKeys;
import org.pragmatica.aether.metrics.ExecutionOutcomeKeys.ParsedKey;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.cluster.metrics.MetricObservation;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// #1573: the leader-side "every instance of this version is broken" detector.
///
/// **Input.** Every node ships its cumulative per-(artifact, method) slice execution outcomes on the
/// cluster-sync pong ([ExecutionOutcomeKeys]): successes, and [org.pragmatica.aether.slice.SliceDefect]s —
/// failures the slice BRIDGE produced (the method threw, a codec failed, the method is missing). A failure
/// the method returned itself is never counted, and neither is an execution timeout.
///
/// **Deltas, not totals.** The counters are cumulative, so each accepted observation is compared with the
/// previous one from the same producer and only the difference enters the window. A new producer
/// incarnation, or any counter below its previous value (a restart), is a fresh baseline and contributes no
/// delta — never a negative count.
///
/// **Verdict.** A version `A@v` is failing when it has at least one ACTIVE instance and, for EVERY node
/// hosting one: the node's pong advanced within [#FRESHNESS] (a stale node excludes the version — we cannot
/// tell), and within [#WINDOW] it recorded at least [#DEFECT_THRESHOLD] defects and zero successes for
/// `A@v`. One success anywhere, or one instance without enough defects, means the version is not wholly
/// broken.
///
/// **Output.** One [AllInstancesFailed] per failing episode (latched until the verdict clears), published
/// on the leader only; every other node, and a node that loses leadership, holds no state.
public final class AllInstancesFailedDetector {
    private static final Logger log = LoggerFactory.getLogger(AllInstancesFailedDetector.class);
    /// Sliding window over which defects and successes are summed per hosting node.
    public static final TimeSpan WINDOW = timeSpan(30).seconds();
    /// Minimum defects per hosting node within [#WINDOW].
    public static final long DEFECT_THRESHOLD = 3;
    /// A hosting node whose pong has not advanced for this long makes the version undecidable.
    public static final TimeSpan FRESHNESS = timeSpan(10).seconds();

    private record Baseline(long incarnation, long sequence, Map<String, Double> values) {}

    private record Sample(long atMs, NodeId node, Artifact artifact, String method, long successes, long defects) {}

    private final Supplier<Map<NodeId, MetricObservation>> observations;
    private final Supplier<Map<Artifact, Set<NodeId>>> activeInstances;
    private final BooleanSupplier isLeader;
    private final Consumer<AllInstancesFailed> publisher;
    private final LongSupplier clockMs;
    private final Map<NodeId, Baseline> baselines = new HashMap<>();
    private final Map<NodeId, Long> lastAdvanceMs = new HashMap<>();
    private final Deque<Sample> samples = new ArrayDeque<>();
    private final Set<Artifact> latched = new HashSet<>();

    private AllInstancesFailedDetector(Supplier<Map<NodeId, MetricObservation>> observations,
                                       Supplier<Map<Artifact, Set<NodeId>>> activeInstances,
                                       BooleanSupplier isLeader,
                                       Consumer<AllInstancesFailed> publisher,
                                       LongSupplier clockMs) {
        this.observations = observations;
        this.activeInstances = activeInstances;
        this.isLeader = isLeader;
        this.publisher = publisher;
        this.clockMs = clockMs;
    }

    public static AllInstancesFailedDetector allInstancesFailedDetector(Supplier<Map<NodeId, MetricObservation>> observations,
                                                                        Supplier<Map<Artifact, Set<NodeId>>> activeInstances,
                                                                        BooleanSupplier isLeader,
                                                                        Consumer<AllInstancesFailed> publisher,
                                                                        LongSupplier clockMs) {
        return new AllInstancesFailedDetector(observations, activeInstances, isLeader, publisher, clockMs);
    }

    /// One detection pass. Runs on a fixed cadence on every node; does nothing but forget on a non-leader.
    @Contract
    public synchronized void tick() {
        if (!isLeader.getAsBoolean()) {
            forget();

            return;
        }

        var now = clockMs.getAsLong();

        observations.get().forEach((node, observation) -> accept(node, observation, now));
        prune(now);
        activeInstances.get().forEach((artifact, hosts) -> evaluate(artifact, hosts, now));
    }

    @Contract
    private void forget() {
        baselines.clear();
        lastAdvanceMs.clear();
        samples.clear();
        latched.clear();
    }

    @Contract
    private void accept(NodeId node, MetricObservation observation, long now) {
        var previous = Option.option(baselines.get(node));

        if (previous.filter(baseline -> baseline.incarnation() == observation.incarnation() && baseline.sequence() >= observation.sequence())
                    .isPresent()) {
            return;
        }

        previous.filter(baseline -> baseline.incarnation() == observation.incarnation())
                .onPresent(baseline -> recordDeltas(node, baseline, observation, now));
        baselines.put(node,
                      new Baseline(observation.incarnation(), observation.sequence(), executionValues(observation)));
        lastAdvanceMs.put(node, now);
    }

    private static Map<String, Double> executionValues(MetricObservation observation) {
        var values = new HashMap<String, Double>();

        observation.values()
                   .forEach((key, value) -> ExecutionOutcomeKeys.parse(key).onPresent(_ -> values.put(key, value)));

        return Map.copyOf(values);
    }

    @Contract
    private void recordDeltas(NodeId node, Baseline baseline, MetricObservation observation, long now) {
        observation.values()
                   .forEach((key, value) -> ExecutionOutcomeKeys.parse(key).onPresent(parsed -> recordDelta(node,
                                                                                                            parsed,
                                                                                                            value - baseline.values()
                                                                                                                            .getOrDefault(key,
                                                                                                                                          0.0),
                                                                                                            now)));
    }

    /// A negative delta is a counter reset (restart within an incarnation we could not see): it is a fresh
    /// baseline, never a negative count.
    @Contract
    private void recordDelta(NodeId node, ParsedKey parsed, double delta, long now) {
        if (delta <= 0) {
            return;
        }

        var count = (long) delta;

        samples.addLast(parsed.defect()
                        ? new Sample(now, node, parsed.artifact(), parsed.method(), 0, count)
                        : new Sample(now, node, parsed.artifact(), parsed.method(), count, 0));
    }

    @Contract
    private void prune(long now) {
        var horizon = now - WINDOW.millis();

        while (!samples.isEmpty() && samples.peekFirst().atMs() < horizon) {
            samples.removeFirst();
        }
    }

    @Contract
    private void evaluate(Artifact artifact, Set<NodeId> hosts, long now) {
        if (isFailing(artifact, hosts, now)) {
            publishOnce(artifact, hosts);
        } else {
            latched.remove(artifact);
        }
    }

    private boolean isFailing(Artifact artifact, Set<NodeId> hosts, long now) {
        return ! hosts.isEmpty() && hosts.stream()
                                         .allMatch(host -> hostFailing(artifact, host, now));
    }

    private boolean hostFailing(Artifact artifact, NodeId host, long now) {
        var fresh = Option.option(lastAdvanceMs.get(host))
                          .filter(advancedAt -> now - advancedAt <= FRESHNESS.millis())
                          .isPresent();

        return fresh
               && sum(artifact, host, true) >= DEFECT_THRESHOLD
               && sum(artifact, host, false) == 0;
    }

    private long sum(Artifact artifact, NodeId host, boolean defects) {
        return samples.stream()
                      .filter(sample -> sample.node()
                                              .equals(host) && sample.artifact()
                                                                     .equals(artifact))
                      .mapToLong(sample -> countOf(sample, defects))
                      .sum();
    }

    private static long countOf(Sample sample, boolean defects) {
        return defects
               ? sample.defects()
               : sample.successes();
    }

    @Contract
    private void publishOnce(Artifact artifact, Set<NodeId> hosts) {
        if (!latched.add(artifact)) {
            return;
        }

        var method = worstMethod(artifact);
        var nodes = hosts.stream().sorted(Comparator.comparing(NodeId::id)).toList();

        log.error("ALL INSTANCES FAILED: every ACTIVE instance of {} ({}) recorded >= {} slice defects and no success "
                 + "within {}; worst method {}",
                  artifact,
                  nodes,
                  DEFECT_THRESHOLD,
                  WINDOW,
                  method);
        MethodName.methodName(method)
                  .onSuccess(methodName -> publisher.accept(event(artifact,
                                                                  methodName,
                                                                  nodes,
                                                                  evidence(artifact, nodes))))
                  .onFailure(cause -> log.error("ALL INSTANCES FAILED for {} not published: method {} unparsable: {}",
                                                artifact,
                                                method,
                                                cause.message()));
    }

    private static AllInstancesFailed event(Artifact artifact,
                                            MethodName method,
                                            List<NodeId> nodes,
                                            Map<NodeId, Long> evidence) {
        return AllInstancesFailed.allInstancesFailed("all-instances-failed:" + artifact.asString(),
                                                     artifact,
                                                     method,
                                                     Option.some(Causes.cause("Every ACTIVE instance recorded only slice defects within " + WINDOW)),
                                                     nodes,
                                                     evidence,
                                                     WINDOW.millis());
    }

    /// Each hosting node's defects for `artifact` within the window — the evidence a rollback reports.
    private Map<NodeId, Long> evidence(Artifact artifact, List<NodeId> nodes) {
        var result = new HashMap<NodeId, Long>();

        nodes.forEach(node -> result.put(node, sum(artifact, node, true)));

        return Map.copyOf(result);
    }

    private String worstMethod(Artifact artifact) {
        var defectsByMethod = new HashMap<String, Long>();

        samples.stream()
               .filter(sample -> sample.artifact()
                                       .equals(artifact))
               .forEach(sample -> defectsByMethod.merge(sample.method(),
                                                        sample.defects(),
                                                        Long::sum));

        return defectsByMethod.entrySet()
                              .stream()
                              .max(Map.Entry.comparingByValue())
                              .map(Map.Entry::getKey)
                              .orElse("unknown");
    }
}
