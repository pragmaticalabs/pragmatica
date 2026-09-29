// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.config.cluster.NodeUserDataRenderer;
import org.pragmatica.aether.config.cluster.ReplacementNodeConfigComposer;
import org.pragmatica.aether.config.cluster.RoleSubTable;
import org.pragmatica.aether.config.cluster.SourceCloudBindings;
import org.pragmatica.aether.config.cluster.SourceProfile;
import org.pragmatica.aether.config.cluster.SshDeploymentConfig;
import org.pragmatica.aether.environment.CloudProviderSupport;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.PlacementHint;
import org.pragmatica.aether.environment.ProvisionContext;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.aether.environment.ProvisionedNode;
import org.pragmatica.aether.environment.SourceName;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import tools.jackson.databind.JsonNode;

import static org.pragmatica.aether.management.route.ManagementRoute.NODES_LIVE;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;


/// How `aether cluster apply` (scale-up, add-role, reprovision, replace-before-retire, `--resume`/`--rollback`)
/// provisions a CLOUD node — through the same composition the leader's auto-heal uses
/// ([ReplacementNodeConfigComposer], [SourceCloudBindings#resolveOverlayFromConfig], [NodeUserDataRenderer]), so the
/// node boots with its cluster identity, secret, role, source, zone and the live core peers, and joins.
///
/// #1695: this path used to hand the provider a spec with NO user-data, so an apply-minted cloud node booted with no
/// identity and no runtime and could never join; and it asked for a location literally named `default` when the source
/// declared no zone. #1027: the node id is minted HERE, in the CTM scheme (`aether-<cluster>-node-<ulid>`, unique per
/// call), and the same id goes into the provisioning context and the rendered user-data. The old per-call
/// `<source>-<role>-<i>` index restarted at 0 on every wave, so threading it would have handed a scale-up node the id of a
/// live member. Known limitation: a minted id does not parse as `<source>-<role>-<i>`, exactly as a CTM replacement's
/// does not; the durable, parseable scheme is a committed high-water (rc5 ticket).
sealed interface WaveNodeProvisioning {
    record unused() implements WaveNodeProvisioning {}

    String CLUSTER_SECRET_ENV = "AETHER_CLUSTER_SECRET";

    /// What every node provisioned by one apply needs to join: the cluster secret and the live core peers
    /// (`nodeId:host:port`). Resolved once per apply.
    record JoinInputs(String clusterSecret, List<String> peers) {
        public JoinInputs {
            peers = List.copyOf(peers);
        }
    }

    /// The operator machine's persisted bootstrap secret for the cluster, else `AETHER_CLUSTER_SECRET`; the live core
    /// peers from the cluster itself. Either missing refuses the provision: a node minted without them cannot join,
    /// and a paid VM that never joins is the failure this path existed to produce (#1695).
    static Result<JoinInputs> resolveJoinInputs(ClusterName clusterName) {
        return clusterSecret(clusterName).toResult(new JoinInputsUnavailable(clusterName,
                                                                             "no cluster secret: neither this machine's bootstrap state for the cluster nor " + CLUSTER_SECRET_ENV
                                                                            + " holds one"))
                            .flatMap(secret -> livePeers(clusterName).map(peers -> new JoinInputs(secret, peers)));
    }

    /// Provision `count` CLOUD nodes of `role` from `source`, each under a freshly minted id.
    static Result<List<ProvisionedNode>> provisionCloud(ComputeProvider compute,
                                                        ClusterBootstrapConfig desired,
                                                        SourceProfile source,
                                                        NodeRole role,
                                                        int count,
                                                        JoinInputs inputs,
                                                        Supplier<String> nodeIds) {
        var nodes = new ArrayList<ProvisionedNode>();

        for (int i = 0; i < count; i++) {
            var nodeId = nodeIds.get();
            var provisioned = cloudProvisionSpec(desired, source, role, nodeId, inputs).flatMap(spec -> CloudProviderSupport.provisionOne(compute,
                                                                                                                                          nodeId,
                                                                                                                                          spec).await());

            if (provisioned.isFailure()) {
                return provisioned.map(_ -> List.<ProvisionedNode> of());
            }

            var _ = provisioned.onSuccess(nodes::add);
        }

        return success(List.copyOf(nodes));
    }

    /// A fresh node id in the CTM scheme, unique per call.
    static Supplier<String> mintedIds(ClusterName clusterName) {
        return () -> ProvisionContext.provisionContext(Option.some(clusterName),
                                                       "",
                                                       SourceName.DEFAULT,
                                                       ProvisionContext.PROVISIONED_BY_BOOTSTRAP)
                                     .resolveNodeId();
    }

