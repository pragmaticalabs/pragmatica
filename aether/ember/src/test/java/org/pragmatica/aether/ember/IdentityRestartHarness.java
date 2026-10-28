// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.stream.Stream;

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

/// The three-core scenario behind the identity-ledger tests (#2429): cores `<prefix>-1` (A), `<prefix>-2` (the observer, B)
/// and `<prefix>-3` (X). X's original process is killed and a NEW process under X's NodeId is refused by A and B, who retire
/// X. What the tests then do to A and B (restart one, restart both, delete a ledger) is their own subject; this class holds
/// the launching, killing, verdict-reading and port-waiting they share.
///
/// A killed core is relaunched with the configuration it had: the harness forgets a killed node's address, a restarted
/// container reads the same config file.
final class IdentityRestartHarness {
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
    static final TimeSpan START_BOUND = TimeSpan.timeSpan(120).seconds();
    static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    static final TimeSpan WRITE_BOUND = TimeSpan.timeSpan(20).seconds();
    private static final int SLOT_A = 0;
    private static final int SLOT_OBSERVER = 1;
    private static final int SLOT_RETIRED = 2;
    private static final long DEMOTION_BOUND_MS = 120_000L;
    private static final long SLOT_BOUND_MS = 60_000L;
    /// How long a launched process is watched for the verdict: it either exits refused or links.
    private static final long VERDICT_BOUND_MS = 60_000L;
    /// A verdict must hold this long, so a link that is torn down a moment later does not read as an admission.
    private static final long STABLE_MS = 5_000L;
    private static final String REFUSALS_METRIC = "boot_token_refusals_total";

    /// What a core did with one process launched under the retired NodeId.
    record Verdict(boolean processExited, boolean linked, long refusalsAdded) {}

    final String coreA;
    final String observer;
    final String retired;
    private final String prefix;
    private EmberCluster cluster;
    private int basePort;
    private List<NodeInfo> original;

    IdentityRestartHarness(String prefix) {
        this.prefix = prefix;
        this.coreA = prefix + "-1";
        this.observer = prefix + "-2";
        this.retired = prefix + "-3";
    }

    EmberCluster cluster() {
        return cluster;
    }

    AetherNode node(String id) {
        return cluster.getNode(id).unwrap();
    }

    void start() {
        basePort = EmberTestPorts.freeBase(PORTS);
        cluster = clusterWithoutAutoHeal();
        assertThat(cluster.start().await(START_BOUND).fold(Cause::message, _ -> "started")).isEqualTo("started");
        original = cluster.getNodeInfos();
        assertThat(write(node(observer), "control-before").isSuccess()).as("control: a three-core cluster commits").isTrue();
    }

    void stop() {
        if (cluster != null) {
            cluster.stop().await(STOP_BOUND);
        }
    }

    /// Kill X's original process, wait until the observer has registered its death, then start a new process under X: its
    /// boot token differs from the one the observer recorded, so A and B retire X and refuse it.
    void retireX() {
        var observerNode = node(observer);

        assertThat(cluster.killNode(retired, false).await(STOP_BOUND).isSuccess()).isTrue();
        awaitCondition("the observer drops the killed original from its effective set",
                       () -> !observerNode.membershipFsm().isCountedMember(new NodeId(retired)),
                       DEMOTION_BOUND_MS);

        var refusalsBefore = refusals(observerNode);
        var verdict = launchRetiredNodeId(observerNode);

        assertThat(verdict.processExited()).as("the conflicting-token process is refused and exits").isTrue();
        assertThat(cluster.exitCodeOf(retired)).isEqualTo(Option.some(AetherNode.EXIT_IDENTITY_REFUSED));
        assertThat(refusals(observerNode)).as("the observer retired X itself").isGreaterThan(refusalsBefore);
    }

