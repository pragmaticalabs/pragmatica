// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.cli.cluster.BootstrapPhaseProvision.ZoneProvisioner;
import org.pragmatica.aether.config.cluster.CloudProviderName;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;
import org.pragmatica.aether.config.cluster.LoadBalancerMode;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.config.cluster.RoleSubTable;
import org.pragmatica.aether.config.cluster.SourceProfile;
import org.pragmatica.aether.config.cluster.SourceType;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.FirewallId;
import org.pragmatica.aether.environment.ProvisionedNode;
import org.pragmatica.aether.environment.SourceName;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.pragmatica.aether.environment.ClusterName.clusterName;
import static org.pragmatica.aether.environment.FirewallName.firewallName;
import static org.pragmatica.aether.environment.SourceName.sourceNameOrDefault;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.Result.success;

/// #994 — the money path's central property: **a paid VM must be in the persisted cleanup ledger before
/// anything can go wrong with the NEXT one.**
///
/// The defect these tests pin was not a bad delete order. `BootstrapCleanup.inDestructionOrder` already
/// ranked VMs ahead of firewalls. It was that the ledger was written ONLY by
/// `BootstrapPhaseProvision.buildUpdatedState`, which runs after every source has provisioned
/// successfully — so when a dedicated-core quota refused the third of three `ccx23` servers on
/// 2026-09-11, the failing phase returned the pre-phase context, the ledger held the firewall and no VMs,
/// and teardown issued zero server deletes while two servers kept billing.
///
/// These tests use the REAL persistence path (`BootstrapStatePersistence`, whose directory is an
/// interface constant resolved from `user.home` at class load, so a temp dir cannot be substituted) under
/// a cluster name owned by this test, deleted before and after each case.
class BootstrapPhaseProvisionLedgerTest {

    private static final ClusterName CLUSTER_NAME = clusterName("ledger-994-test").unwrap();

    private static final SourceName SOURCE = sourceNameOrDefault("hetzner-eu");

    private static final String PROVIDER = "hetzner";

    private static final String RAW_TOML = """
        [cluster]
        name = "ledger-994-test"

        %s
        type = "cloud"
        provider = "hetzner"
        credentials = "${env:HCLOUD_TOKEN}"
        region = "eu-central"
        """.formatted("[" + ClusterBootstrapConfigParser.SOURCE_PREFIX + "hetzner-eu]");

    @BeforeEach
    void clearState() {
        var _ = BootstrapStatePersistence.delete(CLUSTER_NAME);
    }

    @AfterEach
    void removeState() {
        var _ = BootstrapStatePersistence.delete(CLUSTER_NAME);
    }

    /// The ledger exactly as Phase 3 (CREATE_FIREWALL) leaves it: a firewall and nothing else. This is the
    /// state the observed run's cleanup actually read.
    private static void seedFirewallOnlyLedger() {
        var seeded = BootstrapState.initialState(CLUSTER_NAME, "hash-1", "2026-09-11T00:00:00Z")
                                  .withResource(CreatedResource.CloudFirewall.cloudFirewall(PROVIDER,
                                                                                            FirewallId.firewallId("11605223").unwrap(),
                                                                                            SOURCE,
                                                                                            firewallName("aether-ledger-994-primary").unwrap()));

        assertTrue(BootstrapStatePersistence.save(seeded).isSuccess(), "test seed must persist");
    }

    private static List<CreatedResource.ProvisionedVm> persistedVms() {
        return BootstrapStatePersistence.load(CLUSTER_NAME)
                                        .map(BootstrapState::createdResources)
                                        .or(List.of())
                                        .stream()
                                        .filter(resource -> resource instanceof CreatedResource.ProvisionedVm)
                                        .map(resource -> (CreatedResource.ProvisionedVm) resource)
                                        .toList();
    }

    private static ProvisionedNode node(String nodeId, String serverId) {
        return ProvisionedNode.provisionedNode(nodeId, serverId, "10.0.0.1");
    }

