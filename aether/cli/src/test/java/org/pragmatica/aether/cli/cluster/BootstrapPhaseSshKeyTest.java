// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.cli.cluster.ClusterBootstrapOrchestrator.BootstrapContext;
import org.pragmatica.aether.config.cluster.CloudProviderName;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
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
import org.pragmatica.cloud.hetzner.HetznerClient;
import org.pragmatica.cloud.hetzner.api.Firewall;
import org.pragmatica.cloud.hetzner.api.FloatingIp;
import org.pragmatica.cloud.hetzner.api.LoadBalancer;
import org.pragmatica.cloud.hetzner.api.Network;
import org.pragmatica.cloud.hetzner.api.Server;
import org.pragmatica.cloud.hetzner.api.Server.CreateServerRequest;
import org.pragmatica.cloud.hetzner.api.SshKey;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.pragmatica.aether.environment.ClusterName.clusterName;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.pragmatica.aether.environment.SourceName.sourceNameOrDefault;

class BootstrapPhaseSshKeyTest {

    private static final String VALID_KEY =
        "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIBaa1234567890abcdefghijklmnopqrstuvwxyz/+ABCD operator@workstation";
    private static final String VALID_KEY_2 =
        "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQDBgzoY1yR1oSqeIElykMLu7j33ezBKBJpCiKGgkNE5SP4jfVg4F93OrVBY4ORc9oLliXmjaFjamYAppOpFktdy5oAqO3Q9SqgKi9oGSn+v5Hy675tRQwc+ZprL9cWtIFMOmFZF8w7JT8RFppsSM6hpezGFL0Ce3amudXXOIEC9y6wpQZZebE45EdtZfYg6BzQ1bc8clHzyShXe5CkYQ6SBCsjfqVjyTymqtY97LSX/GlGs764jlo3yixHcjv1OxHHzw3jSLCEa+ySpP8g6pUMuHtW2NdSj2UpEg34FqRG7VcN5zMS2ZsRqLpmsMg2MtymBdIEABSoZJ0SYypiAZk/h operator@second";