    /// As [#retireX], but until BOTH A and B hold the retirement in their ledger files. A new process under X dials both, yet
    /// the first refusal makes it exit, so A may not be reached by the first launch; each further launch carries a fresh token,
    /// which A (still holding X's original token) retires on contact.
    void retireXAtBothHolders() {
        retireX();

        for (var attempt = 0; attempt < 5 && !(ledgerRetires(coreA, retired) && ledgerRetires(observer, retired)); attempt++) {
            launchRetiredNodeId(node(coreA));
        }

        assertThat(ledgerRetires(coreA, retired)).as("A holds the retirement in its ledger").isTrue();
        assertThat(ledgerRetires(observer, retired)).as("B holds the retirement in its ledger").isTrue();
    }

    /// Kill both holders, one after the other, and wait until their ports are free.
    void killBothHolders() {
        killCoreA();
        killObserver();
    }

    /// A saw B's boot token and would refuse a restarted B; with A gone B is the only observer left.
    void killCoreA() {
        assertThat(cluster.killNode(coreA, false).await(STOP_BOUND).isSuccess()).isTrue();
        awaitSlotFree(SLOT_A);
    }

    void killObserver() {
        assertThat(cluster.killNode(observer, false).await(STOP_BOUND).isSuccess()).isTrue();
        awaitSlotFree(SLOT_OBSERVER);
    }

    /// Start `id` again with the configuration it had: the original peer list.
    AetherNode relaunch(String id) {
        restoreConfiguredPeers(Set.of(coreA, observer, retired));
        cluster.relaunchNode(id, true);

        return node(id);
    }

    AetherNode restartObserver() {
        killObserver();

        return relaunch(observer);
    }

    /// A ledger file of one core, the way the harness places it.
    Path ledgerFile(String id) {
        return cluster.identityLedgerFile(new NodeId(id)).unwrap();
    }

    boolean ledgerRetires(String id, String retiredId) {
        return Result.lift(() -> Files.readString(ledgerFile(id))).map(text -> text.contains("T\t" + retiredId + "\t")).or(false);
    }

    /// Delete the node's whole ledger directory: the node is then being created now, as far as the ledger can tell.
    void deleteLedgerDirectory(String id) {
        deleteRecursively(ledgerFile(id).getParent());
    }

    /// Delete only the ledger FILE: the directory stays, so the node is not new and its facts are lost.
    void deleteLedgerFile(String id) {
        Result.lift(() -> Files.deleteIfExists(ledgerFile(id))).unwrap();
    }

