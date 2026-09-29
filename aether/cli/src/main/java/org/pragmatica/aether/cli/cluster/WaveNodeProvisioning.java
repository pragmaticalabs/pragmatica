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
import org.pragmatica.aether.environment.InstanceId;
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
import org.pragmatica.lang.Unit;

import tools.jackson.databind.JsonNode;

import static org.pragmatica.aether.management.route.ManagementRoute.NODES_LIVE;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;


/// How `aether cluster apply` (scale-up, add-role, reprovision, replace-before-retire, `--resume`/`--rollback`)
/// provisions a CLOUD node — through the composition the leader's auto-heal uses
/// ([ReplacementNodeConfigComposer], [SourceCloudBindings#resolveOverlayFromConfig], [NodeUserDataRenderer]), so the
/// node boots with its cluster identity, secret, role, source, zone and the live core peers, and joins. One difference
/// from auto-heal: `ssh_key_ids` are empty here (auto-heal threads the leader's own, #442), so a node minted here that
/// later becomes leader provisions through the provider's by-name key fallback; see #1724.
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
                return provisioned.mapError(cause -> PartiallyProvisioned.partiallyProvisioned(tearDown(compute,
                                                                                                        created(nodes,
                                                                                                                source)),
                                                                                               cause))
                                  .map(_ -> List.<ProvisionedNode> of());
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

    /// A node this apply created, with the provider that holds its VM and — for a node of the FAILING step — the outcome
    /// of destroying it. `teardown` is empty for a node of an earlier, completed step: it belongs to the desired
    /// configuration and is kept.
    record CreatedNode(ProvisionedNode node, String provider, Option<Result<Unit>> teardown) {
        boolean stillRunning() {
            return teardown.map(Result::isFailure)
                           .or(true);
        }

        /// The exact steps to remove the node: drain it if it joined, then delete the VM with the provider's own
        /// tool (the CLI has no per-node VM delete).
        String removal() {
            return "aether cluster drain " + node.nodeId() + " --wait --yes (if it joined), then " + providerDelete();
        }

        String describe() {
            var vm = "node " + node.nodeId()
                   + "  provider " + provider
                   + "  server " + node.serverId()
                   + "  ip " + node.publicIp();

            return teardown.fold(() -> "kept, RUNNING AND BILLED (created by an earlier step of this apply; it belongs to"
                                      + " the desired configuration): " + vm
                                      + "\n      remove if unwanted: " + removal(),
                                 outcome -> outcome.fold(failure -> "STILL RUNNING AND BILLED: " + vm
                                                                   + " (destroy failed: " + failure.message()
                                                                   + ")\n      remove: " + removal(),
                                                         _ -> "destroyed: " + vm));
        }

        private String providerDelete() {
            return switch (provider) {
                case "hetzner" -> "hcloud server delete " + node.serverId();
                case "docker" -> "docker rm -f " + node.serverId();
                default -> "delete instance " + node.serverId() + " in the " + provider + " console";
            };
        }
    }

    static List<CreatedNode> created(List<ProvisionedNode> nodes, SourceProfile source) {
        var provider = providerName(source);

        return nodes.stream()
                    .map(node -> new CreatedNode(node,
                                                 provider,
                                                 Option.none()))
                    .toList();
    }

    /// The failing step's VMs are destroyed, best-effort: the apply persists the desired configuration only on success,
    /// so they belong to no desired state, and a retry mints new ids and never reuses them.
    private static List<CreatedNode> tearDown(ComputeProvider compute, List<CreatedNode> created) {
        return created.stream()
                      .map(node -> new CreatedNode(node.node(),
                                                   node.provider(),
                                                   Option.some(InstanceId.instanceId(node.node().serverId())
                                                                         .async()
                                                                         .flatMap(compute::terminate)
                                                                         .await())))
                      .toList();
    }

    static String providerName(SourceProfile source) {
        return source.provider()
                     .map(provider -> provider.value())
                     .or(source.type().value());
    }

    /// A rollout that fails after creating VMs. The failing step's VMs have been torn down; each destroy that failed is
    /// named as STILL RUNNING AND BILLED with its provider, server id and removal steps. VMs from earlier completed steps
    /// are kept and listed too, because the rollout records none of them: a retry mints new ids, and `--rollback` does
    /// not see them.
    record PartiallyProvisioned(List<CreatedNode> created, Cause cause) implements Cause {
        static Cause partiallyProvisioned(List<CreatedNode> created, Cause cause) {
            return switch (cause) {
                case PartiallyProvisioned inner -> created.isEmpty()
                                                   ? inner
                                                   : new PartiallyProvisioned(merge(created, inner.created()),
                                                                              inner.cause());
                default -> created.isEmpty()
                           ? cause
                           : new PartiallyProvisioned(List.copyOf(created), cause);
            };
        }

        private static List<CreatedNode> merge(List<CreatedNode> earlier, List<CreatedNode> later) {
            var all = new ArrayList<CreatedNode>(earlier);

            later.stream().filter(node -> !all.contains(node)).forEach(all::add);

            return List.copyOf(all);
        }

        @Override
        public String message() {
            var sb = new StringBuilder();
            var stillRunning = created.stream().filter(CreatedNode::stillRunning).count();

            sb.append("apply failed part-way: ").append(cause.message()).append('\n');
            created.forEach(node -> sb.append("    - ")
                                      .append(node.describe())
                                      .append('\n'));
            sb.append("  ")
              .append(stillRunning)
              .append(" cloud VM(s) created by this apply are STILL RUNNING AND BILLED and are not recorded by the apply")
              .append(" (a retry mints new ids and will not reuse them; --rollback does not see them).\n");

            return sb.toString();
        }
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
