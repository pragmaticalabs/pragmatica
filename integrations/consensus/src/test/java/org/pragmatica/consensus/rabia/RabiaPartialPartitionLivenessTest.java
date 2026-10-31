package org.pragmatica.consensus.rabia;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pragmatica.consensus.StateMachine.Batch;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestCommand;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Asynchronous.NewBatch;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.Decision;
import org.pragmatica.consensus.rabia.ScheduledCluster.Delivery;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/// Qsync-scenario liveness probe (Q6 brief section 6.1): can the real `RabiaEngine` stall completely under a partial
/// or asymmetric partition while the voters hold different pending queues?
///
/// Every cell cuts directed edges, never delivers a `NewBatch` between voters (so the only way a batch spreads is
/// `learnProposedBatch` on a received `Propose`), and submits batches at its own submitters through the engine's real
/// submit path (`apply`: the batch enters the submitter's own pending queue, RabiaEngine `registerBatch`/`mergePending`).
///
/// A stall is: the liveness condition L holds (some majority is pairwise connected in both directions) and no
/// command commits on any member of that majority for [#BOUND] delivery steps. The seed is printed on failure. Engine
/// timers run on wall-clock time, so the delivery schedule is seeded but not bit-for-bit reproducible.
class RabiaPartialPartitionLivenessTest {
    /// The harness's existing `pumpUntil` step budget (ScheduledCluster.pumpUntil).
    private static final int BOUND = 30_000;
    private static final int SEEDS = 12;
    private static final int FLIPS = 20;
    private static final int HARD_CAP = 20 * BOUND;
    private static final int PENTAGON_CUT_STEPS = 3_000;

    private static final ConcurrentLinkedQueue<Run> RESULTS = new ConcurrentLinkedQueue<>();
    private final List<ScheduledCluster> clusters = new ArrayList<>();

    @AfterEach
    void stopClusters() {
        clusters.forEach(ScheduledCluster::stop);
    }

    @AfterAll
    static void printTable() {
        var byCell = new TreeMap<String, List<Run>>();
        RESULTS.forEach(run -> byCell.computeIfAbsent(run.cell(), _ -> new ArrayList<>()).add(run));
        var medians = new TreeMap<String, Integer>();
        byCell.forEach((cell, runs) -> medians.put(cell, median(runs.stream().map(Run::steps).sorted().toList())));
        byCell.forEach((cell, runs) -> {
            var steps = runs.stream().map(Run::steps).sorted().toList();
            var v0 = runs.stream().map(Run::v0Slots).sorted().toList();
            System.out.printf("QSYNC-TABLE cell=%s n=%d runs=%d stalls=%d steps(median=%d max=%d) v0(median=%d max=%d) cutEdgesBlockedMin=%d%n",
                              cell, runs.getFirst().n(), runs.size(), runs.stream().filter(Run::stalled).count(),
                              median(steps), steps.getLast(), median(v0), v0.getLast(),
                              runs.stream().mapToInt(Run::minCutBlocked).min().orElse(0));
            var control = medians.get("C4-" + cell);
            if (control != null && !cell.startsWith("C4-") && median(steps) > 10 * Math.max(1, control)) {
                System.out.printf("QSYNC-FINDING cell=%s median steps %d exceeds 10x the fully connected control median %d%n",
                                  cell, median(steps), control);
            }
        });
    }

    private static int median(List<Integer> sorted) {
        return sorted.get(sorted.size() / 2);
    }

    static IntStream seeds() {
        return IntStream.range(0, SEEDS);
    }