    private static void deleteRecursively(Path directory) {
        Result.lift(() -> {
            try (Stream<Path> paths = Files.walk(directory)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(IdentityRestartHarness::deleteQuietly);
            }
            return Unit.unit();
        }).unwrap();
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new IllegalStateException("cannot delete " + path, e);
        }
    }

    /// Start a process under X at X's own address and report `watcher`'s verdict on it: it exits refused, or `watcher` links
    /// to it. The harness registers the node before its start runs, so a refused process is seen leaving the registry.
    /// "Links" reads `connectedPeerIds`, which also lists a peer in the transient EVICTED phase that a failed dial leaves
    /// behind: right for a verdict that ends in an exit, too weak for [#launchRetiredNodeIdAlone].
    Verdict launchRetiredNodeId(AetherNode watcher) {
        var retiredId = new NodeId(retired);

        return launchRetiredNodeId(watcher, node -> node.connectedPeerIds().contains(retiredId));
    }

    /// As [#launchRetiredNodeId], for a `watcher` that has no other live peer, so any CONNECTED link of it is a link to X.
    Verdict launchRetiredNodeIdAlone(AetherNode watcher) {
        return launchRetiredNodeIdAmong(watcher, 0);
    }

    /// As [#launchRetiredNodeId], for a `watcher` that has `otherLinks` CONNECTED links to peers other than X (the caller
    /// waits for them first): X is linked when the watcher holds more. `connectedNodeCount` counts the CONNECTED phase only,
    /// whereas `connectedPeerIds` also lists a peer a failed dial left EVICTED, which is not an admission.
    Verdict launchRetiredNodeIdAmong(AetherNode watcher, int otherLinks) {
        return launchRetiredNodeId(watcher, node -> node.connectedNodeCount() > otherLinks);
    }

    private Verdict launchRetiredNodeId(AetherNode watcher, Predicate<AetherNode> linked) {
        var refusalsBefore = refusals(watcher);

        awaitSlotFree(SLOT_RETIRED);
        cluster.relaunchNode(retired, true);

        awaitCondition("the watcher links to the process or the process exits",
                       () -> cluster.getNode(retired).isEmpty() || linked.test(watcher),
                       VERDICT_BOUND_MS,
                       false);
        awaitCondition("a linked process stays linked",
                       () -> cluster.getNode(retired).isEmpty() || !linked.test(watcher),
                       STABLE_MS,
                       false);

        var exited = cluster.getNode(retired).isEmpty();

        return new Verdict(exited, !exited && linked.test(watcher), refusals(watcher) - refusalsBefore);
    }

    static long refusals(AetherNode node) {
        return Option.option(node.transportMetrics().get(REFUSALS_METRIC))
                     .map(Number::longValue)
                     .or(0L);
    }

    /// `EmberCluster` drops a killed node's `NodeInfo` from the list it hands a new node. Put back the entries the restarted
    /// node's configuration lists; the node's own entry is re-added by the relaunch.
    @SuppressWarnings("unchecked")
    private void restoreConfiguredPeers(Set<String> ids) {
        var infos = (Map<String, NodeInfo>) harnessField("nodeInfos");

        original.stream()
                .filter(info -> ids.contains(info.id().id()))
                .forEach(info -> infos.putIfAbsent(info.id().id(), info));
    }

    private Object harnessField(String name) {
        return Result.lift(() -> accessibleField(name).get(cluster)).unwrap();
    }

    private static Field accessibleField(String name) throws NoSuchFieldException {
        var field = EmberCluster.class.getDeclaredField(name);

        field.setAccessible(true);

        return field;
    }

    /// Auto-heal would provision a fourth voter 15 s after the kill; it is not these tests' subject.
    private EmberCluster clusterWithoutAutoHeal() {
        var built = emberCluster(CLUSTER_SIZE, basePort, basePort + MGMT_OFFSET, basePort + APP_HTTP_OFFSET, prefix);

        built.withComputeProviderDecorator(IdentityRestartHarness::refuseProvisioning);

        return built;
    }

    private static ComputeProvider refuseProvisioning(ComputeProvider delegate) {
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

    static Result<List<Object>> write(AetherNode node, String keyId) {
        KVCommand<AetherKey> put = new KVCommand.Put<>(AetherKey.ApiKeyKey.apiKeyKey(keyId),
                                                       AetherValue.ApiKeyValue.apiKeyValue(keyId, "00", 0L));

        return node.<Object>apply(List.of(put))
                   .await(WRITE_BOUND);
    }

    static void awaitCondition(String what, BooleanSupplier condition, long boundMs) {
        awaitCondition(what, condition, boundMs, true);
    }

    /// With `failOnTimeout` false a bound reached is a verdict the caller reads, not an error.
    static void awaitCondition(String what, BooleanSupplier condition, long boundMs, boolean failOnTimeout) {
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

    /// A hard kill resolves after a 1 s bound while the killed node may still hold its sockets; a same-address launch waits
    /// until every port of the slot is free, and FAILS on its deadline (a bind failure would otherwise pass for a refusal).
    private void awaitSlotFree(int slot) {
        var deadline = System.currentTimeMillis() + SLOT_BOUND_MS;

        while (!EmberTestPorts.slotFree(PORTS, basePort, slot) && System.currentTimeMillis() < deadline) {
            sleep(250);
        }
        if (!EmberTestPorts.slotFree(PORTS, basePort, slot)) {
            throw new AssertionError("slot " + slot + " of base port " + basePort + " is still held " + SLOT_BOUND_MS
                                     + " ms after the kill: a launch there would fail on the bind, not on the boot token");
        }
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
