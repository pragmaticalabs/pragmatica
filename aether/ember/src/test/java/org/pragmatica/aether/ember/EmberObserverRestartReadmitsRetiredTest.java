// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
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
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// The flip experiment: after an OBSERVER restarts, does it admit a process for a NodeId that it had
/// retired by a conflicting boot token? The boot-token registry is process memory (`BootTokens`: nothing survives a
/// restart, the first token seen wins again), so on paper yes; this runs it on real QUIC, SWIM and
/// Rabia.
///
/// The sequence, three cores `obs-1` (A), `obs-2` (B, the observer) and `obs-3` (X):
///
/// 1. X's original process is killed; a NEW process under X's NodeId (new boot token) is refused by B and A: B
///    retires X.
/// 2. A is killed, so that B is the only process left that can refuse anything. CONTROL, no restart of B: yet
///    another process under X is launched. B must refuse it again from its own registry (its refusal counter
///    rises, the process exits identity-refused, B never links to it). A control that does not reach B's registry
///    fails the test; it does not pass vacuously.
/// 3. B is killed and started again with its original configuration (an operator restarting a container). A is
///    gone by now on purpose: it holds B's old boot token and would refuse a restarted B (matrix rows 1, 2 and 5
///    pin that for any peer that saw the old process), so B cannot restart under its own NodeId while any peer
///    that saw it is alive.
/// 4. A process is started under X, exactly as in the control. It is a process B's new registry has never seen,
///    which is what X's old process would be to it: the harness cannot replay a chosen token, and the registry's
///    decision depends on its content only (`BootTokensTest.admit_afterARestart_admitsTheTokenThatWasRetired`
///    pins the old-token row at registry level).
///
/// Control and treatment run the same launch and the same assertions against the same observer NodeId, with the
/// same peers alive (none); the restart of B is the only difference. /// Today B admits it: it links to X, its refusal counter stays zero, the process stays up. The DISABLED twin
/// holds the real assertion, the same refusal the control got, and is red on today's code. An owner decision to
/// un-defer a durable identity record is what would flip it.
///
/// The same-id relaunch matrix, one row per cell and the test that pins it:
///
/// | # | Cell | Today | Pinned by |
/// |---|------|-------|-----------|
/// | 1 | killed core's NodeId relaunched at a new address; survivors hold its token | refused, never counts, quorum not restored | `EmberSameIdentityRelaunchTest.relaunchedSameIdentityProcess_atNewAddress_neverRestoresQuorum` |
/// | 2 | same, at the killed node's own address (container restart) | same | `EmberSameIdentityRelaunchTest.relaunchedSameIdentityProcess_atSameAddress_neverRestoresQuorum` |
/// | 3 | core killed while the cluster formed, NodeId then taken over | takeover refused, original demoted on the survivor | `EmberSameIdentityRelaunchTest.coreKilledWhileForming_andSupersededByTheSameNodeId_isDemotedOnTheSurvivor` |
/// | 4 | partitioned-but-live process, same token, partition heals | heals, counts toward quorum, no refusal | `EmberSameIdentityRelaunchTest.partitionedLiveProcess_sameToken_healsAndRestoresQuorum` |
/// | 5 | same-id restart after a voter swap, lagging voter holds the old roster | refused at transport, slot agreement holds | `EmberAmnesiacRestartTest.sameIdRestartAfterASwap_isRefusedAtTransport_andSlotAgreementHolds` |
/// | 6 | SWIM: different token in ANNOUNCE | identity retired, new process refused | `SwimBootTokenTest.differentToken_announce_retiresIdentity_andRefusesNewProcess` |
/// | 7 | SWIM: same token, higher incarnation | heals as before | `SwimBootTokenTest.sameToken_higherIncarnation_healsAsBefore` |
/// | 8 | SWIM: token 0 | carries no identity, admitted | `SwimBootTokenTest.zeroToken_carriesNoProcessIdentity_andIsAdmitted` |
/// | 9 | QUIC acceptor: different token / same token | refused and retired / admitted | `QuicBootTokenAdmissionTest.acceptor_differentTokenProcess_isRefusedAndRetired`, `QuicBootTokenAdmissionTest.acceptor_sameTokenProcess_isAdmitted` |
/// | 10 | QUIC dialer: different token / same token | refused and retired / admitted | `QuicBootTokenAdmissionTest.dialer_differentTokenServer_isRefusedAndRetired`, `QuicBootTokenAdmissionTest.dialer_sameTokenServer_isAdmitted` |
/// | 11 | the rule itself: unknown / equal / different / retired / token 0 | the `BootTokens.admit` table | `BootTokensTest` |
/// | 12 | membership FSM against the registry | FSM admits what the registry retired | `MembershipFsmBootTokenDivergenceTest` |
/// | 13 | the OBSERVER restarts (its registry is empty), a process for the retired NodeId arrives | admitted | this class |
@PortBudget
class EmberObserverRestartReadmitsRetiredTest {
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
    private static final String PREFIX = "obs";
    private static final String CORE_A = "obs-1";
    private static final String OBSERVER = "obs-2";
    private static final String RETIRED = "obs-3";
    private static final int SLOT_A = 0;
    private static final int SLOT_OBSERVER = 1;
    private static final int SLOT_RETIRED = 2;
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final TimeSpan WRITE_BOUND = TimeSpan.timeSpan(20).seconds();
    private static final long DEMOTION_BOUND_MS = 120_000L;
    private static final long SLOT_BOUND_MS = 60_000L;
    /// How long a launched process is watched for the observer's verdict: it either exits refused or links.
    private static final long VERDICT_BOUND_MS = 60_000L;
    /// A verdict must hold this long, so a link that is torn down a moment later does not read as an admission.
    private static final long STABLE_MS = 5_000L;
    private static final String REFUSALS_METRIC = "boot_token_refusals_total";