    // ---------------------------------------------------------------- cells

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    void q1_mutualCutBetweenSubmitters_everyBatchCommits(int seed) {
        runCell("Q1", 3, seed, q1(), uniform("q1", List.of(0, 2), 2));
    }

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    void q2_asymmetricCut_everyBatchCommits(int seed) {
        runCell("Q2", 3, seed, Links.fullyConnected(3).cut(Set.of(0), Set.of(2)), uniform("q2", List.of(0, 2), 2));
    }

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    void q3_bridgeNode_everyBatchCommits(int seed) {
        runCell("Q3", 5, seed, q3(), uniform("q3", List.of(0, 4), 2));
    }

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    void q4_oneWayFan_everyBatchCommits(int seed) {
        runCell("Q4", 5, seed, q4(), uniform("q4", List.of(0, 1, 3, 4), 2));
    }

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    void q5_noConnectedQuorum_staysSafeThenRecoversAfterHealing(int seed) {
        var probe = new Probe("Q5", 5, seed, Links.pentagon());
        assertThat(probe.majorities()).as("Q5 has no pairwise-connected majority (L false)").isEmpty();
        uniform("q5", List.of(0, 1, 2, 3, 4), 1).accept(probe);
        // During the cut only safety is required: pumpStep checks the prefix property after every step.
        for (int step = 0; step < PENTAGON_CUT_STEPS; step++) {
            probe.cluster.pumpStep(() -> false);
        }
        Links.pentagon().downEdges().forEach(edge -> assertThat(probe.cutBlocked[edge[0]][edge[1]]).as("Q5 seed=%s cut edge %s->%s lost traffic", seed, edge[0], edge[1]).isPositive());
        probe.links = Links.fullyConnected(5);
        finish(probe, 5);
    }

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    void q6_flappingEvery50Steps_everyBatchCommitsOnceStable(int seed) {
        flapping(seed, 50);
    }

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    void q6_flappingEvery500Steps_everyBatchCommitsOnceStable(int seed) {
        flapping(seed, 500);
    }

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    void q7_headInversion_learnedBatchIsReProposed(int seed) {
        var probe = new Probe("Q7", 3, seed, q1());
        q7Workload(probe).accept(probe);
        finish(probe, 4);
        System.out.printf("QSYNC-Q7 seed=%d first command applied at voter-0: %s%n", seed, probe.cluster.machines.getFirst().getProcessedCommands().getFirst());
    }

    // ---------------------------------------------------------------- controls

    /// C4: with no cut and the same submitters, every cell's shape commits within a tenth of the bound.
    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    void c4_fullyConnectedControl_commitsWithinATenthOfTheBound(int seed) {
        control("Q1", 3, seed, uniform("q1", List.of(0, 2), 2), 4);
        control("Q3", 5, seed, uniform("q3", List.of(0, 4), 2), 4);
        control("Q4", 5, seed, uniform("q4", List.of(0, 1, 3, 4), 2), 8);
        control("Q5", 5, seed, uniform("q5", List.of(0, 1, 2, 3, 4), 1), 5);
        var q7 = new Probe("C4-Q7", 3, seed, Links.fullyConnected(3));
        q7Workload(q7).accept(q7);
        finish(q7, 4);
        assertThat(q7.run.steps()).as("C4 Q7 shape seed=%s", seed).isLessThanOrEqualTo(BOUND / 10);
    }

    /// C3: L is computed from the cut set; Q5 is the one cell without a connected majority (hand-computed answers).
    @Test
    void c3_livenessCondition_isFalseOnlyForThePentagon() {
        assertThat(Links.pentagon().majorities()).isEmpty();
        assertThat(Links.fullyConnected(3).majorities()).contains(Set.of(0, 1), Set.of(1, 2), Set.of(0, 2), Set.of(0, 1, 2));
        assertThat(q1().majorities()).containsExactlyInAnyOrder(Set.of(0, 1), Set.of(1, 2));
        assertThat(Links.fullyConnected(3).cut(Set.of(0), Set.of(2)).majorities()).containsExactlyInAnyOrder(Set.of(0, 1), Set.of(1, 2));
        assertThat(q3().majorities()).contains(Set.of(0, 1, 2), Set.of(2, 3, 4)).doesNotContain(Set.of(0, 3, 4), Set.of(1, 3, 4));
        assertThat(q4().majorities()).contains(Set.of(0, 1, 2), Set.of(2, 3, 4)).doesNotContain(Set.of(0, 3, 4));
        assertThat(union(q3().majorities())).containsExactlyInAnyOrder(0, 1, 2, 3, 4);
        assertThat(union(q4().majorities())).containsExactlyInAnyOrder(0, 1, 2, 3, 4);
    }