    /// Scripts the observed shape: the first `successes` nodes are created, the next attempt is refused the
    /// way Hetzner refused it — `403 resource_limit_exceeded`, which is NOT capacity-unavailable, so zone
    /// rotation does not retry it and the group fails immediately.
    private static final class QuotaLimitedProvisioner implements ZoneProvisioner {
        private final int successes;
        private final List<String> attempted = new ArrayList<>();

        private QuotaLimitedProvisioner(int successes) {
            this.successes = successes;
        }

        @Override
        public Result<ProvisionedNode> provisionInZone(String nodeId, int globalIndex, String zone) {
            attempted.add(nodeId);

            return attempted.size() <= successes
                   ? success(node(nodeId, "server-" + globalIndex))
                   : EnvironmentError.provisionFailed(new RuntimeException("Hetzner API error 403 "
                                                                         + "(resource_limit_exceeded): dedicated core limit exceeded"))
                                     .result();
        }
    }

    private static SourceProfile cloudSource() {
        return SourceProfile.sourceProfile(SOURCE,
                                           SourceType.CLOUD,
                                           Option.some(CloudProviderName.HETZNER),
                                           Option.some("resolved-token-value"),
                                           Option.some("eu-central"),
                                           Option.empty(),
                                           Option.empty(),
                                           Option.empty(),
                                           Option.empty(),
                                           LoadBalancerMode.NONE,
                                           List.of(),
                                           Option.empty(),
                                           Map.of(),
                                           Map.of(NodeRole.CORE,
                                                  RoleSubTable.roleSubTable(NodeRole.CORE,
                                                                            Option.some(3),
                                                                            Option.empty(),
                                                                            Option.empty(),
                                                                            "default")),
                                           List.of());
    }

    /// The recording aspect wrapped around a scripted provisioner, for the two attempt-level controls below.
    /// The group-level regression test does NOT use this: it calls
    /// `BootstrapPhaseProvision.provisionAndRecordRoleGroup`, the function production itself calls, so the
    /// line that wires the aspect to the recorder is covered rather than re-assembled here.
    private static ZoneProvisioner productionSeam(ZoneProvisioner provisionOne) {
        return BootstrapPhaseProvision.recordingProvisioner(provisionOne,
                                                            node -> BootstrapPhaseProvision.recordProvisionedVm(CLUSTER_NAME,
                                                                                                                PROVIDER,
                                                                                                                SOURCE,
                                                                                                                NodeRole.CORE,
                                                                                                                node));
    }

    @Nested
    class LedgerSurvivesMidGroupRefusal {

        /// THE #994 REGRESSION. Three cores requested, the provider creates two and refuses the third.
        /// Before the fix the ledger still held only the firewall and the two servers billed until a human
        /// found them; after it, both are nameable by id.
        @Test
        void provisionAndRecordRoleGroup_persistsEveryVmCreated_whenALaterNodeIsRefused() {
            seedFirewallOnlyLedger();
            var provisioner = new QuotaLimitedProvisioner(2);

            var result = BootstrapPhaseProvision.provisionAndRecordRoleGroup(CLUSTER_NAME,
                                                                             PROVIDER,
                                                                             SOURCE,
                                                                             NodeRole.CORE,
                                                                             3,
                                                                             0,
                                                                             List.of(),
                                                                             provisioner);

            assertTrue(result.isFailure(), "the group must still fail — recording is not a recovery");
            assertEquals(List.of("hetzner-eu-core-0", "hetzner-eu-core-1", "hetzner-eu-core-2"),
                         provisioner.attempted,
                         "all three nodes must be attempted, the third refused");
            assertEquals(List.of("server-0", "server-1"),
                         persistedVms().stream().map(CreatedResource.ProvisionedVm::resourceId).toList(),
                         "both created servers must be in the PERSISTED ledger: the failing phase returns no "
                         + "context, so the state file is teardown's only source of their ids");
        }