    /// The node's spec: its id and live peers in the context, rendered user-data, its role image, and a zone
    /// placement ONLY when the source names one.
    static Result<ProvisionSpec> cloudProvisionSpec(ClusterBootstrapConfig desired,
                                                    SourceProfile source,
                                                    NodeRole role,
                                                    String nodeId,
                                                    JoinInputs inputs) {
        var clusterName = desired.cluster().name();
        var context = ProvisionContext.provisionContext(Option.some(clusterName),
                                                        role.value(),
                                                        source.name(),
                                                        Option.some(nodeId),
                                                        peersValue(inputs.peers()),
                                                        ProvisionContext.DEFAULT_CORE_MAX,
                                                        ProvisionContext.PROVISIONED_BY_BOOTSTRAP,
                                                        WaveExecutor.provisionTags(clusterName, source.name(), role));

        return renderUserData(desired, source, role, nodeId, inputs).flatMap(userData -> ProvisionSpec.provisionSpec(InstanceType.ON_DEMAND,
                                                                                                                     instanceType(source,
                                                                                                                                  role),
                                                                                                                     role.value(),
                                                                                                                     context)
                                                                                                      .map(spec -> spec.withUserData(userData))
                                                                                                      .map(spec -> withRoleImage(spec,
                                                                                                                                 source,
                                                                                                                                 role))
                                                                                                      .map(spec -> withZone(spec,
                                                                                                                            source.zone())));
    }

    private static Result<String> renderUserData(ClusterBootstrapConfig desired,
                                                 SourceProfile source,
                                                 NodeRole role,
                                                 String nodeId,
                                                 JoinInputs inputs) {
        return ReplacementNodeConfigComposer.compose(desired,
                                                     source,
                                                     role,
                                                     Option.some(inputs.clusterSecret()),
                                                     List.of())
                                            .flatMap(composed -> SourceCloudBindings.resolveOverlayFromConfig(composed,
                                                                                                              desired,
                                                                                                              source.name(),
                                                                                                              role))
                                            .map(composed -> NodeUserDataRenderer.render(desired,
                                                                                         source,
                                                                                         role,
                                                                                         nodeId,
                                                                                         0,
                                                                                         inputs.clusterSecret(),
                                                                                         desired.cluster().name(),
                                                                                         composed,
                                                                                         authorizedKeys(desired),
                                                                                         inputs.peers()));
    }

    private static String instanceType(SourceProfile source, NodeRole role) {
        return option(source.roles().get(role)).flatMap(RoleSubTable::instanceType)
                     .or("default");
    }

    private static ProvisionSpec withRoleImage(ProvisionSpec spec, SourceProfile source, NodeRole role) {
        return option(source.roles().get(role)).flatMap(RoleSubTable::image)
                     .map(spec::withImage)
                     .or(spec);
    }

    /// #1695: the zone is optional. An absent zone leaves placement to the provider's configured region, never a
    /// location literally named `default`.
    private static ProvisionSpec withZone(ProvisionSpec spec, Option<String> zone) {
        return zone.map(name -> spec.withPlacement(new PlacementHint.ZoneHint(name)))
                   .or(spec);
    }

    private static Option<String> peersValue(List<String> peers) {
        return peers.isEmpty()
               ? Option.none()
               : Option.some(String.join(",", peers));
    }

    private static List<String> authorizedKeys(ClusterBootstrapConfig desired) {
        return desired.infrastructure()
                      .ssh()
                      .map(SshDeploymentConfig::authorizedKeys)
                      .or(List.of());
    }

    private static Option<String> clusterSecret(ClusterName clusterName) {
        return BootstrapStatePersistence.load(clusterName)
                                        .map(BootstrapState::clusterSecret)
                                        .filter(secret -> !secret.isBlank())
                                        .orElse(() -> option(System.getenv(CLUSTER_SECRET_ENV)).filter(secret -> !secret.isBlank()));
    }

    /// The live CORE members, as the `nodeId:host:port` peer entries a joining node dials.
    private static Result<List<String>> livePeers(ClusterName clusterName) {
        return ClusterHttpClient.fetch(NODES_LIVE)
                                .flatMap(JsonMapper.defaultJsonMapper()::readTree)
                                .map(WaveNodeProvisioning::corePeers)
                                .flatMap(peers -> peers.isEmpty()
                                                  ? new JoinInputsUnavailable(clusterName,
                                                                              "the cluster reports no live core member to join through").result()
                                                  : success(peers));
    }

    static List<String> corePeers(JsonNode liveNodes) {
        var peers = new ArrayList<String>();

        for (var node : liveNodes.path("nodes")) {
            var address = node.path("address").asText("");

            if ("core".equalsIgnoreCase(node.path("role").asText("")) && node.path("swimAlive").asBoolean(false) && !address.isBlank()) {
                peers.add(node.path("nodeId").asText() + ":" + address);
            }
        }

        return List.copyOf(peers);
    }

    record JoinInputsUnavailable(ClusterName clusterName, String reason) implements Cause {
        @Override
        public String message() {
            return "Refusing to provision for cluster '" + clusterName.value()
                 + "': " + reason
                 + ". A node provisioned without them boots unable to join.";
        }
    }
}
