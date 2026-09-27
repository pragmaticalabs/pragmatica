package org.pragmatica.consensus.rabia;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/// Deterministic simulation of genesis view agreement (#1526, owner design). Each iteration is seeded
/// and drives [GenesisViewAgreement] instances through randomized message delay and loss, network
/// partitions that open and heal during genesis, nodes whose visibility flaps, and late nodes.
///
/// SAFETY, asserted after every tick of every iteration: no two nodes ever hold different epoch-0
/// configurations (started or joined). The universe is kept below twice the configured count: with
/// twice the count or more, a partition splitting two exactly-count groups can form two disjoint
/// clusters, which this rule cannot close (see [GenesisViewAgreement]; `cluster.genesis_voters` closes it).
///
/// LIVENESS: on a stable connected network with exactly the configured count of cores, genesis
/// completes on every node, including after flapping or late arrival once visibility stabilises.
class GenesisViewAgreementSimulationTest {
    private static final int ITERATIONS = 2_000;
    private static final int MAX_TICKS = 400;

    @Test
    void safety_neverTwoDifferentEpochZeroConfigurations_underChaos() {
        var formed = 0;
        for (int seed = 0; seed < ITERATIONS; seed++) {
            var random = new Random(seed);
            var count = random.nextBoolean() ? 3 : 5;
            var extras = random.nextInt(count);
            var world = new World(random, count, count + extras, true);

            for (int tick = 0; tick < MAX_TICKS; tick++) {
                world.step(tick);
                world.assertSafe(seed, tick);
            }
            formed += world.configurations().isEmpty() ? 0 : 1;
        }
        assertThat(formed).as("the chaos schedules must form genesis often enough to exercise safety").isGreaterThan(ITERATIONS / 4);
    }

    @Test
    void liveness_stableConnectedExactCount_completesEverywhere() {
        for (int seed = 0; seed < ITERATIONS / 4; seed++) {
            var random = new Random(seed);
            var count = random.nextBoolean() ? 3 : 5;
            var world = new World(random, count, count, false);

            for (int tick = 0; tick < MAX_TICKS && !world.allConfigured(); tick++) {
                world.step(tick);
                world.assertSafe(seed, tick);
            }
            assertThat(world.allConfigured()).as("seed %s: genesis did not complete on a stable network", seed).isTrue();
            assertThat(world.configurations()).containsOnly(world.universe());
        }
    }

    @Test
    void liveness_flappingAndLateNodes_completeOnceVisibilityStabilises() {
        for (int seed = 0; seed < ITERATIONS / 4; seed++) {
            var random = new Random(seed);
            var count = random.nextBoolean() ? 3 : 5;
            var world = new World(random, count, count, true);

            for (int tick = 0; tick < MAX_TICKS && !world.allConfigured(); tick++) {
                world.stable = tick >= MAX_TICKS / 4;
                world.step(tick);
                world.assertSafe(seed, tick);
            }
            assertThat(world.allConfigured()).as("seed %s: genesis did not complete after visibility stabilised", seed).isTrue();
        }
    }

    @Test
    void moreCandidatesThanConfigured_nobodyStarts() {
        for (int seed = 0; seed < 50; seed++) {
            var world = new World(new Random(seed), 3, 4, false);

            for (int tick = 0; tick < MAX_TICKS; tick++) {
                world.step(tick);
            }
            assertThat(world.configurations()).as("seed %s", seed).isEmpty();
            assertThat(world.nodes.values()).allMatch(node -> node.agreement.status().stage() == GenesisViewAgreement.Stage.EXCEEDS_COUNT);
        }
    }

    private record Message(int deliverAt, long order, NodeId from, NodeId to, long round, Set<NodeId> view, Option<Set<NodeId>> formed) {}

    private static final class Node {
        private final NodeId id;
        private final GenesisViewAgreement agreement;
        private final int appearAt;
        private Option<Set<NodeId>> configuration = Option.none();

        Node(NodeId id, GenesisViewAgreement agreement, int appearAt) {
            this.id = id;
            this.agreement = agreement;
            this.appearAt = appearAt;
        }
    }

    private static final class World {
        private final Random random;
        private final boolean chaos;
        private final Map<NodeId, Node> nodes = new HashMap<>();
        private final PriorityQueue<Message> inFlight = new PriorityQueue<>((a, b) -> a.deliverAt() != b.deliverAt()
                                                                                       ? Integer.compare(a.deliverAt(), b.deliverAt())
                                                                                       : Long.compare(a.order(), b.order()));
        private final Map<NodeId, Integer> group = new HashMap<>();
        private final Set<NodeId> flappedOut = new HashSet<>();
        private long order;
        private boolean stable;

