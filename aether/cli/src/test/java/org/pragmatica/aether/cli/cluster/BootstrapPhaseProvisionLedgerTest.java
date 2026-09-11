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
import org.pragmatica.aether.cli.cluster.ClusterBootstrapOrchestrator.BootstrapContext;
import org.pragmatica.aether.config.cluster.CloudProviderName;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;
import org.pragmatica.aether.config.cluster.ClusterIdentity;
import org.pragmatica.aether.config.cluster.CoreTopology;
import org.pragmatica.aether.config.cluster.InfrastructureConfig;
import org.pragmatica.aether.config.cluster.LoadBalancerMode;
import org.pragmatica.aether.config.cluster.NetworkingType;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.config.cluster.OperationsConfig;
import org.pragmatica.aether.config.cluster.RoleSubTable;
import org.pragmatica.aether.config.cluster.SourceProfile;
import org.pragmatica.aether.config.cluster.SourceType;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.FirewallId;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.InstanceStatus;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.ProvisionRequest;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.aether.environment.ProvisionedNode;
import org.pragmatica.aether.environment.SourceName;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.pragmatica.aether.environment.ClusterName.clusterName;
import static org.pragmatica.aether.environment.FirewallName.firewallName;
import static org.pragmatica.aether.environment.SourceName.sourceNameOrDefault;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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

    /// #994 verification NOTE-7 — the cluster name carries a per-JVM random suffix, and `@AfterEach`
    /// removes the DIRECTORY as well as the state file. `AETHER_DIR` is an interface constant resolved from
    /// `user.home`, so these tests genuinely write the owner's home; with a fixed name, two concurrent
    /// `aether/cli` reactors — the normal arrangement in this workspace, with nine working trees — share one
    /// path and one test's `@BeforeEach` delete races the other's write. Same class as #939's fixed port.
    /// Mirrors `BootstrapStatePersistencePermissionsTest`, which already had this shape.
    private static final ClusterName CLUSTER_NAME = uniqueClusterName();

    private static final SourceName SOURCE = sourceNameOrDefault("hetzner-eu");

    private static final String PROVIDER = "hetzner";

    private static final String RAW_TOML = """
        [cluster]
        name = "%s"

        %s
        type = "cloud"
        provider = "hetzner"
        credentials = "${env:HCLOUD_TOKEN}"
        region = "eu-central"
        """.formatted(CLUSTER_NAME.value(),
                      "[" + ClusterBootstrapConfigParser.SOURCE_PREFIX + "hetzner-eu]");

    private static ClusterName uniqueClusterName() {
        var suffix = new byte[6];

        new SecureRandom().nextBytes(suffix);

        return clusterName("ledger-994-test-" + HexFormat.of().formatHex(suffix)).unwrap();
    }

    @BeforeEach
    void clearState() {
        var _ = BootstrapStatePersistence.delete(CLUSTER_NAME);
    }

    /// `deleteIfExists` on a directory refuses when it is not empty, and that refusal is the safety
    /// property here: anything unexpected in the cluster directory is left alone rather than swept.
    @AfterEach
    @SuppressWarnings("JBCT-EX-01")
    void removeState() throws Exception {
        var _ = BootstrapStatePersistence.delete(CLUSTER_NAME);

        try {
            Files.deleteIfExists(BootstrapStatePersistence.AETHER_DIR.resolve(CLUSTER_NAME.value()));
        } catch (DirectoryNotEmptyException _) {
            // Something else is in there — not this test's to remove.
        }
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

    // ---------------------------------------------------------------------------------------------
    // #994 verification finding SF-2 — the RECORDING IS WIRED INTO PRODUCTION, pinned at the real call
    // site rather than one level below it.
    // ---------------------------------------------------------------------------------------------

    private static ClusterBootstrapConfig configWith(SourceProfile source) {
        return ClusterBootstrapConfig.clusterBootstrapConfig("1.0.0",
                                                            ClusterIdentity.clusterIdentity(CLUSTER_NAME.value(), "1.0.0").unwrap(),
                                                            CoreTopology.defaultCoreTopology(),
                                                            Map.of(SOURCE.value(), source),
                                                            Map.of(),
                                                            InfrastructureConfig.infrastructureConfig(NetworkingType.MANUAL),
                                                            OperationsConfig.defaultOperationsConfig());
    }

    private static BootstrapContext contextFor(SourceProfile source) {
        var state = BootstrapState.initialState(CLUSTER_NAME, "hash-1", "2026-09-11T00:00:00Z")
                                  .withClusterSecret("test-secret-xyz");

        return BootstrapContext.bootstrapContext(configWith(source), state, List.of(), List.of())
                               .withClusterSecret("test-secret-xyz")
                               .withRawTomlContent(RAW_TOML);
    }

    /// The same scripted shape as [QuotaLimitedProvisioner], one layer lower: a real [ComputeProvider], so
    /// the test enters through `provisionCloudRoleGroup` — the function `provisionCloudWithCompute` actually
    /// calls — and everything between it and the ledger is production code.
    private static final class QuotaLimitedCompute implements ComputeProvider {
        private final int successes;
        private final List<ProvisionSpec> provisioned = new ArrayList<>();

        private QuotaLimitedCompute(int successes) {
            this.successes = successes;
        }

        @Override
        public Promise<InstanceInfo> provision(ProvisionSpec spec) {
            provisioned.add(spec);

            return provisioned.size() <= successes
                   ? Promise.success(info(provisioned.size() - 1))
                   : EnvironmentError.provisionFailed(new RuntimeException("Hetzner API error 403 "
                                                                        + "(resource_limit_exceeded): dedicated core limit exceeded"))
                                     .promise();
        }

        private static InstanceInfo info(int index) {
            return new InstanceInfo(new InstanceId("server-" + index),
                                    InstanceStatus.RUNNING,
                                    List.of("203.0.113." + index),
                                    InstanceType.ON_DEMAND,
                                    Map.of());
        }

        @Override public Promise<InstanceInfo> createFrom(ProvisionRequest request) {return Promise.success(info(0));}

        @Override public Promise<Unit> terminate(InstanceId instanceId) {return Promise.success(Unit.unit());}

        @Override public Promise<List<InstanceInfo>> listInstances() {return Promise.success(List.of());}

        @Override public Promise<InstanceInfo> instanceStatus(InstanceId instanceId) {return Promise.success(info(0));}
    }

    @Nested
    class ProductionWiresTheRecorder {

        /// #994 verification finding SF-2. `provisionAndRecordRoleGroup` being pinned says nothing about
        /// whether production still CALLS it: replacing the call in `provisionCloudRoleGroup` with a direct
        /// `rotateZonesForRoleGroup` — deleting the whole recording behaviour — left all 724 tests green.
        /// That is the same defect class as #994 itself, which was a correct mechanism that never ran.
        ///
        /// So this drives `provisionCloudRoleGroup`, the function `provisionCloudWithCompute` calls, through
        /// a real `ComputeProvider`. Every layer between the provider's "created" and the state file is
        /// production code.
        @Test
        void provisionCloudRoleGroup_recordsEveryVmCreated_whenALaterNodeIsRefused() {
            seedFirewallOnlyLedger();
            var source = cloudSource();
            var compute = new QuotaLimitedCompute(2);

            var result = BootstrapPhaseProvision.provisionCloudRoleGroup(compute,
                                                                         contextFor(source),
                                                                         SOURCE,
                                                                         NodeRole.CORE,
                                                                         3,
                                                                         source,
                                                                         CLUSTER_NAME,
                                                                         0);

            assertTrue(result.isFailure(), "the group must still fail — recording is not a recovery");
            assertEquals(3, compute.provisioned.size(), "all three nodes must be attempted, the third refused");
            assertEquals(List.of("server-0", "server-1"),
                         persistedVms().stream().map(CreatedResource.ProvisionedVm::resourceId).toList(),
                         "the VMs must reach the PERSISTED ledger through production's OWN call into "
                         + "provisionAndRecordRoleGroup — bypassing it here is exactly the regression #994 was");
        }

        /// Positive control: the assertion above is about records APPEARING, so it cannot be satisfied by a
        /// provisioner that never ran. All three succeed, all three are recorded with the role and source
        /// teardown resolves credentials by.
        @Test
        void provisionCloudRoleGroup_recordsTheWholeGroup_whenEveryNodeSucceeds() {
            seedFirewallOnlyLedger();
            var source = cloudSource();
            var compute = new QuotaLimitedCompute(3);

            var result = BootstrapPhaseProvision.provisionCloudRoleGroup(compute,
                                                                         contextFor(source),
                                                                         SOURCE,
                                                                         NodeRole.CORE,
                                                                         3,
                                                                         source,
                                                                         CLUSTER_NAME,
                                                                         0);

            assertTrue(result.isSuccess(), () -> "precondition: every node is created: " + result);
            assertEquals(List.of("server-0", "server-1", "server-2"),
                         persistedVms().stream().map(CreatedResource.ProvisionedVm::resourceId).toList());
            assertEquals(List.of("core", "core", "core"),
                         persistedVms().stream().map(CreatedResource.ProvisionedVm::role).toList(),
                         "the role reaches the ledger from the call site's own argument, not from parsing a node id");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // #994 verification finding SF-1 — the ledger is the only record of money-bearing resources, so every
    // way a write or a read can fail has to be AUDIBLE, and an unreadable ledger must never be overwritten.
    // ---------------------------------------------------------------------------------------------

    @Nested
    class LedgerFailuresAreLoudAndNonDestructive {

        private final ByteArrayOutputStream err = new ByteArrayOutputStream();

        private PrintStream originalErr;

        @BeforeEach
        void captureErr() {
            originalErr = System.err;
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        }

        @AfterEach
        void restoreErr() {
            System.setErr(originalErr);
        }

        private String stderr() {
            return err.toString(StandardCharsets.UTF_8);
        }

        /// Writes a HALF of a real, valid state file — the shape `TRUNCATE_EXISTING` used to leave behind
        /// when the process died mid-write, and the shape the verifier measured loads as EMPTY.
        @SuppressWarnings("JBCT-EX-01")
        private byte[] tearTheLedger() throws Exception {
            seedFirewallOnlyLedger();
            BootstrapPhaseProvision.recordProvisionedVm(CLUSTER_NAME, PROVIDER, SOURCE, NodeRole.CORE, node("n-0", "server-1"));
            var path = BootstrapStatePersistence.statePath(CLUSTER_NAME);
            var whole = Files.readAllBytes(path);
            var torn = Arrays.copyOf(whole, whole.length / 2);

            Files.write(path, torn);

            assertTrue(BootstrapStatePersistence.read(CLUSTER_NAME).isFailure(),
                       "precondition: a half-written file must read as a FAILURE, not as empty");

            return torn;
        }

        /// **The measured torn-write chain (verification §4b), now refused.** `markPhaseFailed` fell back to
        /// the pre-phase snapshot whenever `load` came back empty — and `load` returns empty for an
        /// UNREADABLE file just as it does for an absent one. Saving the snapshot over a torn ledger replaces
        /// the only record of paid VMs with a VM-less one that is **valid JSON**, so nothing downstream can
        /// tell it ever held anything. This asserts the bytes are untouched, which is the only observation
        /// that distinguishes "refused" from "rewrote it with the same intent".
        @Test
        @SuppressWarnings("JBCT-EX-01")
        void markPhaseFailed_leavesTheTornLedgerByteForByte_ratherThanOverwritingIt() throws Exception {
            var torn = tearTheLedger();
            var preSnapshot = BootstrapState.initialState(CLUSTER_NAME, "hash-1", "2026-09-11T00:00:00Z");

            ClusterBootstrapOrchestrator.markPhaseFailed(preSnapshot, BootstrapPhase.PROVISION);

            assertArrayEquals(torn,
                              Files.readAllBytes(BootstrapStatePersistence.statePath(CLUSTER_NAME)),
                              "the unreadable bytes are the only surviving trace of the paid VMs — overwriting "
                              + "them with a VM-less snapshot is #994's outcome reached by a different route");
            assertTrue(stderr().contains("REFUSING"),
                       () -> "and the refusal must be said out loud, or a silent no-op is indistinguishable "
                             + "from a successful marker; got:\n" + stderr());
            assertTrue(stderr().contains("cloud-reaper.sh"),
                       () -> "with the recovery action beside it; got:\n" + stderr());
        }

        /// Positive control for the assertion above: over a READABLE ledger `markPhaseFailed` still writes.
        /// Without it, "the bytes did not change" is also satisfied by a `markPhaseFailed` that never writes
        /// at all, and the #994 fix would be silently undone.
        @Test
        @SuppressWarnings("JBCT-EX-01")
        void markPhaseFailed_stillWritesTheMarker_whenTheLedgerIsReadable() throws Exception {
            seedFirewallOnlyLedger();
            var before = Files.readAllBytes(BootstrapStatePersistence.statePath(CLUSTER_NAME));
            var preSnapshot = BootstrapState.initialState(CLUSTER_NAME, "hash-1", "2026-09-11T00:00:00Z");

            ClusterBootstrapOrchestrator.markPhaseFailed(preSnapshot, BootstrapPhase.PROVISION);

            assertFalse(Arrays.equals(before, Files.readAllBytes(BootstrapStatePersistence.statePath(CLUSTER_NAME))),
                        "a readable ledger MUST be updated — this is what proves the refusal above is "
                        + "conditional rather than a method that stopped writing");
            assertFalse(stderr().contains("REFUSING"),
                        () -> "and nothing is refused on the normal path; got:\n" + stderr());
        }

        /// `recordProvisionedVm` was a silent no-op whenever the ledger was absent — which is exactly what an
        /// unchecked pre-phase save leaves behind. The server is already created and already billing at that
        /// point, so the id is the only thing that can still save the operator money; it goes to stderr even
        /// though the ledger cannot hold it. The VM is NOT a phantom: the provider reported it created.
        @Test
        void recordProvisionedVm_printsTheServerId_whenThereIsNoLedgerToRecordItIn() {
            BootstrapPhaseProvision.recordProvisionedVm(CLUSTER_NAME, PROVIDER, SOURCE, NodeRole.CORE, node("n-0", "server-77"));

            assertTrue(stderr().contains("server-77"),
                       () -> "the id is the whole recovery: without it the server cannot be named at all; "
                             + "got:\n" + stderr());
            assertTrue(stderr().contains("PAID"),
                       () -> "and the operator must be told it costs money; got:\n" + stderr());
            assertTrue(stderr().contains("cloud-reaper.sh"),
                       () -> "with what removes it; got:\n" + stderr());
        }

        /// Positive control: the same call with a ledger present records and says NOTHING. Without it,
        /// "stderr names the server" could be an always-on line printed on the happy path too, which would
        /// train an operator to ignore it.
        @Test
        void recordProvisionedVm_warnsAboutNothing_whenTheLedgerAcceptsTheRecord() {
            seedFirewallOnlyLedger();

            BootstrapPhaseProvision.recordProvisionedVm(CLUSTER_NAME, PROVIDER, SOURCE, NodeRole.CORE, node("n-0", "server-77"));

            assertEquals(List.of("server-77"),
                         persistedVms().stream().map(CreatedResource.ProvisionedVm::resourceId).toList(),
                         "precondition: the record landed");
            assertFalse(stderr().contains("NOT recorded"),
                        () -> "a successful record must be silent; got:\n" + stderr());
        }

        /// The other silent no-op: the per-source cleanup handle. The real incident artifact recorded
        /// `sources: []`, so teardown had no credential mapping at all — and nothing in the transcript said
        /// the handle had not been written.
        @Test
        void persistCleanupHandle_saysSo_whenThereIsNoLedgerToWriteInto() {
            BootstrapPhaseProvision.persistCleanupHandle(CLUSTER_NAME, RAW_TOML, SOURCE, cloudSource());

            assertTrue(stderr().contains("cleanup handle"),
                       () -> "the missing handle must be reported; got:\n" + stderr());
            assertTrue(stderr().contains(SOURCE.value()),
                       () -> "naming the source teardown would have resolved credentials for; got:\n" + stderr());
        }

        @Test
        void persistCleanupHandle_warnsAboutNothing_whenTheLedgerAcceptsTheHandle() {
            seedFirewallOnlyLedger();

            BootstrapPhaseProvision.persistCleanupHandle(CLUSTER_NAME, RAW_TOML, SOURCE, cloudSource());

            assertFalse(stderr().contains("NOT persisted"),
                        () -> "positive control for the case above; got:\n" + stderr());
        }

        /// SF-1's atomic-write half, observed from the reader's side: a save OVER a torn file must leave a
        /// file that parses. Under the old in-place `TRUNCATE_EXISTING` write this held only if the write
        /// completed; the point of the temp-and-rename is that the reader never sees the intermediate state.
        @Test
        @SuppressWarnings("JBCT-EX-01")
        void save_replacesATornLedgerWholesale_soTheNextReadParses() throws Exception {
            var _ = tearTheLedger();

            assertTrue(BootstrapStatePersistence.save(BootstrapState.initialState(CLUSTER_NAME, "hash-2", "2026-09-11T01:00:00Z"))
                                                .isSuccess(),
                       "the save itself must succeed over an unparseable file");
            assertTrue(BootstrapStatePersistence.read(CLUSTER_NAME).isSuccess(),
                       "and the result must parse — a partial overwrite would leave the tail of the old file");
            try (var entries = Files.list(BootstrapStatePersistence.AETHER_DIR.resolve(CLUSTER_NAME.value()))) {
                assertTrue(entries.noneMatch(entry -> entry.getFileName().toString().endsWith(".tmp")),
                           "and the temp file must not be left beside the real one");
            }
        }
    }
}