    private static String md5Fingerprint(String publicKey) {
        try {
            var blob = Base64.getDecoder().decode(publicKey.split("\\s+")[1]);
            var md5 = MessageDigest.getInstance("MD5").digest(blob);
            var hex = HexFormat.of().formatHex(md5);
            var sb = new StringBuilder();
            for (int i = 0; i < hex.length(); i += 2) {
                if (i > 0) {sb.append(':');}
                sb.append(hex, i, i + 2);
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static SourceProfile cloudHetznerSource() {
        return SourceProfile.sourceProfile(sourceNameOrDefault("eu-1"),
                                            SourceType.CLOUD,
                                            Option.some(CloudProviderName.HETZNER),
                                            Option.some("dummy-token"),
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

    private static ClusterBootstrapConfig configWithCloudSource() {
        return ClusterBootstrapConfig.clusterBootstrapConfig("1.0.0",
                                                              ClusterIdentity.clusterIdentity("test", "1.0.0").unwrap(),
                                                              CoreTopology.defaultCoreTopology(),
                                                              Map.of("eu-1", cloudHetznerSource()),
                                                              Map.of(),
                                                              InfrastructureConfig.infrastructureConfig(NetworkingType.MANUAL),
                                                              OperationsConfig.defaultOperationsConfig());
    }

    private static BootstrapContext contextWithKeys(List<SshPublicKey> keys) {
        var config = configWithCloudSource();
        var state = BootstrapState.initialState(clusterName("test").unwrap(), "h", "now");
        return BootstrapContext.bootstrapContext(config, state, List.of(), List.of()).withSshPublicKeys(keys);
    }

    @Test
    void execute_uploadsKey_whenFingerprintNotPresent() {
        var key = SshPublicKey.sshPublicKey(VALID_KEY).unwrap();
        var ctx = contextWithKeys(List.of(key));
        var stub = new RecordingHetznerClient(List.of());

        var result = BootstrapPhaseSshKey.execute(ctx, _ -> org.pragmatica.lang.Result.success(stub));

        assertTrue(result.isSuccess(), () -> "phase should succeed: " + result);
        var newCtx = result.unwrap();
        assertEquals(List.of(1L), newCtx.sshKeyIdsFor("hetzner"),
                     "First created key must get id 1 from the stub");
        assertEquals(1, stub.createdKeys.size(), "Should have called createSshKey once");
        assertEquals(VALID_KEY, stub.createdKeys.getFirst().publicKey());
        // RFC-0016 W3 §3.3 — uploaded key name is cluster-scoped: aether-bootstrap-<cluster>-<blob8>
        // (cluster identity name = "test"), never the old bare aether-bootstrap prefix.
        assertTrue(stub.createdKeys.getFirst().name().startsWith("aether-bootstrap-test-"),
                   () -> "Uploaded key name must be cluster-scoped: " + stub.createdKeys.getFirst().name());
    }

    @Test
    void execute_reusesKey_whenFingerprintAlreadyPresent() {
        var key = SshPublicKey.sshPublicKey(VALID_KEY).unwrap();
        var ctx = contextWithKeys(List.of(key));
        var existing = new SshKey(42L, "pre-existing", md5Fingerprint(VALID_KEY), VALID_KEY);
        var stub = new RecordingHetznerClient(List.of(existing));

        var result = BootstrapPhaseSshKey.execute(ctx, _ -> org.pragmatica.lang.Result.success(stub));

        assertTrue(result.isSuccess());
        assertEquals(List.of(42L), result.unwrap().sshKeyIdsFor("hetzner"),
                     "Pre-existing key id must be reused, not a new upload");
        assertTrue(stub.createdKeys.isEmpty(), "createSshKey must NOT be called when fingerprint matches");
    }

    @Test
    void execute_doesNotRegisterCreatedResource_whenKeyReused() {
        var key = SshPublicKey.sshPublicKey(VALID_KEY).unwrap();
        var ctx = contextWithKeys(List.of(key));
        var existing = new SshKey(42L, "pre-existing", md5Fingerprint(VALID_KEY), VALID_KEY);
        var stub = new RecordingHetznerClient(List.of(existing));

        var result = BootstrapPhaseSshKey.execute(ctx, _ -> org.pragmatica.lang.Result.success(stub));

        var resources = result.unwrap().state().createdResources();
        assertFalse(resources.stream().anyMatch(r -> r instanceof CreatedResource.SshKeyResource),
                    "Reused (pre-existing) keys must NOT be registered as CreatedResource — we don't own them");
    }

    @Test
    void execute_registersCreatedResource_whenKeyUploaded() {
        var key = SshPublicKey.sshPublicKey(VALID_KEY).unwrap();
        var ctx = contextWithKeys(List.of(key));
        var stub = new RecordingHetznerClient(List.of());

        var result = BootstrapPhaseSshKey.execute(ctx, _ -> org.pragmatica.lang.Result.success(stub));

        var resources = result.unwrap().state().createdResources();
        var keyResources = resources.stream()
                                    .filter(r -> r instanceof CreatedResource.SshKeyResource)
                                    .map(r -> (CreatedResource.SshKeyResource) r)
                                    .toList();
        assertEquals(1, keyResources.size(), "Newly uploaded key must be registered for cleanup");
        assertEquals("hetzner", keyResources.getFirst().provider());
        assertEquals(1L, keyResources.getFirst().sshKeyId());
    }

    @Test
    void execute_handlesMultipleKeys_uploadingAll() {
        var k1 = SshPublicKey.sshPublicKey(VALID_KEY).unwrap();
        var k2 = SshPublicKey.sshPublicKey(VALID_KEY_2).unwrap();
        var ctx = contextWithKeys(List.of(k1, k2));
        var stub = new RecordingHetznerClient(List.of());

        var result = BootstrapPhaseSshKey.execute(ctx, _ -> org.pragmatica.lang.Result.success(stub));

        assertTrue(result.isSuccess());
        assertEquals(List.of(1L, 2L), result.unwrap().sshKeyIdsFor("hetzner"),
                     "Two keys uploaded should yield two ids in order");
    }

    @Test
    void execute_skipsAllWork_whenSshKeyListEmpty() {
        var ctx = contextWithKeys(List.of());
        var stub = new RecordingHetznerClient(List.of());

        var result = BootstrapPhaseSshKey.execute(ctx, _ -> org.pragmatica.lang.Result.success(stub));

        assertTrue(result.isSuccess());
        assertTrue(stub.listCalls == 0, "list should not be called when no keys to upload");
        assertTrue(stub.createdKeys.isEmpty());
    }

    /// Stubs only the SshKey-related operations of HetznerClient. Other methods
    /// throw — they should never be called in these tests, and we want the
    /// assertion to be loud if scope creep happens.
    static final class RecordingHetznerClient implements HetznerClient {
        private final List<SshKey> existingKeys;
        final List<SshKey.CreateSshKeyRequest> createdKeys = new ArrayList<>();
        final List<Long> deleteCalls = new ArrayList<>();
        int listCalls = 0;
        private final AtomicLong nextId = new AtomicLong(1);

        RecordingHetznerClient(List<SshKey> existingKeys) {
            this.existingKeys = List.copyOf(existingKeys);
        }

        @Override public Promise<List<SshKey>> listSshKeys() {
            listCalls++;
            return Promise.success(existingKeys);
        }

        @Override public Promise<SshKey> createSshKey(SshKey.CreateSshKeyRequest request) {
            createdKeys.add(request);
            var id = nextId.getAndIncrement();
            return Promise.success(new SshKey(id, request.name(), "fp-" + id, request.publicKey()));
        }

        @Override public Promise<Unit> deleteSshKey(long sshKeyId) {
            deleteCalls.add(sshKeyId);
            return Promise.success(Unit.unit());
        }

        // Methods below should not be called by phase under test.
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
            return new AssertionError("Test stub: '" + name + "' should not be called by BootstrapPhaseSshKey");
        }
    }
}
