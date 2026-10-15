// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.ProviderDefaults;
import org.pragmatica.aether.environment.ProvisionRequest;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// #1528 / #1545 — a NEW process launched under a killed core's NodeId must not restore quorum, and a
/// partitioned-but-live process (same boot token) still heals.
///
/// The verifier's scenario on real transport, SWIM and Rabia: three cores `btk-1..3`; kill `btk-3`;
/// start a new process as `btk-3` (fresh boot token); kill `btk-2`. Without the boot-token gate at the
/// QUIC handshake, the survivors refused the new process in SWIM but re-admitted it over QUIC as a
/// RECONNECT of the EVICTED peer, it synchronized and voted, and `btk-1` plus the refused process
/// committed a write. With the gate the only live voter set is `{btk-1}` and the write cannot commit.
///
/// Run for both addresses of the relaunched process: a different port (next free slot) and the killed
/// node's own port (same address, the container-restart shape).
@PortBudget
class EmberSameIdentityRelaunchTest {
    private static final int CLUSTER_SIZE = 3;
    private static final int SLOTS = 2 * CLUSTER_SIZE;
    private static final int MGMT_OFFSET = 40;
    private static final int APP_HTTP_OFFSET = 80;
    /// Registered in forge-tests' `TEST_PORT_ALLOCATION.md`: blocks 23000-23300 (step 100; one block spans
    /// base..base+85), BELOW the Linux ephemeral range 32768-60999 — the former 36100-37900 lay inside it,
    /// and under load a kernel-assigned port left no free block in 4 of 9 bigboy runs (#1558). The previous
    /// 22000-23800 scan was not disjoint: five forge-tests classes bind fixed ports at 22000-22885 and
    /// 23500-23702. 23000-23499 is bound by no test in the repository (searched for every five-digit
    /// literal under `src/test`), and sits between those classes and `SurvivorLivenessAfterGracefulKillTest`
    /// (24500).
    private static final int FIRST_CANDIDATE_BASE = 23000;
    private static final int LAST_CANDIDATE_BASE = 23300;
    private static final int CANDIDATE_STEP = 100;
    /// #1667: probed through the shared EmberTestPorts, which also probes each node's SWIM UDP port.
    private static final EmberTestPorts.Block PORTS = new EmberTestPorts.Block(FIRST_CANDIDATE_BASE,
                                                                                LAST_CANDIDATE_BASE,
                                                                                CANDIDATE_STEP,
                                                                                SLOTS,
                                                                                MGMT_OFFSET,
                                                                                APP_HTTP_OFFSET);
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final TimeSpan WRITE_BOUND = TimeSpan.timeSpan(20).seconds();
    /// Longer than the ~65 s the verifier measured between the SWIM refusal and the QUIC re-admission.
    private static final long READMIT_WINDOW_MS = 90_000L;
    private static final long HEAL_BOUND_MS = 180_000L;
    private static final long DEMOTION_BOUND_MS = 30_000L;
    private static final long REFUSAL_BOUND_MS = 20_000L;
    /// Repetitions of the demotion test that reached #2173's scenario. Static: each repetition runs on its own instance.
    private static final AtomicInteger DEMOTION_SCENARIO_REACHED = new AtomicInteger();