        World(Random random, int count, int size, boolean chaos) {
            this.random = random;
            this.chaos = chaos;
            for (int index = 0; index < size; index++) {
                var id = new NodeId("core-" + index);
                var appearAt = chaos && random.nextInt(3) == 0 ? random.nextInt(60) : 0;
                nodes.put(id, new Node(id, GenesisViewAgreement.genesisViewAgreement(id, count, Option.none()), appearAt));
                group.put(id, 0);
            }
        }

        Set<NodeId> universe() {
            return Set.copyOf(nodes.keySet());
        }

        void step(int tick) {
            if (chaos && !stable) {
                perturb();
            } else {
                group.replaceAll((_, _) -> 0);
                flappedOut.clear();
            }
            deliver(tick);
            for (var node : nodes.values()) {
                if (tick < node.appearAt || node.configuration.isPresent() || flappedOut.contains(node.id)) {
                    continue;
                }
                var report = node.agreement.tick(visibleTo(node.id, tick));
                for (var target : recipients(node, report.view(), tick)) {
                    send(tick, node.id, target, report.round(), report.view(), Option.none());
                }
                node.agreement.agreed().onPresent(view -> node.configuration = Option.some(view));
            }
        }

        private void perturb() {
            if (random.nextInt(20) == 0) {
                var groups = 1 + random.nextInt(3);
                group.replaceAll((_, _) -> random.nextInt(groups));
            }
            for (var id : nodes.keySet()) {
                if (random.nextInt(15) == 0) {
                    if (!flappedOut.remove(id)) {
                        flappedOut.add(id);
                    }
                }
            }
        }

        private Set<NodeId> visibleTo(NodeId id, int tick) {
            var visible = new HashSet<NodeId>();
            for (var other : nodes.values()) {
                if (reachable(id, other.id, tick)) {
                    visible.add(other.id);
                }
            }
            visible.add(id);
            return visible;
        }

        private boolean reachable(NodeId from, NodeId to, int tick) {
            var target = nodes.get(to);
            return tick >= target.appearAt && tick >= nodes.get(from).appearAt && !flappedOut.contains(to) && !flappedOut.contains(from)
                   && group.get(from).equals(group.get(to));
        }

        private List<NodeId> recipients(Node node, Set<NodeId> view, int tick) {
            var targets = new HashSet<>(view);
            targets.addAll(visibleTo(node.id, tick));
            targets.remove(node.id);
            return new ArrayList<>(targets);
        }

        private void send(int tick, NodeId from, NodeId to, long round, Set<NodeId> view, Option<Set<NodeId>> formed) {
            if (!reachable(from, to, tick) || (chaos && !stable && random.nextInt(10) == 0)) {
                return;
            }
            var delay = chaos && !stable ? random.nextInt(6) : random.nextInt(2);
            inFlight.add(new Message(tick + delay, order++, from, to, round, view, formed));
        }

        private void deliver(int tick) {
            while (!inFlight.isEmpty() && inFlight.peek().deliverAt() <= tick) {
                var message = inFlight.poll();
                var receiver = nodes.get(message.to());
                if (message.formed().isPresent()) {
                    // Joining a formed electorate: adopt it only from one of its members.
                    if (receiver.configuration.isEmpty() && message.formed().unwrap().contains(message.from())) {
                        receiver.configuration = message.formed();
                    }
                    continue;
                }
                if (receiver.configuration.isPresent()) {
                    send(tick, receiver.id, message.from(), 0, Set.of(), receiver.configuration);
                    continue;
                }
                receiver.agreement.receive(message.from(), message.round(), message.view());
                receiver.agreement.agreed().onPresent(view -> receiver.configuration = Option.some(view));
            }
        }

        Set<Set<NodeId>> configurations() {
            var configurations = new HashSet<Set<NodeId>>();
            nodes.values().forEach(node -> node.configuration.onPresent(configurations::add));
            return configurations;
        }

        boolean allConfigured() {
            return nodes.values().stream().allMatch(node -> node.configuration.isPresent());
        }

        void assertSafe(int seed, int tick) {
            assertThat(configurations()).as("seed %s tick %s: more than one epoch-0 configuration formed", seed, tick).hasSizeLessThanOrEqualTo(1);
        }
    }
}