        /// Scope guard on the same record: teardown resolves credentials per SOURCE and terminates by
        /// provider, so a record with the wrong source or provider is a record cleanup cannot act on.
        @Test
        void recordProvisionedVm_recordsProviderSourceAndRole_soTeardownCanResolveCredentials() {
            seedFirewallOnlyLedger();

            BootstrapPhaseProvision.recordProvisionedVm(CLUSTER_NAME, PROVIDER, SOURCE, NodeRole.CORE, node("n-0", "server-77"));

            var vms = persistedVms();

            assertEquals(1, vms.size(), "exactly one VM record");
            assertEquals(PROVIDER, vms.getFirst().provider());
            assertEquals("server-77", vms.getFirst().resourceId());
            assertEquals("hetzner-eu", vms.getFirst().sourceName());
            assertEquals("core", vms.getFirst().role());
        }

        /// The firewall must survive the VM appends — cleanup needs BOTH, and in that order.
        @Test
        void recordProvisionedVm_keepsResourcesAlreadyInTheLedger() {
            seedFirewallOnlyLedger();

            BootstrapPhaseProvision.recordProvisionedVm(CLUSTER_NAME, PROVIDER, SOURCE, NodeRole.CORE, node("n-0", "server-1"));

            var resources = BootstrapStatePersistence.load(CLUSTER_NAME)
                                                     .map(BootstrapState::createdResources)
                                                     .or(List.of());

            assertEquals(2, resources.size(), "the firewall recorded by Phase 3 must still be there");
            assertEquals(1,
                         resources.stream().filter(r -> r instanceof CreatedResource.CloudFirewall).count(),
                         "firewall record preserved");
        }

        /// NEGATIVE CONTROL, and it is not symmetry for its own sake: recording a VM that was never created
        /// would make teardown issue a delete for a phantom id, and a 404 on a phantom is indistinguishable
        /// from a 404 on a server someone else already removed.
        @Test
        void recordingProvisioner_recordsNothing_whenTheAttemptFailed() {
            seedFirewallOnlyLedger();
            var provisioner = new QuotaLimitedProvisioner(0);

            var result = productionSeam(provisioner).provisionInZone("hetzner-eu-core-0", 0, "fsn1");

            assertTrue(result.isFailure(), "the scripted attempt fails");
            assertTrue(persistedVms().isEmpty(),
                       "a refused attempt created no server, so it must add no record — a phantom id makes "
                       + "teardown delete nothing and report success");
        }

        /// Positive control for the assertion above: the same seam, the same ledger, a SUCCEEDING attempt.
        /// Without it, `persistedVms().isEmpty()` is also satisfied by a recorder that never runs at all.
        @Test
        void recordingProvisioner_recordsTheNode_whenTheAttemptSucceeded() {
            seedFirewallOnlyLedger();
            var provisioner = new QuotaLimitedProvisioner(1);

            var result = productionSeam(provisioner).provisionInZone("hetzner-eu-core-0", 0, "fsn1");

            assertTrue(result.isSuccess(), "the scripted attempt succeeds");
            assertEquals(List.of("server-0"),
                         persistedVms().stream().map(CreatedResource.ProvisionedVm::resourceId).toList(),
                         "the control proves the recorder CAN write, so the negative case above is a real absence");
        }
    }

    @Nested
    class CleanupHandleAvailableBeforeProvisioning {

        /// Recording the VM is not enough on its own: `BootstrapCleanup.resolveComputeForVm` reads the
        /// per-source handle to re-derive the provisioning token, and that handle was ALSO written only by
        /// `buildUpdatedState`. With no handle, teardown of a mid-provision failure falls back to a raw
        /// env var that may name a different account — or, under the scoped-credential mechanism, nothing
        /// at all.
        @Test
        void persistCleanupHandle_writesTheSourceHandle_beforeAnyVmExists() {
            seedFirewallOnlyLedger();

            BootstrapPhaseProvision.persistCleanupHandle(CLUSTER_NAME, RAW_TOML, SOURCE, cloudSource());

            var handle = BootstrapStatePersistence.load(CLUSTER_NAME)
                                                 .map(BootstrapState::sources)
                                                 .or(Map.of())
                                                 .get("hetzner-eu");

            assertTrue(handle != null, "a cloud source must have its cleanup handle persisted before provisioning");
            assertEquals("hetzner", handle.provider());
            assertEquals("HCLOUD_TOKEN",
                         handle.credentialEnvVars().get("api_token"),
                         "the env-var NAME teardown needs to re-derive the token");
        }