    private EmberCluster cluster;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            cluster.stop().await(STOP_BOUND);
        }
    }


    /// Auto-heal is not this class's subject, and a replacement core is a legitimate FOURTH voter with a fresh NodeId: the leader's
    /// reconciler provisions one 15 s after a core's death whenever its first pass saw the full membership (a race against the
    /// early kill), and that core plus btk-1 then commit the post-kill write that these tests read as "a refused or healed
    /// process voted". So no process can be provisioned here. The counter records the attempts that were refused.
    private final AtomicInteger refusedProvisions = new AtomicInteger();

    private ComputeProvider refuseProvisioning(ComputeProvider delegate) {
        return new ComputeProvider() {
            @Override
            public ProviderDefaults providerDefaults() {
                return delegate.providerDefaults();
            }

            @Override
            public Promise<InstanceInfo> createFrom(ProvisionRequest request) {
                refusedProvisions.incrementAndGet();

                return EnvironmentError.capacityUnavailable("", new IllegalStateException("provisioning is blocked: auto-heal is not this test's subject")).promise();
            }

            @Override
            public Promise<Unit> terminate(InstanceId id) {
                return delegate.terminate(id);
            }

            @Override
            public Promise<List<InstanceInfo>> listInstances() {
                return delegate.listInstances();
            }

            @Override
            public Promise<InstanceInfo> instanceStatus(InstanceId id) {
                return delegate.instanceStatus(id);
            }
        };
    }

    /// The electorate precondition: the live processes AND the survivor's MEMBER records are exactly `expected`. Without it the
    /// post-kill write proves nothing about WHICH processes voted.
    private void requireElectorate(AetherNode survivor, Set<String> expected) {
        assertThat(cluster.allNodes().stream().map(node -> node.self().id()).collect(Collectors.toSet())).as("the live processes").isEqualTo(expected);
        assertThat(survivor.membershipFsm()
                           .memberStates()
                           .entrySet()
                           .stream()
                           .filter(entry -> "Member".equals(entry.getValue()))
                           .map(entry -> entry.getKey().id())
                           .collect(Collectors.toSet())).as("the survivor's MEMBER records").isEqualTo(expected);
    }

    @Test
    @Timeout(480)
    void relaunchedSameIdentityProcess_atNewAddress_neverRestoresQuorum() {
        relaunchedProcessNeverRestoresQuorum(false);
    }

    @Test
    @Timeout(480)
    void relaunchedSameIdentityProcess_atSameAddress_neverRestoresQuorum() {
        relaunchedProcessNeverRestoresQuorum(true);
    }

    /// #2173: the survivor must register the death of a core that was killed while the cluster was still forming and whose
    /// NodeId a new process then took over. A core killed that early leaves the survivors' QUIC link to it unclosed; the takeover
    /// is then retired by a boot-token conflict, a death-path removal that feeds the membership FSM nothing, and the SWIM FAULTY
    /// was turned into UNKNOWN by the cold-boot rule and dropped when the member was swept, so the survivor's record for the
    /// NodeId stayed MEMBER for good. The kill has to win a race against the link establishment, so one run does not always
    /// hit it (about half of the runs did before the fix). Two repetitions is all the port pool of this class allows (four
    /// blocks, TIME_WAIT between clusters); the deterministic regression is `SwimRetiredIdentityDeathTest`.
    @RepeatedTest(2)
    @Timeout(300)
    void coreKilledWhileFormingAndSupersededByTheSameNodeId_isDemotedOnTheSurvivor(RepetitionInfo repetition) {
        var basePort = EmberTestPorts.freeBase(PORTS);
        cluster = emberCluster(CLUSTER_SIZE, basePort, basePort + MGMT_OFFSET, basePort + APP_HTTP_OFFSET, "btk");
        cluster.start();
        awaitCondition("the forming cluster's nodes exist", () -> cluster.getNode("btk-1").isPresent() && cluster.getNode("btk-3").isPresent());
        var survivor = cluster.getNode("btk-1").unwrap();
        var killedId = cluster.getNode("btk-3").unwrap().self();

        assertThat(cluster.killNode("btk-3", false).await(STOP_BOUND).isSuccess()).isTrue();
        cluster.relaunchNode("btk-3", false);
        requireTheScenario(refusedWithin(REFUSAL_BOUND_MS), repetition);

        awaitDemoted(survivor, killedId, DEMOTION_BOUND_MS);
    }

    /// A kill that lands before the survivors learned the original's boot token leaves nothing to supersede: the relaunch is
    /// then the first process they see for btk-3 and joins as a live member. A kill that lands while the forming cluster's
    /// start is still running aborts that start and stops the whole cluster. Only a refused relaunch is #2173's scenario, so
    /// any other repetition is skipped, except the last when none reached it: a regression whose every repetition missed its
    /// scenario has tested nothing, and must not pass as skipped.
    private static void requireTheScenario(boolean refused, RepetitionInfo repetition) {
        if (refused) {
            DEMOTION_SCENARIO_REACHED.incrementAndGet();

            return;
        }

        assertThat(repetition.getCurrentRepetition() == repetition.getTotalRepetitions() && DEMOTION_SCENARIO_REACHED.get() == 0)
            .as("none of the %d repetitions reached #2173's scenario: no relaunch was refused, so the regression never ran",
                repetition.getTotalRepetitions())
            .isFalse();
        Assumptions.abort("the relaunch was not refused: the original was killed before its token was seen, or the forming cluster's start aborted");
    }

    /// The positive arm on the same transport: a partitioned-but-LIVE process keeps its boot token, so
    /// when the partition heals (higher SWIM incarnation, same token) it is re-admitted over QUIC and
    /// counts toward quorum again — btk-1 plus the healed btk-3 commit after btk-2 is killed. No
    /// boot-token refusal may be recorded anywhere on the path.
    @Test
    @Timeout(480)
    void partitionedLiveProcess_sameToken_healsAndRestoresQuorum() {
        cluster = EmberTestPorts.startedCluster(PORTS, this::clusterWithoutAutoHeal, START_BOUND);
        var survivor = cluster.getNode("btk-1").unwrap();
        var partitioned = cluster.getNode("btk-3").unwrap();
        var partitionedId = partitioned.self();

        assertThat(write(survivor, "control-before").isSuccess()).as("control: a three-core cluster commits").isTrue();

        partitioned.blackhole(true);
        awaitCondition("the survivor's membership stops counting the partitioned node as MEMBER",
                       () -> !"Member".equals(survivor.membershipFsm().memberStates().get(partitionedId)));
        partitioned.blackhole(false);
        awaitCondition("the same process, same token, heals back to MEMBER on the survivor",
                       () -> "Member".equals(survivor.membershipFsm().memberStates().get(partitionedId)));

        requireElectorate(survivor, Set.of("btk-1", "btk-2", "btk-3"));
        assertThat(cluster.killNode("btk-2", false).await(STOP_BOUND).isSuccess()).isTrue();

        assertThat(write(survivor, "after-heal").isSuccess())
            .as("btk-1 plus the healed btk-3 (same process) form a quorum")
            .isTrue();
        assertThat(survivor.transportMetrics().get("boot_token_refusals_total").longValue())
            .as("a same-token heal is never refused")
            .isZero();
    }

    /// Refused means the relaunched process exited with the identity-refused code. Its absence from the cluster is not
    /// evidence: a start aborted by the early kill clears the cluster too, and the survivor, then stopped, keeps its seeded
    /// MEMBER record (CI run 38336812709, "Cluster startup aborted on first node failure").
    private boolean refusedWithin(long boundMs) {
        var deadline = System.currentTimeMillis() + boundMs;

        while (!cluster.exitCodeOf("btk-3").equals(Option.some(AetherNode.EXIT_IDENTITY_REFUSED))) {
            if (System.currentTimeMillis() > deadline) {
                return false;
            }
            sleep(250);
        }

        return true;
    }

    private EmberCluster clusterWithoutAutoHeal(int basePort) {
        var built = emberCluster(CLUSTER_SIZE, basePort, basePort + MGMT_OFFSET, basePort + APP_HTTP_OFFSET, "btk");

        built.withComputeProviderDecorator(this::refuseProvisioning);

        return built;
    }

    private static void awaitCondition(String what, java.util.function.BooleanSupplier condition) {
        var deadline = System.currentTimeMillis() + HEAL_BOUND_MS;

        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting: " + what);
            }
            sleep(250);
        }
    }

    private void relaunchedProcessNeverRestoresQuorum(boolean sameAddress) {
        var basePort = EmberTestPorts.freeBase(PORTS);
        cluster = clusterWithoutAutoHeal(basePort);
        assertThat(cluster.start().await(START_BOUND).fold(Cause::message, _ -> "started")).isEqualTo("started");
        var survivor = cluster.getNode("btk-1").unwrap();

        assertThat(write(survivor, "control-before").isSuccess()).as("control: a three-core cluster commits").isTrue();

        var killedId = cluster.getNode("btk-3").unwrap().self();

        assertThat(cluster.killNode("btk-3", false).await(STOP_BOUND).isSuccess()).isTrue();
        // The survivor must have registered the original's death before the same NodeId reappears. Its record is demoted by the
        // organic close of the original's QUIC link; a relaunch that connects first supersedes that link, and the retirement that
        // follows is a death-path removal, which by design feeds the membership FSM nothing (AetherNode.connectivityWiring), so
        // the record can stay MEMBER for good. That race is a separate product defect, not this test's subject.
        awaitDemoted(survivor, killedId, HEAL_BOUND_MS);
        if (sameAddress) {
            awaitSlotFree(basePort, 2);
        }

        var launch = cluster.relaunchNode("btk-3", sameAddress);
        // The node is registered before its start runs; the refused process may exit within seconds.
        var relaunched = cluster.getNode("btk-3").unwrap();

        // The start promise of a refused process never resolves (the node stops during its start), so awaiting it is a
        // pure START_BOUND timeout of 120 s, after which the window below used to open. The signal is the exit.
        awaitCondition("the refused process exits", () -> cluster.getNode("btk-3").isEmpty());
        assertThat(launch.await(TimeSpan.timeSpan(1).seconds()).fold(Cause::message, _ -> "launched"))
            .as("the refused process never completes a start")
            .isNotEqualTo("launched");

        var relaunchedId = relaunched.self();

        awaitRefusedNeverParticipates(survivor, relaunched, relaunchedId);
        assertThat(relaunched.isReady()).as("the refused process must never become consensus-active").isFalse();
        assertThat(cluster.getNode("btk-3").isEmpty())
            .as("the refused process learns it was refused (explicit HelloRefused/IdentityRefused) and exits")
            .isTrue();
        assertThat(cluster.exitCodeOf("btk-3"))
            .as("#1558: a refused identity exits with its own code, distinct from a drain's")
            .isEqualTo(Option.some(AetherNode.EXIT_IDENTITY_REFUSED));

        requireElectorate(survivor, Set.of("btk-1", "btk-2"));
        assertThat(cluster.killNode("btk-2", false).await(STOP_BOUND).isSuccess()).isTrue();

        assertThat(write(survivor, "after-kill").isSuccess())
            .as("btk-1 plus a refused same-NodeId process must NOT form a quorum")
            .isFalse();
        assertThat(cluster.allNodes().stream().map(node -> node.self().id())).as("no live process answers to the refused NodeId")
                                                                                .doesNotContain("btk-3");
    }

    /// Waits for the survivor to stop counting `id` as MEMBER. A bound reached with the record still MEMBER fails naming that
    /// state, so a stuck record is reported as itself and not as a generic timeout.
    private static void awaitDemoted(AetherNode survivor, org.pragmatica.consensus.NodeId id, long boundMs) {
        var deadline = System.currentTimeMillis() + boundMs;
        var state = survivor.membershipFsm().memberStates().get(id);

        while ("Member".equals(state)) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("the survivor still counts the killed original " + id.id() + " as MEMBER " + boundMs
                                         + " ms after it was killed (stuck state: " + state + "): its death was never registered");
            }
            sleep(250);
            state = survivor.membershipFsm().memberStates().get(id);
        }
    }

    /// From the moment the killed original was demoted, and for the whole readmission window, the survivor never
    /// counts the refused identity as a member again and the refused process never becomes consensus-active. Sampled, so a process admitted at any moment of the window
    /// fails the test, not only one still admitted at its end.
    private static void awaitRefusedNeverParticipates(AetherNode survivor, AetherNode relaunched, org.pragmatica.consensus.NodeId relaunchedId) {
        var deadline = System.currentTimeMillis() + READMIT_WINDOW_MS;

        while (System.currentTimeMillis() < deadline) {
            assertThat(relaunched.isReady()).as("the refused process became consensus-active during the window").isFalse();
            assertThat(survivor.membershipFsm().memberStates().get(relaunchedId))
                .as("the survivor re-admitted the refused identity as a member during the window")
                .isNotEqualTo("Member");
            sleep(250);
        }
    }

    private static org.pragmatica.lang.Result<List<Object>> write(AetherNode node, String keyId) {
        KVCommand<AetherKey> put = new KVCommand.Put<>(AetherKey.ApiKeyKey.apiKeyKey(keyId),
                                                       AetherValue.ApiKeyValue.apiKeyValue(keyId, "00", 0L));

        return node.<Object>apply(List.of(put))
                   .await(WRITE_BOUND);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /// A hard kill resolves after a 1 s bound while the killed node may still hold its sockets; the
    /// same-address relaunch waits until every port of btk-3's slot (slot 2) is free again. It FAILS on its
    /// deadline: since #1719 the QUIC bind is exclusive, so relaunching onto a still-held port fails with
    /// BindFailed, and "the refused process never completes a start" would then pass for the wrong reason.
    private static void awaitSlotFree(int base, int slot) {
        var deadline = System.currentTimeMillis() + 60_000L;

        while (!EmberTestPorts.slotFree(PORTS, base, slot) && System.currentTimeMillis() < deadline) {
            sleep(250);
        }
        if (!EmberTestPorts.slotFree(PORTS, base, slot)) {
            throw new AssertionError("slot " + slot + " of base port " + base + " is still held 60 s after the kill: a relaunch "
                                     + "there would be refused by the bind (BindFailed), not by the boot token");
        }
    }
}