    private EmberCluster cluster;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            cluster.stop().await(STOP_BOUND);
        }
    }

    /// What the observer did with one process launched under the retired NodeId.
    private record Verdict(boolean processExited, boolean linked, long refusalsAdded) {}

    /// The state the experiment hands to its two arms: the observer after its restart, and its verdict on the
    /// process launched under the retired NodeId.
    private record Experiment(Verdict control, AetherNode restartedObserver, Verdict afterRestart) {}

    @Test
    @Timeout(900)
    void restartedObserver_admitsAProcessForTheNodeIdItRetired_today() {
        var experiment = runExperiment();

        assertThat(experiment.control().processExited()).as("control: without the restart the process is refused and exits").isTrue();
        assertThat(experiment.control().linked()).as("control: without the restart the observer never links to it").isFalse();
        assertThat(experiment.control().refusalsAdded()).as("control: the refusal came from the observer's own registry").isPositive();

        assertThat(experiment.afterRestart().refusalsAdded()).as("the restarted observer refused nothing").isZero();
        assertThat(experiment.afterRestart().processExited()).as("the process for the retired NodeId stays up").isFalse();
        assertThat(experiment.afterRestart().linked()).as("the restarted observer links to the retired NodeId's process").isTrue();
        assertThat(experiment.restartedObserver().membershipFsm().memberStates().get(new NodeId(RETIRED)))
            .as("the restarted observer holds a live record for the retired NodeId")
            .isNotNull()
            .isNotIn("Dead", "Departing");
    }

    @Disabled("enable at #2429 (durable identity record): the restarted observer must refuse what it retired before the restart; today the registry is process memory")
    @Test
    @Timeout(900)
    void restartedObserver_refusesAProcessForTheNodeIdItRetired_target() {
        var experiment = runExperiment();

        assertThat(experiment.afterRestart().processExited()).as("the restarted observer refuses the process: it exits identity-refused").isTrue();
        assertThat(experiment.afterRestart().linked()).as("the restarted observer never links to it").isFalse();
        assertThat(experiment.afterRestart().refusalsAdded()).as("the refusal is counted by the restarted observer's own registry").isPositive();
    }

    private Experiment runExperiment() {
        var basePort = EmberTestPorts.freeBase(PORTS);
        cluster = clusterWithoutAutoHeal(basePort);
        assertThat(cluster.start().await(START_BOUND).fold(Cause::message, _ -> "started")).isEqualTo("started");

        var observer = cluster.getNode(OBSERVER).unwrap();
        var retiredId = cluster.getNode(RETIRED).unwrap().self();
        var original = cluster.getNodeInfos();

        assertThat(write(observer, "control-before").isSuccess()).as("control: a three-core cluster commits").isTrue();

        retireAtObserver(observer, retiredId, basePort);
        killCoreA(basePort);

        var control = launchRetiredNodeId(observer, basePort);
        var restarted = restartObserver(basePort, original);
        var afterRestart = launchRetiredNodeId(restarted, basePort);

        return new Experiment(control, restarted, afterRestart);
    }

    /// Kill X's original process, wait until the observer has registered its death, then start a new process under
    /// X: its boot token differs from the one the observer recorded, so the observer retires X and refuses it.
    private void retireAtObserver(AetherNode observer, NodeId retiredId, int basePort) {
        assertThat(cluster.killNode(RETIRED, false).await(STOP_BOUND).isSuccess()).isTrue();
        awaitCondition("the observer drops the killed original from its effective set",
                       () -> !observer.membershipFsm().isCountedMember(retiredId),
                       DEMOTION_BOUND_MS);

        var refusalsBefore = refusals(observer);
        var verdict = launchRetiredNodeId(observer, basePort);

        assertThat(verdict.processExited()).as("the conflicting-token process is refused and exits").isTrue();
        assertThat(cluster.exitCodeOf(RETIRED)).isEqualTo(Option.some(AetherNode.EXIT_IDENTITY_REFUSED));
        assertThat(refusals(observer)).as("the observer retired X itself").isGreaterThan(refusalsBefore);
    }

    /// A saw B's boot token and would refuse a restarted B; with A gone B is the only observer left.
    private void killCoreA(int basePort) {
        assertThat(cluster.killNode(CORE_A, false).await(STOP_BOUND).isSuccess()).isTrue();
        awaitSlotFree(basePort, SLOT_A);
    }

    /// Kill B and start it again with the configuration it had: the original peer list, not the harness's reduced
    /// one (the harness forgets a killed node's address; a restarted container reads the same config file).
    private AetherNode restartObserver(int basePort, List<NodeInfo> original) {
        assertThat(cluster.killNode(OBSERVER, false).await(STOP_BOUND).isSuccess()).isTrue();
        awaitSlotFree(basePort, SLOT_OBSERVER);
        restoreConfiguredPeers(original, Set.of(CORE_A, RETIRED));
        cluster.relaunchNode(OBSERVER, true);

        return cluster.getNode(OBSERVER).unwrap();
    }

    /// Start a process under X at X's own address and report the observer's verdict on it: it exits refused, or
    /// the observer links to it. The harness registers the node before its start runs, so a refused process is
    /// seen leaving the registry.
    private Verdict launchRetiredNodeId(AetherNode observer, int basePort) {
        var refusalsBefore = refusals(observer);
        var retiredId = new NodeId(RETIRED);

        awaitSlotFree(basePort, SLOT_RETIRED);
        cluster.relaunchNode(RETIRED, true);
        var launched = cluster.getNode(RETIRED).unwrap();

        awaitCondition("the observer links to the process or the process exits",
                       () -> cluster.getNode(RETIRED).isEmpty() || observer.connectedPeerIds().contains(retiredId),
                       VERDICT_BOUND_MS,
                       false);

        awaitCondition("a linked process stays linked", () -> cluster.getNode(RETIRED).isEmpty() || !observer.connectedPeerIds().contains(retiredId), STABLE_MS, false);

        var exited = cluster.getNode(RETIRED).isEmpty();

        return new Verdict(exited, !exited && observer.connectedPeerIds().contains(launched.self()), refusals(observer) - refusalsBefore);
    }

    private static long refusals(AetherNode node) {
        return Option.option(node.transportMetrics().get(REFUSALS_METRIC))
                     .map(Number::longValue)
                     .or(0L);
    }

    /// `EmberCluster` drops a killed node's `NodeInfo` from the list it hands a new node. Put back the entries the
    /// restarted observer's configuration lists; the observer's own entry is re-added by the relaunch.
    @SuppressWarnings("unchecked")
    private void restoreConfiguredPeers(List<NodeInfo> original, Set<String> ids) {
        var infos = (Map<String, NodeInfo>) harnessField("nodeInfos");

        original.stream()
                .filter(info -> ids.contains(info.id().id()))
                .forEach(info -> infos.put(info.id().id(), info));
    }

    private Object harnessField(String name) {
        return Result.lift(() -> accessibleField(name).get(cluster)).unwrap();
    }

    private static Field accessibleField(String name) throws NoSuchFieldException {
        var field = EmberCluster.class.getDeclaredField(name);

        field.setAccessible(true);

        return field;
    }

    /// Auto-heal would provision a fourth voter 15 s after the kill; it is not this test's subject.
    private EmberCluster clusterWithoutAutoHeal(int basePort) {
        var built = emberCluster(CLUSTER_SIZE, basePort, basePort + MGMT_OFFSET, basePort + APP_HTTP_OFFSET, PREFIX);

        built.withComputeProviderDecorator(this::refuseProvisioning);

        return built;
    }

    private ComputeProvider refuseProvisioning(ComputeProvider delegate) {
        return new ComputeProvider() {
            @Override
            public ProviderDefaults providerDefaults() {
                return delegate.providerDefaults();
            }

            @Override
            public Promise<InstanceInfo> createFrom(ProvisionRequest request) {
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

    private static Result<List<Object>> write(AetherNode node, String keyId) {
        KVCommand<AetherKey> put = new KVCommand.Put<>(AetherKey.ApiKeyKey.apiKeyKey(keyId),
                                                       AetherValue.ApiKeyValue.apiKeyValue(keyId, "00", 0L));

        return node.<Object>apply(List.of(put))
                   .await(WRITE_BOUND);
    }

    private static void awaitCondition(String what, BooleanSupplier condition, long boundMs) {
        awaitCondition(what, condition, boundMs, true);
    }

    /// With `failOnTimeout` false a bound reached is a verdict the caller reads, not an error.
    private static void awaitCondition(String what, BooleanSupplier condition, long boundMs, boolean failOnTimeout) {
        var deadline = System.currentTimeMillis() + boundMs;

        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                if (failOnTimeout) {
                    throw new AssertionError("timed out waiting: " + what);
                }

                return;
            }
            sleep(250);
        }
    }

    /// A hard kill resolves after a 1 s bound while the killed node may still hold its sockets; a same-address
    /// launch waits until every port of the slot is free, and FAILS on its deadline (a bind failure would
    /// otherwise pass for a refusal).
    private static void awaitSlotFree(int base, int slot) {
        var deadline = System.currentTimeMillis() + SLOT_BOUND_MS;

        while (!EmberTestPorts.slotFree(PORTS, base, slot) && System.currentTimeMillis() < deadline) {
            sleep(250);
        }
        if (!EmberTestPorts.slotFree(PORTS, base, slot)) {
            throw new AssertionError("slot " + slot + " of base port " + base + " is still held " + SLOT_BOUND_MS
                                     + " ms after the kill: a launch there would fail on the bind, not on the boot token");
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
