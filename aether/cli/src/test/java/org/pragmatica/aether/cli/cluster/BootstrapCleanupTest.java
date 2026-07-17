// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.cli.cluster.BootstrapState.PhaseStatus;
import org.pragmatica.aether.cli.cluster.CreatedResource.ProvisionedVm;
import org.pragmatica.aether.cli.cluster.CreatedResource.SshKeyResource;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.ProvisionRequest;
import org.pragmatica.cloud.hetzner.HetznerClient;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BootstrapCleanupTest {

    private static final String CLUSTER_NAME = "test-cleanup-cluster";

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