    // ---------------------------------------------------------------- drivers

    private void runCell(String cell, int n, int seed, Links links, java.util.function.Consumer<Probe> workload) {
        var probe = new Probe(cell, n, seed, links);
        assertThat(probe.majorities()).as("%s has a pairwise-connected majority (L true)", cell).isNotEmpty();
        workload.accept(probe);
        finish(probe, probe.submitted);
    }

    private void control(String shape, int n, int seed, java.util.function.Consumer<Probe> workload, int expected) {
        var probe = new Probe("C4-" + shape, n, seed, Links.fullyConnected(n));
        workload.accept(probe);
        finish(probe, expected);
        assertThat(probe.run.steps()).as("C4 %s shape seed=%s commits within a tenth of the bound", shape, seed).isLessThanOrEqualTo(BOUND / 10);
    }

    private void flapping(int seed, int period) {
        var cell = "Q6-" + period;
        var states = List.of(q3(), q4(), Links.fullyConnected(5));
        var random = new Random(1_000L * period + seed);
        var probe = new Probe(cell, 5, seed, states.getFirst());
        for (int flip = 0; flip < FLIPS; flip++) {
            probe.links = states.get(random.nextInt(states.size()));
            assertThat(probe.majorities()).as("%s flip %s keeps L true", cell, flip).isNotEmpty();
            probe.submit(flip % 5, "q6-" + period + "-" + flip);
            for (int step = 0; step < period; step++) {
                probe.cluster.pumpStep(() -> false);
            }
        }
        finish(probe, FLIPS);
    }

    /// Drives the schedule until `expected` commands are applied on every voter in a connected majority, or until no
    /// command has committed on any of them for [#BOUND] steps (a stall), and asserts the controls.
    private void finish(Probe probe, int expected) {
        clusters.add(probe.cluster);
        var required = union(probe.majorities());
        BooleanSupplier done = () -> required.stream().allMatch(index -> probe.processed(index) >= expected);
        var lastProgress = 0;
        var lastTotal = probe.total(required);
        var steps = -1;
        for (int step = 1; step <= HARD_CAP && steps < 0; step++) {
            if (probe.cluster.pumpStep(done)) {
                steps = step;
            } else if (probe.total(required) != lastTotal) {
                lastTotal = probe.total(required);
                lastProgress = step;
            } else if (step - lastProgress >= BOUND) {
                break;
            }
        }
        if (steps < 0) {
            probe.record(false, HARD_CAP);
            fail(probe.stallReport(required, expected));
        }
        probe.cluster.settle();
        probe.record(true, steps);
        probe.assertControls();
        required.forEach(index -> assertThat(probe.processed(index)).as("%s seed=%s voter-%s applies every batch", probe.cell, probe.seed, index)
                                                                    .isEqualTo(expected));
    }

    // ---------------------------------------------------------------- workloads

    private static java.util.function.Consumer<Probe> uniform(String prefix, List<Integer> submitters, int perSubmitter) {
        return probe -> submitters.forEach(submitter -> IntStream.range(0, perSubmitter)
                                                                  .forEach(index -> probe.submit(submitter, prefix + "-s" + submitter + "-b" + index)));
    }

    /// Voter 0 (A) and voter 2 (C) submit two batches each under Q1's cut; every one of C's batch ids sorts below
    /// every one of A's, so C's queue head is the global minimum and reaches A only through B's re-proposal.
    private static java.util.function.Consumer<Probe> q7Workload(Probe sizing) {
        var serializer = sizing.cluster.machines.getFirst().serializer();
        Function<String, Batch.Id> idOf = value -> Batch.create(serializer, List.of(new TestCommand(value))).id();
        var aliceValues = List.of("q7-a-0", "q7-a-1");
        var smallestA = aliceValues.stream().map(idOf).min(Comparator.naturalOrder()).orElseThrow();
        var cValues = new ArrayList<String>();
        for (int candidate = 0; cValues.size() < 2; candidate++) {
            var value = "q7-c-" + candidate;
            if (idOf.apply(value).compareTo(smallestA) < 0) {
                cValues.add(value);
            }
        }
        return probe -> {
            aliceValues.forEach(value -> probe.submit(0, value));
            cValues.forEach(value -> probe.submit(2, value));
        };
    }

