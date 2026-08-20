// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.cli.cluster.BootstrapState.PhaseStatus;
import org.pragmatica.aether.cli.cluster.CreatedResource.ProvisionedVm;
import org.pragmatica.aether.cli.cluster.CreatedResource.SshKeyResource;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.FirewallId;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.ProvisionRequest;
import org.pragmatica.cloud.hetzner.HetznerClient;
import org.pragmatica.cloud.hetzner.HetznerError;
import org.pragmatica.cloud.hetzner.api.Firewall;
import org.pragmatica.cloud.hetzner.api.FloatingIp;
import org.pragmatica.cloud.hetzner.api.LoadBalancer;
import org.pragmatica.cloud.hetzner.api.Network;
import org.pragmatica.cloud.hetzner.api.Server;
import org.pragmatica.cloud.hetzner.api.Server.CreateServerRequest;
import org.pragmatica.cloud.hetzner.api.SshKey;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.pragmatica.aether.environment.ClusterName.clusterName;
import static org.pragmatica.aether.environment.FirewallName.firewallName;
import static org.pragmatica.aether.environment.SourceName.sourceNameOrDefault;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class BootstrapCleanupTest {

    private static final ClusterName CLUSTER_NAME = clusterName("test-cleanup-cluster").unwrap();

    private static BootstrapState stateWithVm(String provider, String vmId) {
        var phases = new EnumMap<BootstrapPhase, PhaseStatus>(BootstrapPhase.class);
        for (var phase : BootstrapPhase.values()) {
            phases.put(phase, PhaseStatus.COMPLETED);
        }
        var resources = List.<CreatedResource>of(new ProvisionedVm(provider, vmId, "core-source", "core"));
        return BootstrapState.bootstrapState(CLUSTER_NAME,
                                             "hash-1",
                                             "2026-05-01T00:00:00Z",
                                             phases,
                                             resources,
                                             List.of(),
                                             List.of());
    }

    private static Fn1<Result<ComputeProvider>, String> recordingResolver(List<String> resolverCalls,
                                                                           List<String> terminateCalls) {
        return providerName -> recordResolverCall(providerName, resolverCalls, terminateCalls);
    }

    private static Result<ComputeProvider> recordResolverCall(String providerName,
                                                              List<String> resolverCalls,
                                                              List<String> terminateCalls) {
        resolverCalls.add(providerName);
        return Result.success(recordingCompute(terminateCalls));
    }

    private static Fn1<Result<ComputeProvider>, String> failingResolver(List<String> resolverCalls) {
        return providerName -> recordFailingCall(providerName, resolverCalls);
    }

    private static Result<ComputeProvider> recordFailingCall(String providerName, List<String> resolverCalls) {
        resolverCalls.add(providerName);
        return new TestCause("no factory for '" + providerName + "'").result();
    }

    @Test
    void cleanup_resolvesProvider_byVmProviderField() {
        var resolverCalls = new ArrayList<String>();
        var terminateCalls = new ArrayList<String>();
        var resolver = recordingResolver(resolverCalls, terminateCalls);

        var state = stateWithVm("hetzner", "vm-1");

        var result = BootstrapCleanup.cleanup(state, resolver);

        assertTrue(result.isSuccess(), "cleanup should succeed for hetzner provider lookup");
        assertEquals(List.of("hetzner"), resolverCalls,
                     "resolver must be called with VM.provider() = 'hetzner', not the source type 'cloud'");
        assertEquals(List.of("vm-1"), terminateCalls,
                     "compute provider must terminate the VM by its resource id");
    }

    @Test
    void cleanup_passesProviderName_toResolverEvenWhenLookupFails() {
        var resolverCalls = new ArrayList<String>();
        var resolver = failingResolver(resolverCalls);

        var state = stateWithVm("hetzner", "vm-99");

        var result = BootstrapCleanup.cleanup(state, resolver);

        assertTrue(result.isFailure(), "cleanup should fail when provider lookup fails");
        assertEquals(List.of("hetzner"), resolverCalls,
                     "resolver must receive the VM provider name even on failure");
    }

    @Test
    void cleanup_passesAwsProvider_whenSourceProvisionedAws() {
        var resolverCalls = new ArrayList<String>();
        var terminateCalls = new ArrayList<String>();
        var resolver = recordingResolver(resolverCalls, terminateCalls);

        var state = stateWithVm("aws", "i-abc123");

        var result = BootstrapCleanup.cleanup(state, resolver);

        assertTrue(result.isSuccess());
        assertEquals(List.of("aws"), resolverCalls,
                     "AWS provider name must round-trip through the cleanup resolver");
        assertEquals(List.of("i-abc123"), terminateCalls);
    }

    /// A destroy that failed for any reason must be able to SUCCEED on retry. Before this, the retry
    /// re-terminated VMs the first pass had already deleted; Hetzner answered 404, cleanup counted
    /// that as failure, and the registry entry stayed KEPT forever. Observed live on 2026-08-05.
    @Test
    void cleanup_treatsAlreadyGoneVm_asDestroyed() {
        var state = stateWithVm("hetzner", "vm-already-gone");

        var result = BootstrapCleanup.cleanup(state,
                                              _ -> Result.success(new VanishedComputeProvider()));

        assertTrue(result.isSuccess(),
                   () -> "a VM that is already gone is the outcome destroy wanted: " + result);
    }

    @Test
    void cleanup_stillFails_whenTerminateFailsForAnyOtherReason() {
        var state = stateWithVm("hetzner", "vm-stuck");

        var result = BootstrapCleanup.cleanup(state,
                                              _ -> Result.success(new FailingComputeProvider()));

        assertFalse(result.isSuccess(), "a real termination failure must still surface");
    }

    /// Terminate always reports the instance as absent.
    record VanishedComputeProvider() implements ComputeProvider {
        @Override public Promise<InstanceInfo> createFrom(ProvisionRequest request) {
            return new TestCause("unused").promise();
        }

        @Override public Promise<Unit> terminate(InstanceId instanceId) {
            return org.pragmatica.aether.environment.EnvironmentError.InstanceNotFound
                    .instanceNotFound(instanceId).unwrap().promise();
        }

        @Override public Promise<List<InstanceInfo>> listInstances() {
            return Promise.success(List.of());
        }

        @Override public Promise<InstanceInfo> instanceStatus(InstanceId instanceId) {
            return new TestCause("unused").promise();
        }
    }

    /// Terminate fails for a reason that is NOT "already gone".
    record FailingComputeProvider() implements ComputeProvider {
        @Override public Promise<InstanceInfo> createFrom(ProvisionRequest request) {
            return new TestCause("unused").promise();
        }

        @Override public Promise<Unit> terminate(InstanceId instanceId) {
            return new TestCause("rate limited").promise();
        }

        @Override public Promise<List<InstanceInfo>> listInstances() {
            return Promise.success(List.of());
        }

        @Override public Promise<InstanceInfo> instanceStatus(InstanceId instanceId) {
            return new TestCause("unused").promise();
        }
    }

    private static ComputeProvider recordingCompute(List<String> terminateCalls) {
        return new RecordingComputeProvider(terminateCalls);
    }

    /**
     * Test double for {@link ComputeProvider}. JBCT permits these in tests as
     * stubs cannot be expressed as lambdas (multi-method interface).
     */
    record RecordingComputeProvider(List<String> terminateCalls) implements ComputeProvider {
        @Override public Promise<InstanceInfo> createFrom(ProvisionRequest request) {
            return new TestCause("provision not used").promise();
        }

        @Override public Promise<Unit> terminate(InstanceId instanceId) {
            terminateCalls.add(instanceId.value());
            return Promise.success(Unit.unit());
        }

        @Override public Promise<List<InstanceInfo>> listInstances() {
            return Promise.success(List.of());
        }

        @Override public Promise<InstanceInfo> instanceStatus(InstanceId instanceId) {
            return new TestCause("instanceStatus not used").promise();
        }
    }

    record TestCause(String message) implements Cause {}

    private static BootstrapState stateWithSshKey(long sshKeyId) {
        var phases = new EnumMap<BootstrapPhase, PhaseStatus>(BootstrapPhase.class);
        for (var phase : BootstrapPhase.values()) {phases.put(phase, PhaseStatus.COMPLETED);}
        var resources = List.<CreatedResource>of(SshKeyResource.sshKeyResource("hetzner", sshKeyId, "aether-bootstrap-abc12345"));
        return BootstrapState.bootstrapState(CLUSTER_NAME,
                                             "hash-1",
                                             "2026-05-01T00:00:00Z",
                                             phases,
                                             resources,
                                             List.of(),
                                             List.of());
    }

    private static Fn1<Result<HetznerClient>, String> recordingHetznerResolver(List<Long> deleteCalls) {
        return providerName -> Result.success(new RecordingHetznerClient(deleteCalls));
    }

    private static Fn1<Result<HetznerClient>, String> failingHetznerResolver() {
        return _ -> new TestCause("hetzner client unavailable").result();
    }

    private static BootstrapState stateWithFirewall(long firewallId) {
        return stateWithFirewall(Long.toString(firewallId));
    }

    private static BootstrapState stateWithFirewall(String firewallId) {
        var phases = new EnumMap<BootstrapPhase, PhaseStatus>(BootstrapPhase.class);
        for (var phase : BootstrapPhase.values()) {phases.put(phase, PhaseStatus.COMPLETED);}
        var resources = List.<CreatedResource>of(CreatedResource.CloudFirewall.cloudFirewall("hetzner",
                                                                                             FirewallId.firewallId(firewallId).unwrap(),
                                                                                             sourceNameOrDefault("hetzner-eu"),
                                                                                             firewallName("aether-test-hetzner-eu").unwrap()));
        return BootstrapState.bootstrapState(CLUSTER_NAME,
                                             "hash-1",
                                             "2026-05-01T00:00:00Z",
                                             phases,
                                             resources,
                                             List.of(),
                                             List.of());
    }

    /// The cleanup arm for firewalls used to PRINT and return success without issuing any call, so
    /// destroy reported "Cleaned up ..." while the firewall kept existing and kept costing money.
    /// Asserting success is therefore NOT enough — the delete call itself has to be observed.
    @Test
    void cleanup_deletesFirewall_whenCloudFirewallResourcePresent() {
        var firewallDeletes = new ArrayList<Long>();
        var state = stateWithFirewall(77L);

        var result = BootstrapCleanup.cleanup(state,
                                              providerName -> new TestCause("unused").result(),
                                              providerName -> Result.success(new FirewallRecordingHetznerClient(firewallDeletes)));

        assertTrue(result.isSuccess(), () -> "cleanup must succeed: " + result);
        assertEquals(List.of(77L), firewallDeletes,
                     "Hetzner deleteFirewall must be called with the recorded firewall id");
    }

    /// The live 2026-08-05 run failed here: `deleteServer` returns before Hetzner finishes detaching
    /// the server, so the immediately-following firewall delete got `422 resource_in_use` and destroy
    /// reported failure for a firewall that was seconds away from deletable. Retrying is the fix; a
    /// single attempt is what shipped.
    @Test
    void cleanup_retriesFirewallDelete_whenStillAttachedFromAsyncServerDeletion() {
        var firewallDeletes = new ArrayList<Long>();
        var state = stateWithFirewall(77L);
        var client = new FirewallRecordingHetznerClient(firewallDeletes, 2);

        var result = BootstrapCleanup.cleanupWith(state,
                                                  BootstrapCleanup.CleanupResolvers.cleanupResolvers()
                                                          .withCloudComputeFallback(_ -> new TestCause("unused").result())
                                                          .withHetznerClientFallback(_ -> Result.success(client))
                                                          .withSleeper(_ -> {}));

        assertTrue(result.isSuccess(), () -> "delete must succeed once the servers finish detaching: " + result);
        assertEquals(List.of(77L, 77L, 77L), firewallDeletes,
                     "two in-use refusals then success = three attempts");
    }


    /// T3 — Hetzner cleanup must REFUSE a non-numeric recorded id rather than guess one. A recorded
    /// `hetzner` firewall id is expected to be Hetzner's own numeric id; a non-numeric value here means
    /// the state file was written by something else, and deleting a guessed numeric id would destroy
    /// someone else's firewall. The delete call must never even reach the client.
    @Test
    void cleanup_refusesNonNumericHetznerFirewallId_neverCallsDelete() {
        var firewallDeletes = new ArrayList<Long>();
        var state = stateWithFirewall("sg-0abc123def");

        var result = BootstrapCleanup.cleanup(state,
                                              providerName -> new TestCause("unused").result(),
                                              providerName -> Result.success(new FirewallRecordingHetznerClient(firewallDeletes)));

        assertTrue(result.isFailure(), "a non-numeric hetzner firewall id must not be treated as deletable");
        result.onFailure(cause -> assertTrue(cause.message().contains("not numeric")
                                             && cause.message().contains("sg-0abc123def")
                                             && cause.message().contains("Refusing to guess"),
                                             () -> "failure must surface FirewallId.NotNumeric's refusal, was: " + cause.message()));
        assertTrue(firewallDeletes.isEmpty(),
                   "deleteFirewall must NOT be called for a non-numeric id — guessing an id could destroy someone else's firewall");
    }

    @Test
    void cleanup_surfacesFailure_whenFirewallDeleteFails() {
        var state = stateWithFirewall(77L);

        var result = BootstrapCleanup.cleanup(state,
                                              providerName -> new TestCause("unused").result(),
                                              failingHetznerResolver());

        assertTrue(result.isFailure(), "a firewall that could not be deleted must NOT report success");
    }

    /// Scope guard: firewall teardown must issue exactly the id-scoped delete and nothing else. A
    /// label sweep here is what destroyed the standing `test-pg` VM on 2026-08-03 (#572) — every
    /// other client call throws.
    static final class FirewallRecordingHetznerClient implements HetznerClient {
        private final List<Long> deleteCalls;
        private int remainingRefusals;

        FirewallRecordingHetznerClient(List<Long> deleteCalls) {
            this(deleteCalls, 0);
        }

        /// `refusals` replies carry Hetzner's real `422 resource_in_use` before the delete succeeds —
        /// the async-detach window observed on the live 2026-08-05 run.
        FirewallRecordingHetznerClient(List<Long> deleteCalls, int refusals) {
            this.deleteCalls = deleteCalls;
            this.remainingRefusals = refusals;
        }

        @Override public Promise<Unit> deleteFirewall(long firewallId) {
            deleteCalls.add(firewallId);
            if (remainingRefusals > 0) {
                remainingRefusals--;
                return new HetznerError.ApiError(422, "resource_in_use",
                                                 "firewall with ID " + firewallId + " is still in use").promise();
            }

            return Promise.success(Unit.unit());
        }

        @Override public Promise<Unit> deleteSshKey(long sshKeyId) {throw fail("deleteSshKey");}
        @Override public Promise<SshKey> createSshKey(SshKey.CreateSshKeyRequest request) {throw fail("createSshKey");}
        @Override public Promise<List<SshKey>> listSshKeys() {throw fail("listSshKeys");}
        @Override public Promise<Server> createServer(CreateServerRequest request) {throw fail("createServer");}
        @Override public Promise<Unit> deleteServer(long serverId) {throw fail("deleteServer");}
        @Override public Promise<Server> getServer(long serverId) {throw fail("getServer");}
        @Override public Promise<List<Server>> listServers() {throw fail("listServers");}
        @Override public Promise<List<Server>> listServers(String labelSelector) {throw fail("listServers(label)");}
        @Override public Promise<Unit> updateServerLabels(long serverId, Map<String, String> labels) {throw fail("updateServerLabels");}
        @Override public Promise<Unit> rebootServer(long serverId) {throw fail("rebootServer");}
        @Override public Promise<List<Network>> listNetworks() {throw fail("listNetworks");}
        @Override public Promise<Network> getNetwork(long networkId) {throw fail("getNetwork");}
        @Override public Promise<List<Firewall>> listFirewalls() {throw fail("listFirewalls");}
        @Override public Promise<List<Firewall>> listFirewalls(String labelSelector) {throw fail("listFirewalls(selector)");}
        @Override public Promise<Firewall> createFirewall(Firewall.CreateFirewallRequest request) {throw fail("createFirewall");}
        @Override public Promise<Unit> setFirewallRules(long firewallId, List<Firewall.Rule> rules) {throw fail("setFirewallRules");}
        @Override public Promise<Unit> removeFirewallFromResources(long firewallId, long serverId) {throw fail("removeFirewallFromResources");}
        @Override public Promise<Unit> applyFirewall(long firewallId, long serverId) {throw fail("applyFirewall");}
        @Override public Promise<LoadBalancer> createLoadBalancer(LoadBalancer.CreateLoadBalancerRequest request) {throw fail("createLoadBalancer");}
        @Override public Promise<Unit> deleteLoadBalancer(long loadBalancerId) {throw fail("deleteLoadBalancer");}
        @Override public Promise<List<LoadBalancer>> listLoadBalancers() {throw fail("listLoadBalancers");}
        @Override public Promise<Unit> addTarget(long loadBalancerId, long serverId) {throw fail("addTarget");}
        @Override public Promise<Unit> removeTarget(long loadBalancerId, long serverId) {throw fail("removeTarget");}
        @Override public Promise<Unit> addIpTarget(long loadBalancerId, String ip) {throw fail("addIpTarget");}
        @Override public Promise<Unit> removeIpTarget(long loadBalancerId, String ip) {throw fail("removeIpTarget");}
        @Override public Promise<LoadBalancer> getLoadBalancer(long loadBalancerId) {throw fail("getLoadBalancer");}
        @Override public Promise<List<FloatingIp>> listFloatingIps() {throw fail("listFloatingIps");}
        @Override public Promise<FloatingIp> getFloatingIp(long floatingIpId) {throw fail("getFloatingIp");}
        @Override public Promise<Unit> assignFloatingIp(long floatingIpId, long serverId) {throw fail("assignFloatingIp");}

        private static AssertionError fail(String name) {
            return new AssertionError("Test stub: firewall cleanup must not call '" + name + "'");
        }
    }

    @Test
    void cleanup_deletesSshKey_whenSshKeyResourcePresent() {
        var deleteCalls = new ArrayList<Long>();
        var state = stateWithSshKey(42L);

        var result = BootstrapCleanup.cleanup(state,
                                              providerName -> new TestCause("unused").result(),
                                              recordingHetznerResolver(deleteCalls));

        assertTrue(result.isSuccess(), () -> "cleanup must succeed: " + result);
        assertEquals(List.of(42L), deleteCalls,
                     "Hetzner deleteSshKey must be called with the recorded ssh key id");
    }

    @Test
    void cleanup_failsCleanly_whenHetznerClientUnavailable() {
        var state = stateWithSshKey(42L);

        var result = BootstrapCleanup.cleanup(state,
                                              providerName -> new TestCause("unused").result(),
                                              failingHetznerResolver());

        assertTrue(result.isFailure(), "cleanup must surface key-deletion failure");
    }

    @Test
    void cleanup_doesNotDeleteSshKey_whenNotInResourceList() {
        // Pre-existing keys are NOT recorded as CreatedResource — verify cleanup
        // doesn't call deleteSshKey when no SshKeyResource is in state.
        var deleteCalls = new ArrayList<Long>();
        var state = stateWithVm("hetzner", "vm-1");

        var resolverCalls = new ArrayList<String>();
        var terminateCalls = new ArrayList<String>();
        var result = BootstrapCleanup.cleanup(state,
                                              recordingResolver(resolverCalls, terminateCalls),
                                              recordingHetznerResolver(deleteCalls));

        assertTrue(result.isSuccess());
        assertTrue(deleteCalls.isEmpty(),
                   "deleteSshKey must NOT be called when state contains no SshKeyResource (pre-existing keys unowned)");
    }

    private static final String NON_DEFAULT_TOKEN_ENV = "HCLOUD_TOKEN_PROD";
    private static final String PROD_TOKEN_VALUE = "prod-token-value";

    private static BootstrapState stateWithVmSshKeyAndHandle(String sourceName, SourceCleanupHandle handle) {
        var phases = new EnumMap<BootstrapPhase, PhaseStatus>(BootstrapPhase.class);
        for (var phase : BootstrapPhase.values()) {phases.put(phase, PhaseStatus.COMPLETED);}
        var resources = List.<CreatedResource>of(new ProvisionedVm("hetzner", "vm-1", sourceName, "core"),
                                                 SshKeyResource.sshKeyResource("hetzner", 42L, "aether-bootstrap-abc12345"));
        return BootstrapState.bootstrapState(CLUSTER_NAME,
                                             "hash-1",
                                             "2026-05-01T00:00:00Z",
                                             phases,
                                             resources,
                                             List.of(),
                                             List.of(),
                                             "",
                                             Map.of(sourceName, handle));
    }

    private static Fn1<Result<ComputeProvider>, SourceCleanupHandle> recordingHandleComputeResolver(List<SourceCleanupHandle> handleCalls,
                                                                                                    List<String> terminateCalls) {
        return handle -> recordHandleResolve(handle, handleCalls, terminateCalls);
    }

    private static Result<ComputeProvider> recordHandleResolve(SourceCleanupHandle handle,
                                                               List<SourceCleanupHandle> handleCalls,
                                                               List<String> terminateCalls) {
        handleCalls.add(handle);
        return Result.success(recordingCompute(terminateCalls));
    }

    private static Fn1<String, String> recordingGetenv(Map<String, String> env, List<String> reads) {
        return name -> readEnv(name, env, reads);
    }

    private static String readEnv(String name, Map<String, String> env, List<String> reads) {
        reads.add(name);
        return env.get(name);
    }

    private static Fn1<HetznerClient, String> recordingClientFactory(List<String> tokens, List<Long> deleteCalls) {
        return token -> recordClientFactory(token, tokens, deleteCalls);
    }

    private static HetznerClient recordClientFactory(String token, List<String> tokens, List<Long> deleteCalls) {
        tokens.add(token);
        return new RecordingHetznerClient(deleteCalls);
    }

    /// RFC-0016 W4 (#439) — the money path. A timeout-triggered cleanup of a cluster provisioned with a
    /// token supplied under a NON-default env-var name (`HCLOUD_TOKEN_PROD`) must reap BOTH the VM and its
    /// ssh key via that name — never raw `HCLOUD_TOKEN`. The getenv stub deliberately omits `HCLOUD_TOKEN`,
    /// so any read of the raw default would yield a blank token and fail the reap.
    @Test
    void cleanup_reapsVmAndSshKey_viaHandleEnvVarName_notRawHcloudToken() {
        var handleCalls = new ArrayList<SourceCleanupHandle>();
        var terminateCalls = new ArrayList<String>();
        var deleteCalls = new ArrayList<Long>();
        var factoryTokens = new ArrayList<String>();
        var envReads = new ArrayList<String>();

        var handle = SourceCleanupHandle.sourceCleanupHandle("hetzner",
                                                             Option.some("eu-central"),
                                                             Map.of("api_token", NON_DEFAULT_TOKEN_ENV));
        var state = stateWithVmSshKeyAndHandle("core-source", handle);
        var env = Map.of(NON_DEFAULT_TOKEN_ENV, PROD_TOKEN_VALUE);

        var result = BootstrapCleanup.cleanup(state,
                                              recordingHandleComputeResolver(handleCalls, terminateCalls),
                                              recordingGetenv(env, envReads),
                                              recordingClientFactory(factoryTokens, deleteCalls));

        assertTrue(result.isSuccess(), () -> "cleanup must succeed via the persisted-handle credential: " + result);
        assertEquals(List.of("vm-1"), terminateCalls,
                     "VM must be reaped through the handle-derived compute provider");
        assertEquals(List.of(handle), handleCalls,
                     "VM reap must resolve compute from the persisted handle (which names HCLOUD_TOKEN_PROD)");
        assertEquals(List.of(42L), deleteCalls,
                     "SSH key must be reaped");
        assertEquals(List.of(PROD_TOKEN_VALUE), factoryTokens,
                     "SSH-key HetznerClient must be built from the HCLOUD_TOKEN_PROD-derived token, not raw HCLOUD_TOKEN");
        assertTrue(envReads.contains(NON_DEFAULT_TOKEN_ENV),
                   "SSH-key cleanup must read the handle's env-var NAME (HCLOUD_TOKEN_PROD)");
        assertFalse(envReads.contains("HCLOUD_TOKEN"),
                    "SSH-key cleanup must NOT read the raw default HCLOUD_TOKEN when a handle exists");
    }

    /// No persisted handle (pre-W4 fallback path) — the SSH key is still reaped, loudly, via the injected
    /// default resolver. Guards that W4 kept the raw-env last resort rather than hard-failing.
    @Test
    void cleanup_deletesSshKey_viaLoudFallback_whenNoHandlePresent() {
        var deleteCalls = new ArrayList<Long>();
        var state = stateWithSshKey(7L);

        var result = BootstrapCleanup.cleanup(state,
                                              providerName -> new TestCause("unused").result(),
                                              recordingHetznerResolver(deleteCalls));

        assertTrue(result.isSuccess(), () -> "no-handle fallback must still reap the ssh key: " + result);
        assertEquals(List.of(7L), deleteCalls,
                     "no-handle fallback must reap the ssh key via the injected default resolver");
    }

    // --- #481: cluster-scoped ssh-key sweep ---

    private static BootstrapState stateWithHetznerHandle() {
        var phases = new EnumMap<BootstrapPhase, PhaseStatus>(BootstrapPhase.class);
        for (var phase : BootstrapPhase.values()) {phases.put(phase, PhaseStatus.COMPLETED);}
        var handle = SourceCleanupHandle.sourceCleanupHandle("hetzner",
                                                             Option.some("eu-central"),
                                                             Map.of("api_token", NON_DEFAULT_TOKEN_ENV));
        return BootstrapState.bootstrapState(CLUSTER_NAME,
                                             "hash-1",
                                             "2026-05-01T00:00:00Z",
                                             phases,
                                             List.of(),
                                             List.of(),
                                             List.of(),
                                             "",
                                             Map.of("core-source", handle));
    }

    private static Fn1<HetznerClient, String> sweepingClientFactory(HetznerClient client, List<String> tokens) {
        return token -> recordSweepFactory(token, client, tokens);
    }

    private static HetznerClient recordSweepFactory(String token, HetznerClient client, List<String> tokens) {
        tokens.add(token);
        return client;
    }

    /// #481 — the sweep deletes ONLY keys scoped to THIS cluster by the delimiter boundary
    /// `aether-bootstrap-<cluster>-`. Run with clusterName `prod`, so it must reap `aether-bootstrap-prod-op`
    /// yet leave `aether-bootstrap-production-op` (delimiter boundary), `aether-bootstrap-other-op` (different
    /// cluster), and `someones-laptop` (not a bootstrap key) untouched. The HetznerClient is resolved
    /// handle-first (via HCLOUD_TOKEN_PROD from the persisted handle), never raw HCLOUD_TOKEN.
    @Test
    void destroy_deletesClusterScopedSshKeys() {
        var deleteCalls = new ArrayList<Long>();
        var envReads = new ArrayList<String>();
        var factoryTokens = new ArrayList<String>();
        var keys = List.of(new SshKey(42L, "aether-bootstrap-prod-op", "fp-42", "pk-42"),
                           new SshKey(99L, "aether-bootstrap-production-op", "fp-99", "pk-99"),
                           new SshKey(43L, "aether-bootstrap-other-op", "fp-43", "pk-43"),
                           new SshKey(44L, "someones-laptop", "fp-44", "pk-44"));
        var client = new SweepingHetznerClient(keys, deleteCalls, Set.of());
        var state = stateWithHetznerHandle();
        var env = Map.of(NON_DEFAULT_TOKEN_ENV, PROD_TOKEN_VALUE);

        var result = BootstrapCleanup.sweepClusterSshKeys(state,
                                                          clusterName("prod").unwrap(),
                                                          recordingGetenv(env, envReads),
                                                          sweepingClientFactory(client, factoryTokens));

        assertTrue(result.isSuccess(), () -> "sweep must succeed: " + result);
        assertEquals(List.of(42L), deleteCalls,
                     "only the cluster-scoped key (aether-bootstrap-prod-op) may be deleted; 'production' must survive the delimiter boundary");
        assertEquals(List.of(PROD_TOKEN_VALUE), factoryTokens,
                     "sweep HetznerClient must be built from the HCLOUD_TOKEN_PROD-derived token, not raw HCLOUD_TOKEN");
        assertFalse(envReads.contains("HCLOUD_TOKEN"),
                    "sweep must resolve the client handle-first and never read raw HCLOUD_TOKEN");
    }

    /// #481 — an already-gone key (Hetzner 404 / `not_found`) is tolerated as success, so a recorded key that
    /// the state-based cleanup already deleted does not abort the sweep or fail the destroy.
    @Test
    void destroy_sshKeyAlreadyGone_toleratedNoFailure() {
        var deleteCalls = new ArrayList<Long>();
        var keys = List.of(new SshKey(42L, "aether-bootstrap-prod-op", "fp-42", "pk-42"));
        var client = new SweepingHetznerClient(keys, deleteCalls, Set.of(42L));
        var state = stateWithHetznerHandle();
        var env = Map.of(NON_DEFAULT_TOKEN_ENV, PROD_TOKEN_VALUE);

        var result = BootstrapCleanup.sweepClusterSshKeys(state,
                                                          clusterName("prod").unwrap(),
                                                          recordingGetenv(env, new ArrayList<>()),
                                                          sweepingClientFactory(client, new ArrayList<>()));

        assertTrue(result.isSuccess(), () -> "an already-gone (404/not_found) key must be tolerated: " + result);
        assertEquals(List.of(42L), deleteCalls, "the sweep must still attempt the delete before tolerating 404");
    }

    // --- RFC-0017 stage 6 / C3: cluster-scoped VM sweep ---

    private static Server vmServer(long id, String name) {
        return new Server(id, name, "running", null, null, null, null, Map.of("aether-cluster", "prod"));
    }

    /// The selector is built from the cluster name — scoped BY CONSTRUCTION. The sweep deletes
    /// every VM the provider returns for it (the provider already filtered; a bare selector can
    /// never be issued), resolves credentials handle-first, and prints an inventory first (the
    /// #572 lesson: what the sweep sees is what you can LOSE).
    @Test
    void destroy_vmSweep_deletesClusterLabelledVms_viaScopedSelector() {
        var deleteCalls = new ArrayList<Long>();
        var selectors = new ArrayList<String>();
        var envReads = new ArrayList<String>();
        var factoryTokens = new ArrayList<String>();
        var servers = List.of(vmServer(101L, "prod-worker-r123-0"), vmServer(102L, "prod-worker-r123-1"));
        var client = new VmSweepingHetznerClient(servers, deleteCalls, Set.of(), selectors);
        var state = stateWithHetznerHandle();
        var env = Map.of(NON_DEFAULT_TOKEN_ENV, PROD_TOKEN_VALUE);

        var result = BootstrapCleanup.sweepClusterVms(state,
                                                      clusterName("prod").unwrap(),
                                                      recordingGetenv(env, envReads),
                                                      sweepingClientFactory(client, factoryTokens));

        assertTrue(result.isSuccess(), () -> "VM sweep must succeed: " + result);
        assertEquals(List.of("aether-cluster=prod"), selectors,
                     "the sweep must list by the cluster-scoped label selector, never a bare listing");
        assertEquals(List.of(101L, 102L), deleteCalls, "every cluster-labelled VM must be swept");
        assertEquals(List.of(PROD_TOKEN_VALUE), factoryTokens,
                     "sweep HetznerClient must be built handle-first, not from raw HCLOUD_TOKEN");
        assertFalse(envReads.contains("HCLOUD_TOKEN"),
                    "sweep must never read raw HCLOUD_TOKEN");
    }

    /// Protection lives in the TOOL, not the call site (#572: a bare reap deleted the standing
    /// test-pg VM). A destroy aimed at a protected cluster must FAIL — loudly, keeping the
    /// registry entry — and never reach the provider at all.
    @Test
    void destroy_vmSweep_protectedCluster_refusedBeforeAnyProviderCall() {
        var factoryTokens = new ArrayList<String>();
        var client = new VmSweepingHetznerClient(List.of(), new ArrayList<>(), Set.of(), new ArrayList<>());
        var state = stateWithHetznerHandle();
        var env = Map.of(NON_DEFAULT_TOKEN_ENV, PROD_TOKEN_VALUE);

        var result = BootstrapCleanup.sweepClusterVms(state,
                                                      clusterName("test-pg").unwrap(),
                                                      recordingGetenv(env, new ArrayList<>()),
                                                      sweepingClientFactory(client, factoryTokens));

        assertTrue(result.isFailure(), "sweeping a protected cluster must fail, not skip silently");
        result.onFailure(cause -> assertTrue(cause.message().contains("protected")
                                             && cause.message().contains("test-pg"),
                                             () -> "refusal must name the protection: " + cause.message()));
        assertTrue(factoryTokens.isEmpty(), "no client may even be constructed for a protected cluster");
    }

    /// A VM the state-based cleanup already terminated surfaces as 404 — tolerated, so destroy
    /// stays idempotent across the two passes.
    @Test
    void destroy_vmSweep_alreadyGoneVm_tolerated() {
        var deleteCalls = new ArrayList<Long>();
        var servers = List.of(vmServer(101L, "prod-core-0"), vmServer(102L, "prod-worker-r1-0"));
        var client = new VmSweepingHetznerClient(servers, deleteCalls, Set.of(101L), new ArrayList<>());
        var state = stateWithHetznerHandle();
        var env = Map.of(NON_DEFAULT_TOKEN_ENV, PROD_TOKEN_VALUE);

        var result = BootstrapCleanup.sweepClusterVms(state,
                                                      clusterName("prod").unwrap(),
                                                      recordingGetenv(env, new ArrayList<>()),
                                                      sweepingClientFactory(client, new ArrayList<>()));

        assertTrue(result.isSuccess(), () -> "an already-gone VM must be tolerated: " + result);
        assertEquals(List.of(101L, 102L), deleteCalls, "both deletes must still be attempted");
    }

    /// Supersedes `destroy_vmSweep_blankClusterName_skipsWithoutProviderCalls`. A blank cluster name
    /// cannot scope a selector, so the sweep used to check for one and skip. `ClusterName` removes the
    /// input instead: the sweep's parameter cannot hold a blank, so the unscoped call it guarded
    /// against is now a compile error and the guard is gone. What remains worth pinning is the
    /// rejection itself — without it the removal would be a downgrade, not an upgrade.
    @Test
    void vmSweep_cannotBeCalledWithABlankClusterName_becauseNoSuchNameParses() {
        clusterName("").onSuccess(name -> fail("a blank cluster name must not parse, produced " + name));
        clusterName(" ").onSuccess(name -> fail("a whitespace cluster name must not parse, produced " + name));
    }

    // --- #521: a persisted handle that names NO credential env var ---

    private static BootstrapCleanup.CleanupResolvers resolversFor(List<String> fallbackProviders,
                                                                   List<String> terminateCalls,
                                                                   List<Long> deleteCalls,
                                                                   Map<String, String> env,
                                                                   List<String> envReads) {
        return BootstrapCleanup.CleanupResolvers.cleanupResolvers()
                                                .withCloudComputeFallback(provider -> recordFallbackCompute(provider,
                                                                                                             fallbackProviders,
                                                                                                             terminateCalls))
                                                .withHetznerClientFallback(provider -> recordFallbackClient(provider,
                                                                                                             fallbackProviders,
                                                                                                             deleteCalls))
                                                .withHandleComputeResolver(_ -> new TestCause("handle resolver must not be used when the handle names no credential").result())
                                                .withGetenv(recordingGetenv(env, envReads));
    }

    private static Result<ComputeProvider> recordFallbackCompute(String providerName,
                                                                  List<String> fallbackProviders,
                                                                  List<String> terminateCalls) {
        fallbackProviders.add(providerName);
        return Result.success(recordingCompute(terminateCalls));
    }

    private static Result<HetznerClient> recordFallbackClient(String providerName,
                                                               List<String> fallbackProviders,
                                                               List<Long> deleteCalls) {
        fallbackProviders.add(providerName);
        return Result.success(new RecordingHetznerClient(deleteCalls));
    }

    /// #521 — the incident shape. Bootstrap stamped a handle (provider + region) but mined NO credential
    /// env-var name, so `credentialEnvVars` is empty. The handle-derived config would carry no credentials
    /// at all and the provider factory would reject it, stranding five paid VMs. An unmapped handle
    /// expresses no credential intent, so cleanup must demote to the LOUD raw-env last resort and actually
    /// reap — both the VM and the ssh key.
    @Test
    void cleanup_reapsVmAndSshKey_viaLoudFallback_whenHandleNamesNoCredentialEnvVar() {
        var fallbackProviders = new ArrayList<String>();
        var terminateCalls = new ArrayList<String>();
        var deleteCalls = new ArrayList<Long>();

        var unmappedHandle = SourceCleanupHandle.sourceCleanupHandle("hetzner", Option.some("fsn1"), Map.of());
        var state = stateWithVmSshKeyAndHandle("core-source", unmappedHandle);

        var result = BootstrapCleanup.cleanupWith(state,
                                                  resolversFor(fallbackProviders,
                                                               terminateCalls,
                                                               deleteCalls,
                                                               Map.of(),
                                                               new ArrayList<>()));

        assertTrue(result.isSuccess(),
                   () -> "an unmapped handle must NOT strand resources — cleanup must fall back and reap: " + result);
        assertEquals(List.of("vm-1"), terminateCalls,
                     "the VM must be terminated through the demoted raw-env fallback");
        assertEquals(List.of(42L), deleteCalls,
                     "the ssh key must be reaped through the demoted raw-env fallback");
        assertEquals(List.of("hetzner", "hetzner"), fallbackProviders,
                     "both reaps must route through the raw-env fallback with the provider name");
    }

    /// #521 must not weaken #439: a handle that DOES name an env var stays authoritative. When that name is
    /// unset, cleanup fails loudly rather than silently retrying with the raw default, which could reap
    /// against a DIFFERENT account's token.
    @Test
    void cleanup_failsLoudly_whenHandleNamesEnvVarThatIsUnset() {
        var envReads = new ArrayList<String>();
        var handle = SourceCleanupHandle.sourceCleanupHandle("hetzner",
                                                             Option.some("fsn1"),
                                                             Map.of("api_token", NON_DEFAULT_TOKEN_ENV));
        var state = stateWithSshKeyAndHandle(handle);

        var result = BootstrapCleanup.cleanupWith(state,
                                                  BootstrapCleanup.CleanupResolvers.cleanupResolvers()
                                                                                   .withHetznerClientFallback(_ -> new TestCause("raw-env fallback must NOT be reached when the handle names an env var").result())
                                                                                   .withGetenv(recordingGetenv(Map.of(), envReads)));

        assertTrue(result.isFailure(),
                   "a handle-named env var that is unset must fail loudly, not silently reap with another token");
        assertTrue(envReads.contains(NON_DEFAULT_TOKEN_ENV),
                   "the handle's env-var NAME must still be the one consulted");
    }

    private static BootstrapState stateWithSshKeyAndHandle(SourceCleanupHandle handle) {
        var phases = new EnumMap<BootstrapPhase, PhaseStatus>(BootstrapPhase.class);
        for (var phase : BootstrapPhase.values()) {phases.put(phase, PhaseStatus.COMPLETED);}
        var resources = List.<CreatedResource>of(SshKeyResource.sshKeyResource("hetzner", 42L, "aether-bootstrap-abc12345"));
        return BootstrapState.bootstrapState(CLUSTER_NAME,
                                             "hash-1",
                                             "2026-05-01T00:00:00Z",
                                             phases,
                                             resources,
                                             List.of(),
                                             List.of(),
                                             "",
                                             Map.of("core-source", handle));
    }

    /// #521 — the sweep resolves its client from the same handle. An unmapped handle must not abort the
    /// sweep (which would leave orphan cluster-scoped keys on the account); it demotes to raw-env.
    @Test
    void sweep_deletesClusterScopedKeys_viaLoudFallback_whenHandleNamesNoCredentialEnvVar() {
        var fallbackProviders = new ArrayList<String>();
        var deleteCalls = new ArrayList<Long>();
        var keys = List.of(new SshKey(42L, "aether-bootstrap-prod-op", "fp-42", "pk-42"));
        var unmappedHandle = SourceCleanupHandle.sourceCleanupHandle("hetzner", Option.some("fsn1"), Map.of());
        var state = stateWithSshKeyAndHandle(unmappedHandle);
        var sweepingClient = new SweepingHetznerClient(keys, deleteCalls, Set.of());

        var result = BootstrapCleanup.sweepClusterSshKeys(state,
                                                          clusterName("prod").unwrap(),
                                                          BootstrapCleanup.CleanupResolvers.cleanupResolvers()
                                                                                           .withHetznerClientFallback(provider -> recordSweepFallback(provider,
                                                                                                                                                       fallbackProviders,
                                                                                                                                                       sweepingClient)));

        assertTrue(result.isSuccess(), () -> "the sweep must fall back rather than abort: " + result);
        assertEquals(List.of(42L), deleteCalls, "the cluster-scoped key must still be swept");
        assertEquals(List.of("hetzner"), fallbackProviders,
                     "the sweep client must come from the demoted raw-env fallback");
    }

    private static Result<HetznerClient> recordSweepFallback(String providerName,
                                                             List<String> fallbackProviders,
                                                             HetznerClient client) {
        fallbackProviders.add(providerName);
        return Result.success(client);
    }

    /// Stub for the #481 sweep: records `listSshKeys` results and `deleteSshKey` calls; ids in `goneIds`
    /// surface as a Hetzner 404 `not_found` `ApiError` (already-gone). All other operations throw.
    /// VM-sweep stub: only the label-scoped listing and server deletion are legal; everything else
    /// is a stub failure so the sweep cannot silently widen its surface.
    record VmSweepingHetznerClient(List<Server> servers,
                                   List<Long> deleteCalls,
                                   Set<Long> goneIds,
                                   List<String> selectors) implements HetznerClient {
        @Override public Promise<List<Server>> listServers(String labelSelector) {
            selectors.add(labelSelector);
            return Promise.success(servers);
        }

        @Override public Promise<Unit> deleteServer(long serverId) {
            deleteCalls.add(serverId);
            if (goneIds.contains(serverId)) {
                return new HetznerError.ApiError(404, "not_found", "server not found").promise();
            }
            return Promise.success(Unit.unit());
        }

        @Override public Promise<List<SshKey>> listSshKeys() {throw failVm("listSshKeys");}
        @Override public Promise<Unit> deleteSshKey(long sshKeyId) {throw failVm("deleteSshKey");}
        @Override public Promise<SshKey> createSshKey(SshKey.CreateSshKeyRequest request) {throw failVm("createSshKey");}
        @Override public Promise<Server> createServer(CreateServerRequest request) {throw failVm("createServer");}
        @Override public Promise<Server> getServer(long serverId) {throw failVm("getServer");}
        @Override public Promise<List<Server>> listServers() {throw failVm("listServers (bare — the sweep must always scope)");}
        @Override public Promise<Unit> updateServerLabels(long serverId, Map<String, String> labels) {throw failVm("updateServerLabels");}
        @Override public Promise<Unit> rebootServer(long serverId) {throw failVm("rebootServer");}
        @Override public Promise<List<Network>> listNetworks() {throw failVm("listNetworks");}
        @Override public Promise<Network> getNetwork(long networkId) {throw failVm("getNetwork");}
        @Override public Promise<List<Firewall>> listFirewalls() {throw failVm("listFirewalls");}
        @Override public Promise<Unit> applyFirewall(long firewallId, long serverId) {throw failVm("applyFirewall");}
        @Override public Promise<List<Firewall>> listFirewalls(String labelSelector) {throw failVm("listFirewalls(selector)");}
        @Override public Promise<Firewall> createFirewall(Firewall.CreateFirewallRequest request) {throw failVm("createFirewall");}
        @Override public Promise<Unit> setFirewallRules(long firewallId, List<Firewall.Rule> rules) {throw failVm("setFirewallRules");}
        @Override public Promise<Unit> deleteFirewall(long firewallId) {throw failVm("deleteFirewall");}
        @Override public Promise<Unit> removeFirewallFromResources(long firewallId, long serverId) {throw failVm("removeFirewallFromResources");}
        @Override public Promise<LoadBalancer> createLoadBalancer(LoadBalancer.CreateLoadBalancerRequest request) {throw failVm("createLoadBalancer");}
        @Override public Promise<Unit> deleteLoadBalancer(long loadBalancerId) {throw failVm("deleteLoadBalancer");}
        @Override public Promise<List<LoadBalancer>> listLoadBalancers() {throw failVm("listLoadBalancers");}
        @Override public Promise<Unit> addTarget(long loadBalancerId, long serverId) {throw failVm("addTarget");}
        @Override public Promise<Unit> removeTarget(long loadBalancerId, long serverId) {throw failVm("removeTarget");}
        @Override public Promise<Unit> addIpTarget(long loadBalancerId, String ip) {throw failVm("addIpTarget");}
        @Override public Promise<Unit> removeIpTarget(long loadBalancerId, String ip) {throw failVm("removeIpTarget");}
        @Override public Promise<LoadBalancer> getLoadBalancer(long loadBalancerId) {throw failVm("getLoadBalancer");}
        @Override public Promise<List<FloatingIp>> listFloatingIps() {throw failVm("listFloatingIps");}
        @Override public Promise<FloatingIp> getFloatingIp(long floatingIpId) {throw failVm("getFloatingIp");}
        @Override public Promise<Unit> assignFloatingIp(long floatingIpId, long serverId) {throw failVm("assignFloatingIp");}

        private static AssertionError failVm(String name) {
            return new AssertionError("Test stub: '" + name + "' must not be called by VM sweep");
        }
    }

    record SweepingHetznerClient(List<SshKey> keys, List<Long> deleteCalls, Set<Long> goneIds) implements HetznerClient {
        @Override public Promise<List<SshKey>> listSshKeys() {
            return Promise.success(keys);
        }

        @Override public Promise<Unit> deleteSshKey(long sshKeyId) {
            deleteCalls.add(sshKeyId);
            if (goneIds.contains(sshKeyId)) {
                return new HetznerError.ApiError(404, "not_found", "ssh key not found").promise();
            }
            return Promise.success(Unit.unit());
        }

        @Override public Promise<SshKey> createSshKey(SshKey.CreateSshKeyRequest request) {throw fail("createSshKey");}
        @Override public Promise<Server> createServer(CreateServerRequest request) {throw fail("createServer");}
        @Override public Promise<Unit> deleteServer(long serverId) {throw fail("deleteServer");}
        @Override public Promise<Server> getServer(long serverId) {throw fail("getServer");}
        @Override public Promise<List<Server>> listServers() {throw fail("listServers");}
        @Override public Promise<List<Server>> listServers(String labelSelector) {throw fail("listServers(label)");}
        @Override public Promise<Unit> updateServerLabels(long serverId, Map<String, String> labels) {throw fail("updateServerLabels");}
        @Override public Promise<Unit> rebootServer(long serverId) {throw fail("rebootServer");}
        @Override public Promise<List<Network>> listNetworks() {throw fail("listNetworks");}
        @Override public Promise<Network> getNetwork(long networkId) {throw fail("getNetwork");}
        @Override public Promise<List<Firewall>> listFirewalls() {throw fail("listFirewalls");}
        @Override public Promise<Unit> applyFirewall(long firewallId, long serverId) {throw fail("applyFirewall");}
        @Override public Promise<List<Firewall>> listFirewalls(String labelSelector) {throw fail("listFirewalls(selector)");}
        @Override public Promise<Firewall> createFirewall(Firewall.CreateFirewallRequest request) {throw fail("createFirewall");}
        @Override public Promise<Unit> setFirewallRules(long firewallId, List<Firewall.Rule> rules) {throw fail("setFirewallRules");}
        @Override public Promise<Unit> deleteFirewall(long firewallId) {throw fail("deleteFirewall");}
        @Override public Promise<Unit> removeFirewallFromResources(long firewallId, long serverId) {throw fail("removeFirewallFromResources");}
        @Override public Promise<LoadBalancer> createLoadBalancer(LoadBalancer.CreateLoadBalancerRequest request) {throw fail("createLoadBalancer");}
        @Override public Promise<Unit> deleteLoadBalancer(long loadBalancerId) {throw fail("deleteLoadBalancer");}
        @Override public Promise<List<LoadBalancer>> listLoadBalancers() {throw fail("listLoadBalancers");}
        @Override public Promise<Unit> addTarget(long loadBalancerId, long serverId) {throw fail("addTarget");}
        @Override public Promise<Unit> removeTarget(long loadBalancerId, long serverId) {throw fail("removeTarget");}
        @Override public Promise<Unit> addIpTarget(long loadBalancerId, String ip) {throw fail("addIpTarget");}
        @Override public Promise<Unit> removeIpTarget(long loadBalancerId, String ip) {throw fail("removeIpTarget");}
        @Override public Promise<LoadBalancer> getLoadBalancer(long loadBalancerId) {throw fail("getLoadBalancer");}
        @Override public Promise<List<FloatingIp>> listFloatingIps() {throw fail("listFloatingIps");}
        @Override public Promise<FloatingIp> getFloatingIp(long floatingIpId) {throw fail("getFloatingIp");}
        @Override public Promise<Unit> assignFloatingIp(long floatingIpId, long serverId) {throw fail("assignFloatingIp");}

        private static AssertionError fail(String name) {
            return new AssertionError("Test stub: '" + name + "' must not be called by SSH-key sweep");
        }
    }

    /// Stub used to assert that the only Hetzner call made by SSH-key cleanup
    /// is `deleteSshKey`. All other operations throw to surface scope creep.
    record RecordingHetznerClient(List<Long> deleteCalls) implements HetznerClient {
        @Override public Promise<Unit> deleteSshKey(long sshKeyId) {
            deleteCalls.add(sshKeyId);
            return Promise.success(Unit.unit());
        }

        @Override public Promise<SshKey> createSshKey(SshKey.CreateSshKeyRequest request) {throw fail("createSshKey");}
        @Override public Promise<List<SshKey>> listSshKeys() {throw fail("listSshKeys");}
        @Override public Promise<Server> createServer(CreateServerRequest request) {throw fail("createServer");}
        @Override public Promise<Unit> deleteServer(long serverId) {throw fail("deleteServer");}
        @Override public Promise<Server> getServer(long serverId) {throw fail("getServer");}
        @Override public Promise<List<Server>> listServers() {throw fail("listServers");}
        @Override public Promise<List<Server>> listServers(String labelSelector) {throw fail("listServers(label)");}
        @Override public Promise<Unit> updateServerLabels(long serverId, Map<String, String> labels) {throw fail("updateServerLabels");}
        @Override public Promise<Unit> rebootServer(long serverId) {throw fail("rebootServer");}
        @Override public Promise<List<Network>> listNetworks() {throw fail("listNetworks");}
        @Override public Promise<Network> getNetwork(long networkId) {throw fail("getNetwork");}
        @Override public Promise<List<Firewall>> listFirewalls() {throw fail("listFirewalls");}
        @Override public Promise<Unit> applyFirewall(long firewallId, long serverId) {throw fail("applyFirewall");}
        @Override public Promise<List<Firewall>> listFirewalls(String labelSelector) {throw fail("listFirewalls(selector)");}
        @Override public Promise<Firewall> createFirewall(Firewall.CreateFirewallRequest request) {throw fail("createFirewall");}
        @Override public Promise<Unit> setFirewallRules(long firewallId, List<Firewall.Rule> rules) {throw fail("setFirewallRules");}
        @Override public Promise<Unit> deleteFirewall(long firewallId) {throw fail("deleteFirewall");}
        @Override public Promise<Unit> removeFirewallFromResources(long firewallId, long serverId) {throw fail("removeFirewallFromResources");}
        @Override public Promise<LoadBalancer> createLoadBalancer(LoadBalancer.CreateLoadBalancerRequest request) {throw fail("createLoadBalancer");}
        @Override public Promise<Unit> deleteLoadBalancer(long loadBalancerId) {throw fail("deleteLoadBalancer");}
        @Override public Promise<List<LoadBalancer>> listLoadBalancers() {throw fail("listLoadBalancers");}
        @Override public Promise<Unit> addTarget(long loadBalancerId, long serverId) {throw fail("addTarget");}
        @Override public Promise<Unit> removeTarget(long loadBalancerId, long serverId) {throw fail("removeTarget");}
        @Override public Promise<Unit> addIpTarget(long loadBalancerId, String ip) {throw fail("addIpTarget");}
        @Override public Promise<Unit> removeIpTarget(long loadBalancerId, String ip) {throw fail("removeIpTarget");}
        @Override public Promise<LoadBalancer> getLoadBalancer(long loadBalancerId) {throw fail("getLoadBalancer");}
        @Override public Promise<List<FloatingIp>> listFloatingIps() {throw fail("listFloatingIps");}
        @Override public Promise<FloatingIp> getFloatingIp(long floatingIpId) {throw fail("getFloatingIp");}
        @Override public Promise<Unit> assignFloatingIp(long floatingIpId, long serverId) {throw fail("assignFloatingIp");}

        private static AssertionError fail(String name) {
            return new AssertionError("Test stub: '" + name + "' must not be called by SSH-key cleanup");
        }
    }
}
