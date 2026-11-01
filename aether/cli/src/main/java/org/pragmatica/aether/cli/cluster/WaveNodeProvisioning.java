// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.pragmatica.aether.environment.ClusterSecretSource;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.PlacementHint;
import org.pragmatica.aether.environment.ProvisionContext;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.aether.environment.ProvisionedNode;
import org.pragmatica.aether.environment.SourceName;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import tools.jackson.databind.JsonNode;

import static org.pragmatica.aether.management.route.ManagementRoute.NODES_LIVE;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;


/// How `aether cluster apply` (scale-up, add-role, reprovision, replace-before-retire, `--resume`)
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
        return resolveJoinInputs(clusterName, System::getenv);
    }

    static Result<JoinInputs> resolveJoinInputs(ClusterName clusterName, Fn1<String, String> env) {
        return clusterSecret(clusterName, env).flatMap(secret -> livePeers(clusterName).map(peers -> new JoinInputs(secret,
                                                                                                                    peers)));
    }

    /// Provision `count` CLOUD nodes of `role` from `source`, each under a freshly minted id, each recorded in the
    /// ledger BEFORE its create call (a failed write means no call) and after the provider's answer.
    static Result<List<ProvisionedNode>> provisionCloud(ComputeProvider compute,
                                                        ClusterBootstrapConfig desired,
                                                        SourceProfile source,
                                                        NodeRole role,
                                                        int count,
                                                        JoinInputs inputs,
                                                        Supplier<String> nodeIds,
                                                        ApplyRecorder recorder) {
        var nodes = new ArrayList<ProvisionedNode>();

        for (int i = 0; i < count; i++) {
            var nodeId = nodeIds.get();
            var provisioned = cloudProvisionSpec(desired, source, role, nodeId, inputs, recorder.applyId()).flatMap(spec -> createRecorded(compute,
                                                                                                                                           spec,
                                                                                                                                           nodeId,
                                                                                                                                           providerName(source),
                                                                                                                                           recorder,
                                                                                                                                           WaveNodeProvisioning::returnedLabels));

            if (provisioned.isFailure()) {
                return provisioned.mapError(cause -> PartiallyProvisioned.partiallyProvisioned(tearDown(compute,
                                                                                                        created(nodes,
                                                                                                                source),
                                                                                                        recorder),
                                                                                               cause))
                                  .map(_ -> List.<ProvisionedNode> of());
            }

            var _ = provisioned.onSuccess(nodes::add);
        }

        return success(List.copyOf(nodes));
    }

    /// The write-ahead sequence for one node, shared by every source type that creates a billed or running VM:
    /// S1 intent row, then the provider create, then the created row, after checking the VM carries its labels.
    static Result<ProvisionedNode> createRecorded(ComputeProvider compute,
                                                  ProvisionSpec spec,
                                                  String nodeId,
                                                  String provider,
                                                  ApplyRecorder recorder,
                                                  LabelCheck labelCheck) {
        return recorder.intend(nodeId, provider)
                       .flatMap(_ -> answered(compute.provision(spec).await(),
                                              nodeId,
                                              provider,
                                              recorder))
                       .flatMap(info -> recordCreated(compute, info, nodeId, provider, recorder, labelCheck));
    }

    /// How a source type proves its VM carries the labels it was asked for (F9). A cloud provider reports the labels on
    /// the instance it returns; Docker re-stamps the returned instance from `docker inspect`, which lists no custom
    /// labels, so there the daemon's own label filter answers.
    @FunctionalInterface
    interface LabelCheck {
        Result<Unit> verify(ComputeProvider compute, InstanceInfo info, String nodeId, String applyId);
    }

    static Result<Unit> returnedLabels(ComputeProvider compute, InstanceInfo info, String nodeId, String applyId) {
        return verifyLabels(info, nodeId, applyId);
    }

    static Result<Unit> filteredListing(ComputeProvider compute, InstanceInfo info, String nodeId, String applyId) {
        var serverId = info.id().value();

        return compute.listInstances(Map.of(APPLY_LABEL, applyId))
                      .await()
                      .flatMap(listed -> listed.stream()
                                               .anyMatch(item -> item.id()
                                                                     .value()
                                                                     .equals(serverId)) && info.nodeId()
                                                                                               .equals(Option.some(nodeId))
                                         ? Result.unitResult()
                                         : new LabelsMissing(nodeId, serverId).result());
    }

    /// Whether `info`, as a provider LISTED it, carries this cluster's label and this apply's label. Cloud providers report
    /// custom labels on the instance; the Docker provider reports `aether.cluster` and never custom labels, so for Docker the
    /// apply label is what the daemon's own label filter already proved. A listing the server-side selector got wrong (or
    /// ignored) therefore never makes a server "ours" by itself.
    static boolean ownedBy(String provider, InstanceInfo info, String cluster, String applyId) {
        return "docker".equals(provider)
               ? cluster.equals(info.tags().get("aether.cluster"))
               : cluster.equals(info.tags().get("aether-cluster")) && applyId.equals(info.tags().get(APPLY_LABEL));
    }

    private static Result<InstanceInfo> answered(Result<InstanceInfo> answer,
                                                 String nodeId,
                                                 String provider,
                                                 ApplyRecorder recorder) {
        recorder.providerAnswered();

        return answer.fold(cause -> unanswered(cause, nodeId, provider, recorder), Result::success);
    }

    /// G1-f: a create the provider refused, read by what it says. A refused NAME is not a failed create: the provider names the server
    /// after the node id and refuses a second server with that name, so `AlreadyExists` means the earlier attempt (a lost reply, a racing
    /// resume) made the server, and it is the answer, but only if it is OURS: the cluster and apply labels are checked here, whatever the
    /// provider checked. A name held by anything else is refused, typed, and never adopted. Every other failure keeps the row INTENDED.
    private static Result<InstanceInfo> unanswered(Cause cause,
                                                   String nodeId,
                                                   String provider,
                                                   ApplyRecorder recorder) {
        return switch (cause) {
            case EnvironmentError.AlreadyExists existing -> adoptIfOwned(existing.existing(), nodeId, provider, recorder);
            case EnvironmentError.NameHeldByOther held -> new NameHeld(nodeId,
                                                                       held.holder().value()).result();
            default -> new CreateUnconfirmed(nodeId, cause).result();
        };
    }

    private static Result<InstanceInfo> adoptIfOwned(InstanceInfo existing,
                                                     String nodeId,
                                                     String provider,
                                                     ApplyRecorder recorder) {
        return ownedBy(provider,
                       existing,
                       recorder.snapshot().clusterName().value(),
                       recorder.applyId())
               ? success(existing)
               : new NameHeld(nodeId,
                              existing.id().value()).result();
    }

    /// The server name of a node's create is held by a server that is not this apply's (another cluster's, another apply's, or one
    /// without the labels). Nothing was adopted and nothing was created; the row stays INTENDED. Resolve the holder, then `--resume`.
    record NameHeld(String nodeId, String holder) implements Cause {
        @Override
        public String message() {
            return "The provider refused the create for node " + nodeId
                 + ": its server name is held by instance " + holder
                 + ", which does not carry this cluster's and this apply's labels. It was not adopted and no second server was created;"
                 + " the ledger keeps the node INTENDED. Remove or relabel the holder, then run `aether cluster apply --resume`.";
        }
    }

    /// A create that failed or whose reply was lost (timeout, 5xx) may nevertheless have created the VM. The row stays
    /// INTENDED. `--resume` looks the node up by label (ApplyReconciler), adopts it if it exists, and never creates an id twice.
    record CreateUnconfirmed(String nodeId, Cause origin) implements Cause.Wrapped {
        @Override
        public String message() {
            return "The create call for node " + nodeId
                 + " failed: " + origin.message()
                 + ". The node may nevertheless exist at the provider"
                 + " (look for the label " + NODE_LABEL
                 + "=" + nodeId
                 + "); the ledger keeps it as INTENDED. Run `aether cluster apply --resume`, not a fresh apply:"
                 + " it looks the node up by that label, adopts it if it exists and creates a node only for an id that is absent.";
        }
    }

    /// The VM exists from here on. If the label check or the created row fails it is terminated NOW by the id the
    /// provider returned; a failed terminate is named STILL RUNNING AND BILLED. (Not gated on a TERMINATING row: that
    /// write would hit the same ledger that just failed, and the intent row already covers this VM.)
    private static Result<ProvisionedNode> recordCreated(ComputeProvider compute,
                                                         InstanceInfo info,
                                                         String nodeId,
                                                         String provider,
                                                         ApplyRecorder recorder,
                                                         LabelCheck labelCheck) {
        var serverId = info.id().value();

        return labelCheck.verify(compute,
                                 info,
                                 nodeId,
                                 recorder.applyId())
                         .flatMap(_ -> recorder.created(nodeId, serverId))
                         .map(_ -> CloudProviderSupport.toProvisionedNode(nodeId, info))
                         .mapError(cause -> terminateUnrecorded(compute, info, nodeId, provider, recorder, cause));
    }

    private static Cause terminateUnrecorded(ComputeProvider compute,
                                             InstanceInfo info,
                                             String nodeId,
                                             String provider,
                                             ApplyRecorder recorder,
                                             Cause cause) {
        var outcome = InstanceId.instanceId(info.id().value()).async().flatMap(compute::terminate).await();
        var node = new CreatedNode(CloudProviderSupport.toProvisionedNode(nodeId, info), provider, Option.some(outcome));
        // FER: when the terminate succeeded, a failed TERMINATED write only leaves the row INTENDED/CREATED for a VM that
        // is gone. Guarantee earned: no billed VM is hidden. Mechanism: the resume lookup by label finds nothing and marks
        // the row ABSENT.
        outcome.onSuccess(_ -> bestEffort(recorder.terminated(nodeId)))
               .onFailure(_ -> bestEffort(recorder.terminateFailed(nodeId,
                                                                   info.id().value())));

        return PartiallyProvisioned.partiallyProvisioned(List.of(node), cause);
    }

    static final String APPLY_LABEL = "aether-apply";
    static final String NODE_LABEL = "aether-node-id";

    /// F9: a provider may drop a label it dislikes without an error. The VM must carry the apply label (the audit and
    /// the leak check find it by it) and its minted node identity, else it is invisible to every later audit.
    static Result<Unit> verifyLabels(InstanceInfo info, String nodeId, String applyId) {
        var identity = info.nodeId().orElse(() -> option(info.tags().get(NODE_LABEL)));

        return applyId.equals(info.tags().get(APPLY_LABEL)) && identity.equals(Option.some(nodeId))
               ? Result.unitResult()
               : new LabelsMissing(nodeId,
                                   info.id().value()).result();
    }

    record LabelsMissing(String nodeId, String serverId) implements Cause {
        @Override
        public String message() {
            return "The provider created server " + serverId
                 + " for node " + nodeId
                 + " without its '" + APPLY_LABEL
                 + "' / '" + NODE_LABEL
                 + "' labels (a provider may drop a label it does not accept); a VM without them is"
                 + " invisible to every audit, so it was terminated.";
        }
    }

    /// The ids a resumed action asks for: first the ids of its rows the resume lookup found NEVER created (ABSENT), re-issued under the
    /// same name, then freshly minted ones. A re-issue is safe because the provider refuses a second server with that name, so a create
    /// that landed after the lookup window answers `AlreadyExists` and is adopted, not duplicated. Only ABSENT rows qualify: a CREATED
    /// row whose VM vanished ends GONE and is never re-created (P-G1 A5: a name is free again once its server is deleted, so a re-create
    /// would bring back a server somebody deleted).
    static Supplier<String> reissuedThenMinted(ApplyRecorder recorder, Supplier<String> minted) {
        var pending = new ArrayDeque<>(recorder.absentNodeIds());

        return () -> option(pending.poll()).or(minted);
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
                                                    JoinInputs inputs,
                                                    String applyId) {
        var clusterName = desired.cluster().name();
        var context = ProvisionContext.provisionContext(Option.some(clusterName),
                                                        role.value(),
                                                        source.name(),
                                                        Option.some(nodeId),
                                                        peersValue(inputs.peers()),
                                                        ProvisionContext.DEFAULT_CORE_MAX,
                                                        ProvisionContext.PROVISIONED_BY_BOOTSTRAP,
                                                        WaveExecutor.provisionTags(clusterName,
                                                                                   source.name(),
                                                                                   role,
                                                                                   applyId));

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

    /// The cluster secret a joining node must carry: this machine's bootstrap state for the cluster, else the
    /// operator's own. A ledger that is present but UNREADABLE (torn, or holding an entry this CLI does not
    /// know) refuses the provision: reading it as "no ledger" would fall through to the ambient secret, and if
    /// that differs from the ledger's the rendered nodes boot unable to join, on paid VMs.
    private static Result<String> clusterSecret(ClusterName clusterName, Fn1<String, String> env) {
        return BootstrapStatePersistence.read(clusterName)
                                        .mapError(cause -> new JoinInputsUnavailable(clusterName,
                                                                                     "the bootstrap state for the cluster is unreadable and was left untouched: " + cause.message()))
                                        .flatMap(state -> persistedOrAmbient(clusterName, state, env));
    }

    private static Result<String> persistedOrAmbient(ClusterName clusterName,
                                                     Option<BootstrapState> state,
                                                     Fn1<String, String> env) {
        return state.map(BootstrapState::clusterSecret)
                    .filter(secret -> !secret.isBlank())
                    .orElse(() -> ambientSecret(env))
                    .toResult(new JoinInputsUnavailable(clusterName,
                                                        "no cluster secret: neither this machine's bootstrap state for the cluster nor " + CLUSTER_SECRET_ENV
                                                       + " holds one"));
    }

    /// The operator's own secret: `AETHER_CLUSTER_SECRET` or the file `AETHER_CLUSTER_SECRET_FILE` names (#828), so an operator who
    /// adopted the file form can still join nodes. A broken file reads as absent and the caller's "no cluster secret" refusal names it.
    static Option<String> ambientSecret(Fn1<String, String> env) {
        return ClusterSecretSource.resolve(env).fold(_ -> Option.<String> none(), value -> value);
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
    private static List<CreatedNode> tearDown(ComputeProvider compute,
                                              List<CreatedNode> created,
                                              ApplyRecorder recorder) {
        return created.stream()
                      .map(node -> new CreatedNode(node.node(),
                                                   node.provider(),
                                                   Option.some(terminateRecorded(compute, node, recorder))))
                      .toList();
    }

    /// S6 then the provider terminate then S7: no terminate is issued when the TERMINATING row cannot be written.
    private static Result<Unit> terminateRecorded(ComputeProvider compute, CreatedNode node, ApplyRecorder recorder) {
        var nodeId = node.node().nodeId();

        return recorder.terminating(nodeId)
                       .flatMap(_ -> InstanceId.instanceId(node.node().serverId())
                                               .async()
                                               .flatMap(compute::terminate)
                                               .await())
                       .flatMap(_ -> recorder.terminated(nodeId))
                       .onFailure(_ -> markTerminateFailed(nodeId, recorder));
    }

    // FER: the terminate already failed and is reported to the operator by name; failing to also write TERMINATE_FAILED
    // leaves the row at TERMINATING, which the leak check and the resume audit read the same way (the VM may stand).
    private static Unit markTerminateFailed(String nodeId, ApplyRecorder recorder) {
        return bestEffort(recorder.terminateFailed(nodeId));
    }

    private static Unit bestEffort(Result<Unit> write) {
        return write.or(Unit.unit());
    }

    static String providerName(SourceProfile source) {
        return source.provider()
                     .map(provider -> provider.value())
                     .or(source.type().value());
    }

    /// A rollout that fails after creating VMs. The failing step's VMs have been torn down; each destroy that failed is
    /// named as STILL RUNNING AND BILLED with its provider, server id and removal steps. VMs from earlier completed steps
    /// are kept and listed too, because the rollout records none of them: a retry mints new ids and does not reuse them.
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
              .append(" (a retry mints new ids and will not reuse them).\n");

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