    // ---------------------------------------------------------------- cuts

    private static Links q1() {
        return Links.fullyConnected(3).cut(Set.of(0), Set.of(2)).cut(Set.of(2), Set.of(0));
    }

    private static Links q3() {
        return Links.fullyConnected(5).cut(Set.of(0, 1), Set.of(3, 4)).cut(Set.of(3, 4), Set.of(0, 1));
    }

    private static Links q4() {
        return Links.fullyConnected(5).cut(Set.of(3, 4), Set.of(0, 1));
    }

    private static Set<Integer> union(List<Set<Integer>> sets) {
        var all = new TreeSet<Integer>();
        sets.forEach(all::addAll);
        return all;
    }

    /// Directed link matrix: `up[source][target]`. Immutable by convention; every cut returns a copy.
    private record Links(boolean[][] up) {
        static Links fullyConnected(int size) {
            var up = new boolean[size][size];
            for (var row : up) {
                java.util.Arrays.fill(row, true);
            }
            return new Links(up);
        }

        /// Each node linked in both directions only to its two ring neighbours: no three nodes are pairwise linked.
        static Links pentagon() {
            var up = new boolean[5][5];
            for (int index = 0; index < 5; index++) {
                up[index][(index + 1) % 5] = true;
                up[(index + 1) % 5][index] = true;
            }
            return new Links(up);
        }

        int size() {
            return up.length;
        }

        boolean isUp(int source, int target) {
            return source == target || up[source][target];
        }

        /// Loses every delivery `from -> to` (one direction only; call twice for a mutual cut).
        Links cut(Set<Integer> from, Set<Integer> to) {
            var copy = new boolean[size()][];
            for (int index = 0; index < size(); index++) {
                copy[index] = up[index].clone();
            }
            from.forEach(source -> to.forEach(target -> copy[source][target] = false));
            return new Links(copy);
        }

        /// L: every majority whose directed links are all up inside it.
        List<Set<Integer>> majorities() {
            var found = new ArrayList<Set<Integer>>();
            for (int mask = 1; mask < (1 << size()); mask++) {
                var members = new TreeSet<Integer>();
                for (int index = 0; index < size(); index++) {
                    if ((mask & (1 << index)) != 0) {
                        members.add(index);
                    }
                }
                if (members.size() > size() / 2 && allUp(members)) {
                    found.add(members);
                }
            }
            return found;
        }

        private boolean allUp(Set<Integer> members) {
            return members.stream().allMatch(source -> members.stream().allMatch(target -> isUp(source, target)));
        }

        List<int[]> downEdges() {
            var edges = new ArrayList<int[]>();
            for (int source = 0; source < size(); source++) {
                for (int target = 0; target < size(); target++) {
                    if (!isUp(source, target)) {
                        edges.add(new int[]{source, target});
                    }
                }
            }
            return edges;
        }
    }

    private record Run(String cell, int n, int seed, int steps, boolean stalled, int v0Slots, int minCutBlocked) {}

    /// One cell instance: a real cluster behind a counting loss predicate.
    private final class Probe {
        final String cell;
        final int n;
        final int seed;
        final ScheduledCluster cluster;
        volatile Links links;
        final int[][] cutBlocked;
        final int[][] passed;
        int newBatchBlocked;
        int submitted;
        Run run;

        Probe(String cell, int n, int seed, Links links) {
            this.cell = cell;
            this.n = n;
            this.seed = seed;
            this.links = links;
            this.cutBlocked = new int[n][n];
            this.passed = new int[n][n];
            this.cluster = new ScheduledCluster(n, seed);
            clusters.add(cluster);
            cluster.start();
            cluster.blocked = this::lose;
        }

