// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.forge;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.awaitility.core.ConditionTimeoutException;
import org.pragmatica.aether.dht.EntityPartitionArc;
import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.lang.Option;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.pragmatica.http.JdkHttpOperations.jdkHttpOperations;

/// #345 increment I4 — the two gates that make a durable timer DURABLE (#351).
///
/// ## The question this suite exists to answer
///
/// [DurableEntityForgeTest] proves timers are real: a schedule answers with a token from every node, and a
/// re-sent token applies its effect exactly once. Neither of those needs the timer to survive anything. A
/// timer wheel held in the scheduling process would pass both and then lose every pending timer the moment
/// its holder was replaced — which is the exact failure this increment is about, and the reason "durable"
/// is in the name rather than "scheduled".
///
/// So this suite asks the two questions that separate a durable timer from an in-memory one:
///
///   1. Does a pending timer survive its owner being REPLACED?
///      ([#timerFires_afterOwnerHandover_appliedOnceByTheNewOwner])
///   2. Does it survive the whole cluster going away and coming back?
///      ([#timerFires_afterFullClusterRestart_fromTheRecoveredLog])
///
/// The mechanism it is measuring: a pending timer is a `TIMER_SCHEDULE` record in the entity's own fenced,
/// replicated, WAL-backed log, and the pending set is FOLDED from that log rather than held beside it.
/// There is no wheel to lose. These gates are what turns that from a design claim into a measured one.
///
/// ## Why a separate cluster from [DurableEntityForgeTest]
///
/// Two reasons, both structural. The restart gate needs a restart-stable per-node data dir
/// (`withDataBaseDir(@TempDir)`) so the backing stream's WAL is on disk; that is a cluster-construction
/// choice, not a per-test one, and forcing it on the API-surface suite would change what every test there
/// measures. And the handover gate KILLS a node, which the sibling suite defers to its very last test
/// precisely because a shrunk cluster invalidates the exactly-one-acceptance helper every other test there
/// relies on.
///
/// ## The effect is read from STATE, never from logs
///
/// The fixture's timer fires `OrderCommand.Expire`, which sets the status to `expired` and INCREMENTS
/// `OrderState.expiries`. Both gates assert on the post-fire state through the ordinary `get` path. The
/// counter is what makes "exactly once" assertable: a status flip alone is idempotent, so it could not tell
/// one fire from two.
///
/// ## Choosing the delays
///
/// Each gate schedules with a delay tuned to the disruption it is testing, and the same reasoning governs
/// both: **the timer must demonstrably still be PENDING when the disruption starts.** If it fires first,
/// the gate proves only that timers fire, which the sibling suite already proves. Both gates therefore
/// (a) read the state immediately before the disruption and require `expiries == 0`, and (b) assert the
/// MEASURED schedule-to-disruption elapsed against a quarter of the delay, so the margin is checked rather
/// than assumed. Past that point the old owner is gone, so any fire that lands came from the recovered log.
///
/// Measured on a 2026-08-27 local run, and the two gates landed on opposite sides of the interesting line:
///
/// | Gate | delay | schedule→disruption | disruption | fire |
/// |------|-------|---------------------|------------|------|
/// | restart  | 30,000 ms | 27 ms | 42,166 ms (stop→serving) | 1,032 ms after ready |
/// | handover | 45,000 ms | 25 ms | 9,355 ms (kill→new committed owner) | 36,668 ms after handover |
///
/// The restart's downtime OUTLASTED its delay, so that timer came due with nobody alive to fire it and
/// fired one tick after the cluster served again — the "late, never lost" clause exercised directly. The
/// handover's did not, so that timer was still pending with ~35s to run when a node that had never seen the
/// schedule inherited it, and fired on its ORIGINAL instant. Both are legal, and having one of each is
/// better coverage than two of either.
///
/// Firing LATE is therefore not a failure and is not guarded against. The gates measure the window and
/// report it rather than constraining it.
@Tag("Heavy")
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DurableEntityTimerDurabilityTest {
    private static final System.Logger LOG = System.getLogger(DurableEntityTimerDurabilityTest.class.getName());

    private static final int BASE_PORT = 22000;
    private static final int BASE_MGMT_PORT = 22100;
    private static final int BASE_APP_HTTP_PORT = 22200;
    private static final int NODES = 5;
    /// Mirrors [DurableEntityForgeTest]'s 3-of-5: fewer instances than nodes keeps the leader's hosting-set
    /// rule under test, and it is also what makes the handover gate's kill safe — three instances still fit
    /// on the four surviving nodes, so the deployment reconciler re-places rather than declaring a node
    /// deficit and provisioning a replacement mid-gate.
    private static final int INSTANCES = 3;
    /// Must match `partition_count` in the fixture's `resources.toml`.
    private static final int ENTITY_PARTITIONS = 8;
    private static final String KEYSPACE = "orders";

    /// The production arc mapper, constructed with the fixture's own keyspace and partition count — not a
    /// re-implementation of the hash. It answers ONE question here: which ownership arc a key rides, so the
    /// handover gate can kill that arc's owner instead of an arbitrary node. Using production's own mapper
    /// for target SELECTION is not self-confirming, because nothing is asserted about the mapping: the gate
    /// independently verifies the arc's committed owner CHANGED after the kill, so a wrong target fails
    /// loudly rather than passing vacuously.
    private static final EntityPartitionArc ARC = EntityPartitionArc.entityPartitionArc(KEYSPACE, ENTITY_PARTITIONS);

    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(240);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);

    /// The handover gate's delay. It must comfortably outlive create + schedule + the pre-kill read — the
    /// timer has to be provably still pending when its owner dies, or a fire on the OLD owner would satisfy
    /// the gate for the wrong reason. That prelude measures 25 ms, so 45s is a 1,800x margin and the gate
    /// asserts the measured figure against a quarter of it. It is also generous enough that the new owner
    /// inherits a STILL-PENDING timer rather than an already-due one: the handover measures 9.4s, leaving
    /// ~35s of the delay to run, and the timer fires on its original instant. That is the more interesting
    /// of the two legal outcomes, and it is why this delay is deliberately larger than the restart gate's.
    private static final long HANDOVER_DELAY_MILLIS = 45_000L;

    /// The restart gate's delay, and it is deliberately SHORTER than the handover gate's rather than longer.
    /// It only has to outlive create + schedule + the pre-stop read (27 ms measured); it does NOT have to
    /// outlive the restart, because no process is alive during a restart that could fire anything. Measured,
    /// the restart takes 42.2s — longer than this delay — so the timer comes due while the cluster is down
    /// and fires one tick after it serves again. That is not a weaker result; it is the contract's "late,
    /// never lost" clause being exercised head-on. Sizing this to span the whole restart would have cost a
    /// minute per run and measured nothing extra.
    private static final long RESTART_DELAY_MILLIS = 30_000L;

    /// Quiet period before declaring a fire exactly-once. The entity timer tick is one second, so a
    /// duplicate timer planted alongside the original comes due within a tick of it; five ticks gives four
    /// spare for a second fire that must not happen to happen.
    private static final Duration EXACTLY_ONCE_SETTLE = Duration.ofSeconds(5);

    private static final String ENTITY_SLICE = TestArtifacts.ENTITY_SLICE;
    private static final String BLUEPRINT_ID = "forge.test:durable-entity-timers:1.0.0";
    private static final String ERROR_FALLBACK = "{\"error\":\"request failed\"}";
    private static final int REASON_EXCERPT_LIMIT = 300;
    private static final int PARTITION_PROBE_KEYS = 12;

    private static final Pattern NODE_COUNT_FIELD = Pattern.compile("\"nodeCount\"\\s*:\\s*(\\d+)");

    Path baseDir;

    private EmberCluster cluster;
    private final HttpOperations http = jdkHttpOperations();
    private final AtomicInteger ownershipProbe = new AtomicInteger();
    private final AtomicReference<List<String>> lastProbeAnswers = new AtomicReference<>(List.of());

    @BeforeAll
    void setUp(@TempDir Path tempDir) {
        this.baseDir = tempDir;

        // Resource provisioning is gated on a ConfigurationProvider being present; without it the node
        // installs a no-op facade and every resource-backed slice fails to load.
        var configProvider = ConfigurationProvider.builder()
                                                  .withSystemProperties("aether.")
                                                  .withEnvironment("AETHER_")
                                                  .build();

        cluster = emberCluster(NODES, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, "det", Option.some(configProvider));
        // The restart gate's whole premise: a restart-stable per-node data dir turns the disk tier and the
        // entity log's backing per-partition WAL on. Without it a full-cluster restart would lose the log
        // and the gate would be measuring the harness rather than the entity.
        cluster.withDataBaseDir(baseDir);

        startAndAwaitReady();
    }

    @AfterAll
    void tearDown() {
        if (cluster != null) {
            var leaderPort = cluster.getLeaderManagementPort().or(anyMgmtPort());

            httpDelete(leaderPort, "/api/v1/blueprints/" + BLUEPRINT_ID);
            cluster.stop()
                   .await();
        }
    }

    // --- gate 1: the timer survives the whole cluster going away ----------------

    /// GATE — a pending timer survives a full-cluster restart and fires afterwards.
    ///
    /// The state and the pending timer both live in the entity's log, which is backed by a per-partition
    /// WAL fsync'd before each append acknowledges. A graceful full-cluster `stop()` → `start()` preserving
    /// the per-node data dirs therefore loses every in-memory structure — folds, pending sets, the lot —
    /// and leaves the WAL as the only place either can come back from. Forge's Rabia persistence is
    /// in-memory, so the restart also wipes the KV: the blueprint is re-deployed afterwards, which restores
    /// the keyspace registration and nothing else. It carries no state and no timers.
    ///
    /// What makes it non-vacuous: `expiries` is read immediately before `stop()` and must be 0, and the
    /// measured schedule-to-stop elapsed is asserted against a quarter of the {@value #RESTART_DELAY_MILLIS}
    /// ms delay. A timer that had already fired would show as 1 there and fail. Past `stop()` there is no
    /// process alive that could fire anything, so the fire observed afterwards necessarily came from the
    /// recovered log.
    ///
    /// Measured: schedule→stop 27 ms against the 30,000 ms delay (a 1,100x margin against a 7,500 ms
    /// threshold); stop→serving-again 42,166 ms; first fire 1,032 ms — one tick — after the cluster was
    /// ready. So the timer came due during the downtime and fired at the first tick a live owner ran.
    ///
    /// The state is asserted too, not just the fire: a timer that fired against a default-constructed state
    /// would report `expiries == 1` while having lost everything else, so `amount` — which `Expire` does not
    /// touch — has to come back as written.
    @Test
    @Order(1)
    void timerFires_afterFullClusterRestart_fromTheRecoveredLog() {
        var key = "order-timer-restart";

        createOnOwner(key, "placed", 700);

        var scheduledAt = System.nanoTime();
        var scheduled = scheduleTimer(firstPort(), key, RESTART_DELAY_MILLIS);

        assertThat(outcome(scheduled)).describedAs("the schedule must be accepted before there is anything to survive")
                                      .isEqualTo("scheduled");
        assertThat(text(scheduled, "token")).describedAs("a scheduled timer answers with its handle").isNotEmpty();
        assertThat(expiriesAcrossCluster(key)).describedAs("the timer must still be PENDING when the cluster goes down; "
                                                           + "a fire before the restart would prove nothing")
                                              .isNotEmpty()
                                              .allSatisfy(count -> assertThat(count).isZero());

        var scheduleToStopMillis = elapsedMillis(scheduledAt);

        assertThat(scheduleToStopMillis).describedAs("the delay must comfortably outlive the prelude, else the pending-ness "
                                                     + "above is a coin flip rather than a margin")
                                        .isLessThan(RESTART_DELAY_MILLIS / 4);

        var restartStartedAt = System.nanoTime();

        restartCluster();

        var restartMillis = elapsedMillis(restartStartedAt);
        var firedAt = System.nanoTime();

        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> expiriesAcrossCluster(key).contains(1));

        var fireLatencyMillis = elapsedMillis(firedAt);

        LOG.log(System.Logger.Level.INFO,
                "I4 restart gate: delay={0}ms schedule->stop={1}ms restart={2}ms ready->fire={3}ms",
                RESTART_DELAY_MILLIS,
                scheduleToStopMillis,
                restartMillis,
                fireLatencyMillis);

        assertExactlyOneFire(key, 700);
    }

    // --- gate 2: the timer survives its owner being replaced --------------------

    /// GATE — a pending timer survives its partition's owner being replaced, and the new owner applies it
    /// exactly once.
    ///
    /// The handover is forced by killing the node holding the committed ownership record for the key's arc,
    /// and it is VERIFIED rather than assumed: the arc's committed owner before and after the kill must
    /// differ. That verification is what stops the gate passing vacuously if the wrong node were killed —
    /// the key's partition would never move, the timer would fire on its original owner, and every other
    /// assertion here would still hold.
    ///
    /// What makes the fire attributable to the NEW owner: `expiries` is read immediately before the kill and
    /// must be 0, with the measured schedule-to-kill elapsed asserted against a quarter of the
    /// {@value #HANDOVER_DELAY_MILLIS} ms delay. After the kill the old owner is not running, so it cannot
    /// fire anything — the timer that fires is one the new owner inherited by REPLAYING the log, which is
    /// the entire point of not keeping a wheel.
    ///
    /// Measured: schedule→kill 25 ms against the 45,000 ms delay (against an 11,250 ms threshold); the
    /// arc's committed owner changed 9,355 ms after the kill, leaving ~35s of the delay still to run; the
    /// fire landed 36,668 ms after that — on its original instant, applied by a node that never saw the
    /// schedule request.
    ///
    /// Ordered last because it shrinks the cluster.
    @Test
    @Order(2)
    void timerFires_afterOwnerHandover_appliedOnceByTheNewOwner() {
        var key = "order-timer-handover";
        var partition = ARC.partitionOf(key);

        createOnOwner(key, "placed", 300);

        var ownerBefore = arcOwner(partition);

        assertThat(ownerBefore).describedAs("the key's arc must carry a committed owner before it can lose one")
                               .isNotEmpty();

        var scheduledAt = System.nanoTime();
        var scheduled = scheduleTimer(firstPort(), key, HANDOVER_DELAY_MILLIS);

        assertThat(outcome(scheduled)).describedAs("the schedule must be accepted before there is anything to hand over")
                                      .isEqualTo("scheduled");
        assertThat(expiriesAcrossCluster(key)).describedAs("the timer must still be PENDING when its owner dies; a fire on "
                                                           + "the OLD owner would satisfy every later assertion for the "
                                                           + "wrong reason")
                                              .isNotEmpty()
                                              .allSatisfy(count -> assertThat(count).isZero());

        var scheduleToKillMillis = elapsedMillis(scheduledAt);

        assertThat(scheduleToKillMillis).describedAs("the delay must comfortably outlive the prelude, else the pending-ness "
                                                     + "above is a coin flip rather than a margin")
                                        .isLessThan(HANDOVER_DELAY_MILLIS / 4);

        var ownerPort = appPortForNodeId(ownerBefore);
        var killedAt = System.nanoTime();

        cluster.killNode(ownerBefore)
               .await()
               .onFailure(cause -> {
                   throw new AssertionError("Failed to stop the owner " + ownerBefore + ": " + cause.message());
               });

        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> !appPorts().contains(ownerPort));

        // The handover itself, asserted rather than assumed. Until this flips, the key's partition still
        // names a dead node as its owner and nothing can fire.
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> hasNewOwner(partition, ownerBefore));

        var handoverMillis = elapsedMillis(killedAt);
        var firedAt = System.nanoTime();

        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> expiriesAcrossCluster(key).contains(1));

        var fireLatencyMillis = elapsedMillis(firedAt);

        LOG.log(System.Logger.Level.INFO,
                "I4 handover gate: delay={0}ms schedule->kill={1}ms kill->newOwner={2}ms newOwner->fire={3}ms",
                HANDOVER_DELAY_MILLIS,
                scheduleToKillMillis,
                handoverMillis,
                fireLatencyMillis);

        assertThat(arcOwner(partition)).describedAs("the key's arc must have moved, else no handover was tested")
                                       .isNotEqualTo(ownerBefore);

        assertExactlyOneFire(key, 300);
    }

    // --- the shared post-fire assertion -----------------------------------------

    /// The exactly-once assertion both gates end on.
    ///
    /// A duplicate fire arrives on a LATER tick, so a reading taken the instant the first one lands cannot
    /// tell exactly-once from not-yet-twice. There is no condition to poll for the ABSENCE of a second fire,
    /// only elapsed ticks, so this waits out a quiet period and then re-reads.
    ///
    /// The count is read from every node currently serving the key rather than from a resolved owner: a
    /// duplicate shows as a 2 on whichever node folded both fires, and reading the whole cluster sees it
    /// wherever it lands. Replica views that have not yet folded the fire read 0, which is lag and not a
    /// defect — hence "no node above 1, and at least one at exactly 1" rather than "all equal 1".
    private void assertExactlyOneFire(String key, int originalAmount) {
        awaitQuietPeriod(EXACTLY_ONCE_SETTLE);

        var counts = expiriesAcrossCluster(key);

        assertThat(counts).describedAs("at least one node must serve '%s' after the fire", key).isNotEmpty();
        assertThat(counts).describedAs("the timer fires ONCE — a node reading 2 folded two fires of one schedule")
                          .allSatisfy(count -> assertThat(count).isLessThanOrEqualTo(1));
        assertThat(counts).describedAs("and it did fire, on some node's committed view").contains(1);

        var settled = servedView(key);

        assertThat(text(settled, "status")).describedAs("the fire is a real mutation applied through the ordinary update path")
                                           .isEqualTo("expired");
        assertThat(number(settled, "amount")).describedAs("the state itself survived: Expire leaves 'amount' alone, so a "
                                                          + "default-constructed state would show here")
                                             .isEqualTo(originalAmount);
    }

    // --- restart ------------------------------------------------------------------

    private void restartCluster() {
        cluster.stop()
               .await()
               .onFailure(cause -> {
                   throw new AssertionError("Cluster stop failed: " + cause.message());
               });

        startAndAwaitReady();
    }

    private void startAndAwaitReady() {
        cluster.start()
               .await()
               .onFailure(cause -> {
                   throw new AssertionError("Cluster start failed: " + cause.message());
               });

        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> cluster.currentLeader().isPresent());

        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(this::allNodesHealthy);

        // Gate on FULL membership, not merely on local quorum. After a cold restart the entity arcs are
        // re-minted over whichever nodes have rejoined, and an arc placed on a straggler-free subset would
        // hand the key's partition to a node whose WAL never held it.
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> allNodesAreMembers(NODES));

        // Forge's Rabia persistence is in-memory, so a full-cluster restart wipes the blueprint and with it
        // the keyspace registration. Re-deploying restores ONLY that: no state, no timers. Both come back
        // from the WAL-backed entity log when the new owner folds its partitions.
        deployEntitySlice();

        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .failFast(this::failIfSliceFailed)
               .until(this::entityReadyOnEveryNode);

        // Placement converges LATER than entity readiness — measured 2026-08-27, the scheduler's first pass
        // placed 2 of the 3 requested instances and the reconciler took a further ~52s to add the third.
        // The handover gate depends on this: it kills a host, and re-placement has room only if the full
        // three were there to begin with. Waiting here also keeps the gates measuring a converged cluster
        // rather than a mid-reconcile one.
        awaitFullSlicePlacement();

        awaitOwnershipConvergence();
    }

    /// Wait until the blueprint's full instance count is ACTIVE cluster-wide, reporting WHICH hosts were
    /// seen on expiry — "expected 3, saw 2" is diagnosable; "condition not met in 240s" is not.
    private void awaitFullSlicePlacement() {
        try {
            await().atMost(WAIT_TIMEOUT)
                   .pollInterval(POLL_INTERVAL)
                   .failFast(this::failIfSliceFailed)
                   .until(() -> activeSliceHosts().size() == INSTANCES);
        } catch (ConditionTimeoutException e) {
            throw new AssertionError("Slice placement never reached " + INSTANCES + " ACTIVE instances; hosts seen: "
                                     + activeSliceHosts(), e);
        }
    }

    /// Node ids hosting an ACTIVE instance of the entity slice, from the cluster-wide slice view. The suite
    /// deploys exactly one slice, so every `nodeId` in the filtered response belongs to it.
    private Set<String> activeSliceHosts() {
        var body = anyManagementAnswer("/api/v1/slices?state=ACTIVE");
        var matcher = Pattern.compile("\"nodeId\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        var hosts = new HashSet<String>();

        while (matcher.find()) {
            hosts.add(matcher.group(1));
        }

        return hosts;
    }

    /// Wait for the leader's ownership reconcile, reporting WHAT the entity answered on expiry — the entity
    /// reports every refusal as data in the response body, so the body is the only place the answer exists.
    private void awaitOwnershipConvergence() {
        try {
            await().atMost(WAIT_TIMEOUT)
                   .pollInterval(POLL_INTERVAL)
                   .failFast(this::failIfSliceFailed)
                   .until(this::ownershipHasConverged);
        } catch (ConditionTimeoutException e) {
            throw new AssertionError("Entity ownership never converged; last probe answers: " + lastProbeAnswers.get(), e);
        }
    }

    // --- the fence invariant, asserted on every create ---------------------------

    /// Create `key` by offering it to EVERY node and requiring that exactly one creates it — the same helper
    /// [DurableEntityForgeTest] uses, and for the same reason: it is independent of production's own owner
    /// resolution, so it cannot be fooled by that resolution being wrong the same way on both sides. Under
    /// owner-forwarding every node ACCEPTS and relays, so the single-writer invariant shows as exactly one
    /// CREATE and four `EntityAlreadyExists` carrying the owner's verdict back across the wire.
    private void createOnOwner(String key, String status, int amount) {
        var responses = appPorts().stream()
                                  .map(port -> create(port, key, status, amount))
                                  .toList();
        var created = responses.stream()
                               .filter(response -> "created".equals(outcome(response)))
                               .toList();

        assertThat(responses).describedAs("every node must have answered").hasSize(NODES);
        assertThat(created).describedAs("exactly one attempt may create '%s'; got %s", key, outcomesOf(responses))
                           .hasSize(1);
        assertThat(rejectionTypesOf(responses)).describedAs("every later attempt must surface the owner's duplicate refusal (#596)")
                                               .containsOnly("EntityAlreadyExists");
    }

    private static List<String> outcomesOf(List<String> responses) {
        return responses.stream()
                        .map(DurableEntityTimerDurabilityTest::outcome)
                        .toList();
    }

    private static List<String> rejectionTypesOf(List<String> responses) {
        return responses.stream()
                        .filter(response -> !"created".equals(outcome(response)))
                        .map(response -> text(response, "failureType"))
                        .toList();
    }

    /// True once the leader's ownership reconcile has minted records for the entity arcs, probed across a
    /// spread of keys so readiness reflects the whole keyspace rather than whichever partition one key
    /// happened to land on. A fresh key per round keeps a successful probe from colliding with a previous one.
    private boolean ownershipHasConverged() {
        var round = ownershipProbe.incrementAndGet();

        return IntStream.range(0, PARTITION_PROBE_KEYS)
                        .allMatch(index -> probeAccepted("__timer_probe_" + round + "_" + index + "__"));
    }

    /// Deliberately NOT via [#outcome]: that throws on a transport-level error body, and this runs while the
    /// cluster is still settling, so one slow node would abort setUp instead of reporting "not yet".
    private boolean probeAccepted(String key) {
        var answers = appPorts().stream()
                                .map(port -> create(port, key, "probe", 0))
                                .toList();

        lastProbeAnswers.set(answers);

        return answers.stream().anyMatch(response -> response.contains("\"outcome\":\"created\""));
    }

    // --- entity operations -----------------------------------------------------

    private String create(int port, String key, String status, int amount) {
        return httpPost(port,
                        "/api/entity/create",
                        "{\"orderId\":\"" + key + "\",\"status\":\"" + status + "\",\"amount\":" + amount + "}");
    }

    private String get(int port, String key) {
        return httpPost(port, "/api/entity/get", "{\"orderId\":\"" + key + "\"}");
    }

    /// Schedules a timer with a CALLER-CONTROLLED delay. The fixture's five-minute default is unusable for a
    /// gate — nothing that must observe a fire can wait it out — so the delay travels in the request.
    private String scheduleTimer(int port, String key, long delayMillis) {
        return httpPost(port,
                        "/api/entity/schedule-timer",
                        "{\"orderId\":\"" + key + "\",\"delayMillis\":" + delayMillis + "}");
    }

    /// Every currently-serving node's `expiries` for `key` — the multiplicity of applied `Expire` commands.
    private List<Integer> expiriesAcrossCluster(String key) {
        return serversOf(key).stream()
                             .map(response -> number(response, "expiries"))
                             .toList();
    }

    /// One serving node's view of `key`, for reading components other than the count.
    private String servedView(String key) {
        return serversOf(key).stream()
                             .findFirst()
                             .orElseThrow(() -> new AssertionError("No node serves '" + key + "'"));
    }

    /// Every node currently serving `key`, by response body. Filters on a POSITIVE answer rather than
    /// counting negatives: a node mid-handover, or one whose fold is still replaying, answers neither
    /// "found" nor "absent" but a transient refusal, and "absent" and "I cannot say" are different claims
    /// that must not be summed. A node that does not hold the key's partition forwards to the committed
    /// owner, so a "found" here names a node that answered, not necessarily one that holds the state.
    private List<String> serversOf(String key) {
        return appPorts().stream()
                         .map(port -> get(port, key))
                         .filter(response -> response.contains("\"outcome\":\"found\""))
                         .toList();
    }

    // --- ownership --------------------------------------------------------------

    /// The committed owner of one `entity:orders` arc, or `""` when the arc carries no record yet.
    private String arcOwner(int partition) {
        return Option.option(entityArcOwners().get(partition)).or("");
    }

    private boolean hasNewOwner(int partition, String previousOwner) {
        var owner = arcOwner(partition);

        return !owner.isEmpty() && !owner.equals(previousOwner);
    }

    /// Committed `entity:orders` arc owners by partition, read from whichever management port answers — the
    /// handover gate calls this immediately after killing a node, and a fixed port would be a dead one half
    /// the time.
    private Map<Integer, String> entityArcOwners() {
        var body = anyManagementAnswer("/api/v1/ownership/stream");
        var matcher = Pattern.compile("\"identity\"\\s*:\\s*\"entity:" + KEYSPACE + ":(\\d+)\"[^}]*\"owner\"\\s*:\\s*\"([^\"]+)\"")
                             .matcher(body);
        var owners = new HashMap<Integer, String>();

        while (matcher.find()) {
            owners.put(Integer.parseInt(matcher.group(1)), matcher.group(2));
        }

        return owners;
    }

    private String anyManagementAnswer(String path) {
        return mgmtPorts().stream()
                          .map(port -> httpGet(port, path))
                          .filter(body -> !body.contains("\"error\""))
                          .findFirst()
                          .orElse(ERROR_FALLBACK);
    }

    // --- cluster addressing ------------------------------------------------------

    private List<Integer> appPorts() {
        return cluster.getAvailableAppHttpPorts();
    }

    private int firstPort() {
        return appPorts().stream()
                         .findFirst()
                         .orElseThrow(() -> new AssertionError("No app-http route is ready"));
    }

    /// The app-http port of a node id. Both ports are assigned from the same per-node slot (`base + slot`),
    /// so the slot recovered from the management port identifies the app port.
    private int appPortForNodeId(String nodeId) {
        return cluster.status()
                      .nodes()
                      .stream()
                      .filter(node -> node.id().equals(nodeId))
                      .map(node -> BASE_APP_HTTP_PORT + (node.mgmtPort() - BASE_MGMT_PORT))
                      .findFirst()
                      .orElseThrow(() -> new AssertionError("No node with id " + nodeId));
    }

    private List<Integer> mgmtPorts() {
        return cluster.status()
                      .nodes()
                      .stream()
                      .map(EmberCluster.NodeStatus::mgmtPort)
                      .toList();
    }

    private int anyMgmtPort() {
        return cluster.status().nodes().getFirst().mgmtPort();
    }

    // --- deployment + readiness ----------------------------------------------------

    private void deployEntitySlice() {
        var blueprint = """
            id = "%s"

            [[slices]]
            artifact = "%s"
            instances = %d
            """.formatted(BLUEPRINT_ID, ENTITY_SLICE, INSTANCES);
        var leaderPort = cluster.getLeaderManagementPort().or(anyMgmtPort());
        var response = postBlueprintWithRetry(leaderPort, blueprint);

        assertThat(response).describedAs("durable-entity slice deployment")
                            .doesNotContain("\"error\"")
                            .contains("\"status\":\"applied\"");
    }

    private boolean entityReadyOnEveryNode() {
        var ports = appPorts();

        return ports.size() == NODES && ports.stream().allMatch(this::entityReady);
    }

    /// A node is ready once its entity resource ANSWERS — with a state verdict, or by saying plainly that it
    /// does not hold the key's partition. Entity state is replicated across a subset of nodes, so requiring
    /// `absent` everywhere would wait forever for a lie; what this still catches is a node whose entity
    /// resource failed to provision, which returns a transport-level error and never becomes ready.
    private boolean entityReady(int port) {
        var body = get(port, "__readiness_probe__");

        return !body.contains("\"error\"")
               && (body.contains("\"outcome\":\"absent\"")
                   || body.contains("\"outcome\":\"found\"")
                   || body.contains("PartitionNotHeld"));
    }

    /// `slicesStatus()` cannot detect this failure: under `ALL_OR_NOTHING` a deterministic slice failure
    /// rolls back the blueprint and removes the deployment map entry with it, so a FAILED status never
    /// appears and the suite would run out the full `WAIT_TIMEOUT` instead of naming the cause. The
    /// cluster-event stream is append-only and is NOT retracted by the rollback.
    private void failIfSliceFailed() {
        for (int port : mgmtPorts()) {
            var reason = deploymentFailedReason(httpGet(port, "/api/v1/events"));

            if (reason != null) {
                throw new AssertionError("Deployment of " + ENTITY_SLICE + " FAILED — event surface reason: " + excerpt(reason));
            }
        }
    }

    private static String deploymentFailedReason(String eventsBody) {
        var matcher = Pattern.compile("\"type\"\\s*:\\s*\"DEPLOYMENT_FAILED\".*?\"artifact\"\\s*:\\s*\""
                                      + Pattern.quote(ENTITY_SLICE)
                                      + "\".*?\"reason\"\\s*:\\s*\"([^\"]*)\"", Pattern.DOTALL)
                             .matcher(eventsBody);

        return matcher.find() ? matcher.group(1) : null;
    }

    private static String excerpt(String reason) {
        return reason.length() <= REASON_EXCERPT_LIMIT ? reason : reason.substring(0, REASON_EXCERPT_LIMIT) + "...";
    }

    private boolean allNodesHealthy() {
        return mgmtPorts().stream().allMatch(this::checkNodeHealth);
    }

    private boolean checkNodeHealth(int port) {
        return healthBody(port).map(body -> body.contains("\"quorum\":true")).or(false);
    }

    /// Full-membership gate: [#allNodesHealthy] only checks each node's LOCAL quorum flag, which turns true
    /// at 3 of 5. `nodeCount` is the cluster-wide membership count the bootstrap formation phase also gates
    /// on.
    private boolean allNodesAreMembers(int expected) {
        var leaderPort = cluster.getLeaderManagementPort().or(anyMgmtPort());

        return healthBody(leaderPort).map(body -> healthHasFullMembership(body, expected)).or(false);
    }

    private Option<String> healthBody(int port) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + "/api/v1/health"))
                                 .GET()
                                 .timeout(Duration.ofSeconds(5))
                                 .build();

        return http.sendString(request)
                   .await()
                   .option()
                   .filter(response -> response.statusCode() == 200)
                   .map(HttpResult::body);
    }

    private static boolean healthHasFullMembership(String body, int expected) {
        if (!body.contains("\"quorum\":true")) {
            return false;
        }

        var matcher = NODE_COUNT_FIELD.matcher(body);

        return matcher.find() && Integer.parseInt(matcher.group(1)) >= expected;
    }

    // --- response reading -------------------------------------------------------

    /// Fails loudly on a missing field rather than returning "": a renamed component would otherwise turn
    /// every `isEqualTo` into a silent comparison against the empty string.
    private static String text(String body, String field) {
        var matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"").matcher(body);

        if (!matcher.find()) {
            throw new AssertionError("No string field '" + field + "' in entity response: " + body);
        }

        return matcher.group(1);
    }

    private static int number(String body, String field) {
        var matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*(-?\\d+)").matcher(body);

        if (!matcher.find()) {
            throw new AssertionError("No numeric field '" + field + "' in entity response: " + body);
        }

        return Integer.parseInt(matcher.group(1));
    }

    /// The outcome, with the raw body attached on the error path — an HTTP-level failure would otherwise
    /// surface as an opaque "expected created but was ''".
    private static String outcome(String body) {
        if (body.contains("\"error\"")) {
            throw new AssertionError("Entity request failed at the HTTP layer: " + body);
        }

        return text(body, "outcome");
    }

    // --- timing ------------------------------------------------------------------

    private static long elapsedMillis(long sinceNanos) {
        return Duration.ofNanos(System.nanoTime() - sinceNanos).toMillis();
    }

    /// Wait out a fixed span. Deliberately a sleep and not a poll: what is being waited for is the ABSENCE
    /// of a second fire, and absence has no condition to poll on — only elapsed ticks.
    ///
    /// The loop is load-bearing, not defensive. [org.pragmatica.lang.Promise#await()] registers an unpark
    /// callback and then parks in a `while (result == null)` loop; when the promise resolves between the
    /// registration and the check, the loop exits WITHOUT consuming the permit that callback's `unpark`
    /// leaves behind. A bare `parkNanos` on the same thread then returns in ~0ns by consuming that residual
    /// permit — and every caller here runs dozens of `await()` calls immediately beforehand. Parking to a
    /// DEADLINE re-parks after such a wakeup, so the span elapses whatever the permit state.
    ///
    /// The elapsed span is measured and asserted rather than assumed. A quiet period that does not elapse
    /// degrades its caller's exactly-once gate into a re-read of the value it just polled for — the gate
    /// stays green and stops testing anything, which is the failure mode a bare park already produced here.
    private static void awaitQuietPeriod(Duration span) {
        var startedAt = System.nanoTime();
        var deadline = startedAt + span.toNanos();

        while (System.nanoTime() < deadline) {
            LockSupport.parkNanos(deadline - System.nanoTime());
        }

        var elapsedMillis = elapsedMillis(startedAt);

        LOG.log(System.Logger.Level.INFO,
                "I4 quiet period: requested={0}ms elapsed={1}ms",
                span.toMillis(),
                elapsedMillis);

        assertThat(elapsedMillis).describedAs("the quiet period must actually elapse — a residual unpark permit left by "
                                              + "Promise.await() makes a bare park return at once, which would reduce the "
                                              + "exactly-once assertion below to a re-read of the value just polled for")
                                 .isGreaterThanOrEqualTo(span.toMillis());
    }

    // --- HTTP ------------------------------------------------------------------------

    private String postBlueprintWithRetry(int port, String body) {
        var lastResponse = ERROR_FALLBACK;

        for (int attempt = 1; attempt <= 3; attempt++) {
            lastResponse = httpPostToml(port, "/api/v1/blueprints", body);

            if (!lastResponse.contains("\"error\"")) {
                return lastResponse;
            }

            if (attempt < 3) {
                LockSupport.parkNanos(Duration.ofSeconds(2).toNanos());
            }
        }

        return lastResponse;
    }

    private String httpPostToml(int port, String path, String body) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
                                 .header("Content-Type", "application/toml")
                                 .POST(HttpRequest.BodyPublishers.ofString(body))
                                 .timeout(Duration.ofSeconds(10))
                                 .build();

        return http.sendString(request)
                   .await()
                   .map(HttpResult::body)
                   .or(ERROR_FALLBACK);
    }

    private String httpPost(int port, String path, String body) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
                                 .header("Content-Type", "application/json")
                                 .POST(HttpRequest.BodyPublishers.ofString(body))
                                 .timeout(Duration.ofSeconds(15))
                                 .build();

        return http.sendString(request)
                   .await()
                   .map(HttpResult::body)
                   .or(ERROR_FALLBACK);
    }

    private String httpGet(int port, String path) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
                                 .GET()
                                 .timeout(Duration.ofSeconds(10))
                                 .build();

        return http.sendString(request)
                   .await()
                   .map(HttpResult::body)
                   .or(ERROR_FALLBACK);
    }

    private String httpDelete(int port, String path) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
                                 .DELETE()
                                 .timeout(Duration.ofSeconds(10))
                                 .build();

        return http.sendString(request)
                   .await()
                   .map(HttpResult::body)
                   .or(ERROR_FALLBACK);
    }
}