        @Test
        void persistCleanupHandle_leavesStateUntouched_forNonCloudSource() {
            seedFirewallOnlyLedger();
            var docker = SourceProfile.sourceProfile(SOURCE,
                                                     SourceType.DOCKER,
                                                     Option.empty(),
                                                     Option.empty(),
                                                     Option.empty(),
                                                     Option.empty(),
                                                     Option.empty(),
                                                     Option.empty(),
                                                     Option.empty(),
                                                     LoadBalancerMode.NONE,
                                                     List.of(),
                                                     Option.empty(),
                                                     Map.of(),
                                                     Map.of(),
                                                     List.of());

            BootstrapPhaseProvision.persistCleanupHandle(CLUSTER_NAME, RAW_TOML, SOURCE, docker);

            assertTrue(BootstrapStatePersistence.load(CLUSTER_NAME)
                                                .map(BootstrapState::sources)
                                                .or(Map.of())
                                                .isEmpty(),
                       "a docker source has no cloud credential to re-derive — no handle should be stamped");
        }
    }

    @Nested
    class FailedPhaseMarkerPreservesLedger {

        /// The other half of the fix, and on its own the whole defect would persist: the orchestrator marks
        /// the phase FAILED from the PRE-phase snapshot, so a save of that snapshot erases everything the
        /// phase appended to the file while it ran.
        @Test
        void markPhaseFailed_preservesResourcesTheFailingPhaseRecorded() {
            seedFirewallOnlyLedger();
            var preSnapshot = BootstrapStatePersistence.load(CLUSTER_NAME).or(BootstrapState.initialState(CLUSTER_NAME, "h", "t"));

            BootstrapPhaseProvision.recordProvisionedVm(CLUSTER_NAME, PROVIDER, SOURCE, NodeRole.CORE, node("n-0", "server-1"));
            BootstrapPhaseProvision.recordProvisionedVm(CLUSTER_NAME, PROVIDER, SOURCE, NodeRole.CORE, node("n-1", "server-2"));

            ClusterBootstrapOrchestrator.markPhaseFailed(preSnapshot, BootstrapPhase.PROVISION);

            assertEquals(List.of("server-1", "server-2"),
                         persistedVms().stream().map(CreatedResource.ProvisionedVm::resourceId).toList(),
                         "marking PROVISION as FAILED must not roll the ledger back to the pre-phase snapshot");
        }

        @Test
        void markPhaseFailed_stillRecordsTheFailedPhaseStatus() {
            seedFirewallOnlyLedger();
            var preSnapshot = BootstrapStatePersistence.load(CLUSTER_NAME).or(BootstrapState.initialState(CLUSTER_NAME, "h", "t"));

            ClusterBootstrapOrchestrator.markPhaseFailed(preSnapshot, BootstrapPhase.PROVISION);

            BootstrapStatePersistence.load(CLUSTER_NAME)
                                     .onEmpty(() -> fail("state must still be persisted"))
                                     .onPresent(state -> assertEquals(BootstrapState.PhaseStatus.FAILED,
                                                                      state.phases().get(BootstrapPhase.PROVISION),
                                                                      "resume logic reads this status — preserving the ledger must not cost it"));
        }

        /// A phase that fails with nothing persisted must still produce a state file, or `cleanupOnFailure`
        /// has nothing to load and `--keep-on-failure` can report no phase at all.
        @Test
        void markPhaseFailed_fallsBackToTheSnapshot_whenNothingIsPersisted() {
            var snapshot = BootstrapState.initialState(CLUSTER_NAME, "hash-1", "2026-09-11T00:00:00Z");

            assertFalse(BootstrapStatePersistence.load(CLUSTER_NAME).isPresent(), "precondition: no state on disk");

            ClusterBootstrapOrchestrator.markPhaseFailed(snapshot, BootstrapPhase.PROVISION);

            BootstrapStatePersistence.load(CLUSTER_NAME)
                                     .onEmpty(() -> fail("the snapshot fallback must still write a state file"))
                                     .onPresent(state -> assertEquals(BootstrapState.PhaseStatus.FAILED,
                                                                      state.phases().get(BootstrapPhase.PROVISION)));
        }
    }
}