        /// The one choke point of the instrument: NewBatch is lost everywhere, every other message on a down edge is lost.
        private boolean lose(Delivery delivery) {
            if (delivery.message() instanceof NewBatch<?>) {
                newBatchBlocked++;
                return true;
            }
            if (!links.isUp(delivery.source(), delivery.target())) {
                cutBlocked[delivery.source()][delivery.target()]++;
                return true;
            }
            passed[delivery.source()][delivery.target()]++;
            return false;
        }

        /// The real submit path: the batch enters this voter's own pending queue; the NewBatch it broadcasts is lost.
        void submit(int submitter, String command) {
            cluster.engines.get(submitter).<String> apply(List.of(new TestCommand(command)));
            cluster.settle();
            submitted++;
        }

        List<Set<Integer>> majorities() {
            return links.majorities();
        }

        int processed(int index) {
            return cluster.machines.get(index).getProcessedCommands().size();
        }

        int total(Set<Integer> members) {
            return members.stream().mapToInt(this::processed).sum();
        }

        void record(boolean committed, int steps) {
            var v0 = (int) cluster.sent.stream()
                                       .filter(Decision.class::isInstance)
                                       .map(message -> (Decision<?>) message)
                                       .filter(decision -> decision.stateValue() == StateValue.V0)
                                       .map(Decision::phase)
                                       .distinct()
                                       .count();
            var minBlocked = links.downEdges().stream().mapToInt(edge -> cutBlocked[edge[0]][edge[1]]).min().orElse(0);
            run = new Run(cell, n, seed, steps, !committed, v0, minBlocked);
            RESULTS.add(run);
            System.out.printf("QSYNC-RUN cell=%s n=%d seed=%d committed=%s steps=%d v0Slots=%d newBatchBlocked=%d minCutEdgeBlocked=%d%n",
                              cell, n, seed, committed, steps, v0, newBatchBlocked, minBlocked);
        }

        /// C1: every cut edge saw (and lost) traffic, no delivery passed on a cut edge, every up edge carried traffic.
        void assertControls() {
            if (cell.startsWith("Q6")) {
                assertThat(newBatchBlocked).as("%s seed=%s NewBatch broadcasts were lost", cell, seed).isPositive();
                return;
            }
            var last = links;
            last.downEdges().forEach(edge -> {
                assertThat(cutBlocked[edge[0]][edge[1]]).as("%s seed=%s cut edge %s->%s lost traffic", cell, seed, edge[0], edge[1]).isPositive();
                assertThat(passed[edge[0]][edge[1]]).as("%s seed=%s nothing passed on cut edge %s->%s", cell, seed, edge[0], edge[1]).isZero();
            });
            if (!cell.startsWith("Q5")) {
                for (int source = 0; source < n; source++) {
                    for (int target = 0; target < n; target++) {
                        if (source != target && last.isUp(source, target)) {
                            assertThat(cutBlocked[source][target]).as("%s seed=%s up edge %s->%s never lost a message", cell, seed, source, target).isZero();
                            assertThat(passed[source][target]).as("%s seed=%s up edge %s->%s carried traffic", cell, seed, source, target).isPositive();
                        }
                    }
                }
            }
            assertThat(newBatchBlocked).as("%s seed=%s NewBatch broadcasts were lost, never delivered", cell, seed).isPositive();
        }

        String stallReport(Set<Integer> required, int expected) {
            var counts = new ArrayList<String>();
            for (int index = 0; index < n; index++) {
                counts.add("voter-%d(processed=%d pending=%d)".formatted(index, processed(index), cluster.engines.get(index).pendingBatchCountForTesting()));
            }
            var tail = new ArrayList<>(cluster.sent);
            var last = tail.subList(Math.max(0, tail.size() - 12), tail.size());
            return "STALL cell=%s n=%d seed=%d: no command committed on majority members %s for %d steps (expected %d on each); %s; deliveriesPending=%d; last protocol messages=%s"
                .formatted(cell, n, seed, required, BOUND, expected, counts, cluster.pending.size(), last);
        }
    }
}
