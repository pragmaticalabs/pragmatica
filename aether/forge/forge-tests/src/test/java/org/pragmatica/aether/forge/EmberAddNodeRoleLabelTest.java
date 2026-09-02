// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.forge;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.pragmatica.aether.deployment.membership.fsm.MemberDescriptor;
import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.ProvisionContext;
import org.pragmatica.aether.environment.ProvisionRequest;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.aether.environment.SourceName;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.TerminalOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// #590 — the guard on Ember's role labels: on the opt-in `addWorkerNode()`, on the DEFAULT
/// `addNode()` staying untouched, and on the provisioned path stamping the role it is handed.
///
/// ## Why this class exists
///
/// `addNode()` is a primitive every forge test depends on, so adding a role-label variant beside it is
/// only safe if the DEFAULT path is provably unchanged. This pins both halves: the default advertises
/// no role, and the opt-in advertises `role=worker`.
///
/// ## The property being restored, and why it is the label
///
/// Community-tier mechanisms gate on a node being positively known NOT to be a core:
/// `MemberDescriptor.isCoreRole(role) = !"worker".equals(role)` — **blank or unknown counts as CORE**,
/// deliberately, because acting on an unresolved view is the dangerous direction. Production nodes
/// self-assert that label (`AETHER_ROLE` → `NodeInfo.LABEL_ROLE`); Ember set none, so every in-JVM node
/// classified as a core and the #590 core-absence fence could never fire. Measured before the fix:
/// `armed=true sinceLastPingMs=40922 remainingMs=0 thresholdMs=10000 fenced=false`.
///
/// The opt-in assertion is on the ADVERTISED LABEL, not on downstream classification, deliberately: the
/// label is the contract that method owns, and pinning it here keeps the guard honest even if the
/// classification chain is later refactored.
///
/// ## The provisioned path, which no test can opt out of
///
/// `addWorkerNode()` fixes the paths a TEST controls. The path a test does NOT control is
/// `EmberComputeProvider.createFrom`, which the CTM reaches through auto-heal and worker reconcile: it
/// used to drop `ProvisionContext.role` and call the bare `addNode()`, so a CTM-minted worker booted
/// in-JVM classifying as a CORE. Production providers translate that same field into `AETHER_ROLE` /
/// `aether-role`, which the booting node re-asserts as its SWIM label — so propagating it is
/// fidelity, not new behaviour.
///
/// These three go through `ComputeProvider.provision(spec)`, NOT `createFrom` directly, so the static
/// `ProvisionRequest.resolve` choke is inside the assertion: a resolution step that dropped the context
/// would fail here too. The base provider is captured through the EXISTING `withComputeProviderDecorator`
/// seam rather than by widening Ember's API for a test.
///
/// **The equivalence that keeps existing core provisioning unchanged is asserted, not assumed**:
/// `core` and blank are both non-`worker`, so both classify as core. That is one line of predicate, and
/// [#coreRoleAndBlank_areEquivalent_soExistingCoreProvisioningIsUnchanged] pins it directly instead of
/// leaving it as a claim in a commit message.
@Tag("Heavy")
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EmberAddNodeRoleLabelTest {
    private static final Logger log = LoggerFactory.getLogger(EmberAddNodeRoleLabelTest.class);

    private static final int INITIAL_CORES = 3;
    private static final int BASE_PORT = 21500;
    private static final int BASE_MGMT_PORT = 21600;
    private static final int BASE_APP_HTTP_PORT = 21700;

    private static final Duration FORM_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration OBSERVE_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration POLL = Duration.ofMillis(250);

    private EmberCluster cluster;

    /// The base [EmberCluster] provider, captured by an identity decorator installed before `start()`.
    private final AtomicReference<ComputeProvider> baseProvider = new AtomicReference<>();

    /// Nodes added by the test that just ran, killed in [#releaseAddedNodes]. Ember's slot pool is
    /// `2 * initialSize` = 6, of which 3 are taken by the initial cores — three spare for five tests.
    /// Killing returns the slot, so each test is slot-neutral and the class is order-independent.
    private final List<String> addedNodes = new CopyOnWriteArrayList<>();

    @BeforeAll
    @TerminalOperation
    void setUp() {
        cluster = emberCluster(INITIAL_CORES, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, "rolelbl");
        cluster.withRaisedSwimTimeouts();
        // Identity decorator — captures the provider the cluster will actually use, changes nothing.
        cluster.withComputeProviderDecorator(provider -> {
            baseProvider.set(provider);

            return provider;
        });
        cluster.start().await().onFailure(EmberAddNodeRoleLabelTest::fail);
        await().atMost(FORM_TIMEOUT).pollInterval(POLL).until(() -> cluster.currentLeader().isPresent());
    }

    @AfterEach
    void releaseAddedNodes() {
        addedNodes.forEach(id -> cluster.killNode(id).await());
        addedNodes.clear();
    }

    @AfterAll
    @TerminalOperation
    void tearDown() {
        Option.option(cluster).onPresent(c -> c.stop().await());
    }

    /// THE REGRESSION GUARD. Every existing forge test calls `addNode()`, and a node that started
    /// advertising a role would change how the membership FSM classifies it cluster-wide. The default
    /// must stay exactly as it was: no role label at all.
    @Test
    void addNode_advertisesNoRoleLabel_soExistingBehaviourIsUnchanged() {
        var added = add(cluster.addNode().await().onFailure(EmberAddNodeRoleLabelTest::fail).map(NodeId::id).or(""));
        var labels = advertisedLabels(added);

        log.info("ROLE-LABEL: default addNode -> {} labels={}", added, labels);
        assertThat(labels.containsKey(NodeInfo.LABEL_ROLE))
            .as("default addNode() must advertise NO role label — blank classifies as CORE, which is the "
                + "long-standing behaviour every forge test is written against. Saw labels=%s", labels)
            .isFalse();
    }

    /// The opt-in. Without this label the node classifies as a core and every community-tier mechanism
    /// is suppressed on it, which is exactly what made #590's fence unobservable in-JVM.
    @Test
    void addWorkerNode_advertisesTheWorkerRole_soCommunityTierMechanismsApply() {
        var added = add(cluster.addWorkerNode()
                               .await()
                               .onFailure(EmberAddNodeRoleLabelTest::fail)
                               .map(NodeId::id)
                               .or(""));
        var labels = advertisedLabels(added);

        log.info("ROLE-LABEL: addWorkerNode -> {} labels={}", added, labels);
        assertThat(labels.get(NodeInfo.LABEL_ROLE))
            .as("addWorkerNode() must advertise exactly the literal `MemberDescriptor.isCoreRole` tests "
                + "against — anything else, INCLUDING a near-miss like \"WORKER\", classifies as core and "
                + "silently restores the suppression this method exists to lift. Saw labels=%s", labels)
            .isEqualTo("worker");
    }

    /// The provisioned worker: the case the harness could not previously produce at all. A CTM worker
    /// reconcile pass provisions with `role=worker`, and before this the minted node came up blank and
    /// counted itself into the core set it was supposed to stay out of.
    ///
    /// Asserts the label AND the classification a PEER reaches from it, because the label alone is only
    /// half the claim on this path — the point of propagating it is that the cluster treats the node as
    /// a non-core.
    @Test
    void provisionedWorker_advertisesTheRoleItWasProvisionedWith_andPeersClassifyItNonCore() {
        var added = provisionWithRole("worker");
        var labels = advertisedLabels(added);

        log.info("ROLE-LABEL: provisioned role=worker -> {} labels={}", added, labels);
        assertThat(labels.get(NodeInfo.LABEL_ROLE))
            .as("a node provisioned with role=worker must advertise it — the provider is the ONLY place "
                + "that role can enter an in-JVM node, and dropping it silently re-creates the #590 "
                + "suppression on a path no test can opt out of. Saw labels=%s", labels)
            .isEqualTo("worker");
        assertThat(classifiedCoreByPeer(added))
            .as("a provisioned worker must classify as NON-core in a peer's membership FSM")
            .isFalse();
    }

    /// The unchanged half, stated on the live path rather than inferred. `core` is the role the CTM
    /// threads for every auto-heal replacement, so this is what the existing provisioning probes
    /// (`PostRestartSlowRejoinDeficitFillProbeTest`, `ProvisioningRecoveryAfterFailureBurstProbeTest`,
    /// `MembershipChaosCycleTest`) now exercise: the label appears where there was none, and the
    /// classification it produces is the SAME core it produced when blank.
    @Test
    void provisionedCore_advertisesCore_andStillClassifiesAsCore() {
        var added = provisionWithRole("core");
        var labels = advertisedLabels(added);

        log.info("ROLE-LABEL: provisioned role=core -> {} labels={}", added, labels);
        assertThat(labels.get(NodeInfo.LABEL_ROLE))
            .as("a node provisioned with role=core must advertise it verbatim. Saw labels=%s", labels)
            .isEqualTo("core");
        assertThat(classifiedCoreByPeer(added))
            .as("stamping role=core must leave the classification exactly where blank left it — this is "
                + "the property that keeps every existing core-provisioning test unaffected")
            .isTrue();
    }

    /// A role that never resolved stamps NO label, rather than `role=""`. Production reaches this shape
    /// through `Main.collectNodeLabels`, which puts the key only when `AETHER_ROLE` is present; the two
    /// are indistinguishable to `isCoreRole` today, but only the empty map matches the wire.
    @Test
    void provisionedWithBlankRole_advertisesNoLabel_matchingAnUnsetAetherRole() {
        var added = provisionWithRole("");
        var labels = advertisedLabels(added);

        log.info("ROLE-LABEL: provisioned role=<blank> -> {} labels={}", added, labels);
        assertThat(labels.containsKey(NodeInfo.LABEL_ROLE))
            .as("an unresolved role must stamp no label at all, not an empty-string one. Saw labels=%s", labels)
            .isFalse();
    }

    /// The equivalence the propagation rests on, asserted directly against the single predicate that
    /// owns the rule. If this ever goes red, stamping `core` where blank used to sit is no longer a
    /// no-op and every core-provisioning path needs re-reading.
    @Test
    void coreRoleAndBlank_areEquivalent_soExistingCoreProvisioningIsUnchanged() {
        assertThat(MemberDescriptor.isCoreRole("core"))
            .as("`core` must classify as core")
            .isEqualTo(MemberDescriptor.isCoreRole(""))
            .isTrue();
        assertThat(MemberDescriptor.isCoreRole("worker"))
            .as("`worker` is the ONLY literal that classifies as non-core — the rule the whole "
                + "community tier is gated on")
            .isFalse();
    }

    /// Provisions through the production boundary: `provision(spec)` runs the static
    /// `ProvisionRequest.resolve` choke and only then reaches `createFrom`. The instance size is the
    /// `default` sentinel the CTM auto-heal path always passes, so the provider's own `in-jvm` default
    /// resolves it — the same resolution production performs.
    private String provisionWithRole(String role) {
        var provider = Option.option(baseProvider.get())
                             .or(() -> {
                                 throw new AssertionError("compute provider was never captured — the decorator "
                                                          + "must be installed before start()");
                             });
        var context = ProvisionContext.provisionContext(Option.empty(),
                                                        role,
                                                        SourceName.DEFAULT,
                                                        ProvisionContext.PROVISIONED_BY_CTM);
        var spec = ProvisionSpec.provisionSpec(InstanceType.ON_DEMAND,
                                               ProvisionRequest.DEFAULT_INSTANCE_SIZE_SENTINEL,
                                               "",
                                               context)
                                .unwrap();

        return add(provider.provision(spec)
                           .await()
                           .onFailure(EmberAddNodeRoleLabelTest::fail)
                           .map(info -> info.nodeId().or(""))
                           .or(""));
    }

    /// Whether a PEER — not the node itself — classifies the new member as core, read from the same
    /// `MemberDescriptor` the FSM's role-scoped projections read. Waits for the SWIM observation to
    /// land; a missing descriptor after the timeout fails loudly rather than defaulting to an answer.
    private boolean classifiedCoreByPeer(String nodeIdStr) {
        var id = NodeId.nodeId(nodeIdStr).unwrap();

        await().atMost(OBSERVE_TIMEOUT)
               .pollInterval(POLL)
               .until(() -> peerDescriptorOf(nodeIdStr, id).isPresent());

        return peerDescriptorOf(nodeIdStr, id).map(MemberDescriptor::isCore)
                                              .or(() -> {
                                                  throw new AssertionError("no peer holds a descriptor for "
                                                                           + nodeIdStr);
                                              });
    }

    /// First descriptor for `id` held by any node OTHER than the subject itself.
    private Option<MemberDescriptor> peerDescriptorOf(String nodeIdStr, NodeId id) {
        return cluster.allNodes()
                      .stream()
                      .filter(node -> !node.topologyManager().self().id().id().equals(nodeIdStr))
                      .map(node -> descriptorFrom(node, id))
                      .flatMap(Option::stream)
                      .findFirst()
                      .map(Option::some)
                      .orElseGet(Option::none);
    }

    private static Option<MemberDescriptor> descriptorFrom(AetherNode node, NodeId id) {
        return node.membershipFsm().memberDescriptor(id);
    }

    /// Reads the node's OWN advertised `NodeInfo` — the field peers and its own `MemberDescriptor`
    /// classify from, and the same one production populates from `AETHER_ROLE`.
    private Map<String, String> advertisedLabels(String nodeId) {
        return cluster.getNode(nodeId)
                      .map(node -> node.topologyManager()
                                       .self()
                                       .labels())
                      .or(Map.of());
    }

    private String add(String nodeId) {
        assertThat(nodeId).as("node id must be non-empty — the add/provision step reported success").isNotBlank();
        addedNodes.add(nodeId);

        return nodeId;
    }

    private static void fail(Cause cause) {
        throw new AssertionError("Ember step failed: " + cause.message());
    }
}
