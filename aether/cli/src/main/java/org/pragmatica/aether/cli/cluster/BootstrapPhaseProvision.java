// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import org.pragmatica.aether.cli.cluster.ClusterBootstrapOrchestrator.BootstrapContext;
import org.pragmatica.aether.config.cluster.CloudProviderName;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.config.cluster.RoleSubTable;
import org.pragmatica.aether.config.cluster.SourceProfile;
import org.pragmatica.aether.config.cluster.SourceType;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.environment.CloudProviderSupport;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.NodeGroupConfig;
import org.pragmatica.aether.environment.PlacementHint;
import org.pragmatica.aether.environment.ProvisionContext;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.aether.environment.ProvisionedNode;
import org.pragmatica.aether.environment.SourceName;
import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.aether.cli.cluster.BootstrapPhase.PROVISION;
import static org.pragmatica.aether.environment.SourceName.sourceNameOrDefault;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;


@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02"})
sealed interface BootstrapPhaseProvision {
    record unused() implements BootstrapPhaseProvision {}

    static Result<BootstrapContext> execute(BootstrapContext ctx) {
        ClusterBootstrapOrchestrator.logPhase(PROVISION,
                                              "Provisioning infrastructure for %d source(s)",
                                              ctx.config().sources().size());
        var allNodes = new ArrayList<ProvisionedNode>();
        var clusterName = ctx.config().cluster().name();
        var mgmtPort = ctx.config().operations().ports().management();

        for (var entry : ctx.config().sources().entrySet()) {
            var sourceName = sourceNameOrDefault(entry.getKey());
            var source = entry.getValue();

            persistCleanupHandle(ctx, sourceName, source);
            var result = provisionSource(ctx, sourceName, source, mgmtPort, clusterName);

            if (result.isFailure()) {
                return result.map(_ -> ctx);
            }

            var _ = result.onSuccess(allNodes::addAll);
        }

        var updatedState = buildUpdatedState(ctx, allNodes);

        return success(ctx.withNodes(List.copyOf(allNodes)).withState(updatedState));
    }

    private static BootstrapState buildUpdatedState(BootstrapContext ctx, List<ProvisionedNode> allNodes) {
        var state = ctx.state().withProvisionedNodeIds(allNodes.stream().map(ProvisionedNode::nodeId).toList());
        var rawToml = ctx.rawTomlContent();

        for (var entry : ctx.config().sources().entrySet()) {
            var sourceName = sourceNameOrDefault(entry.getKey());
            var source = entry.getValue();
            var providerName = resolveProviderName(source);

            for (var node : allNodes) {
                if (node.nodeId().startsWith(sourceName.value() + "-")) {
                    state = state.withResource(CreatedResource.ProvisionedVm.provisionedVm(providerName,
                                                                                           node.serverId(),
                                                                                           sourceName.value(),
                                                                                           extractRole(node.nodeId(),
                                                                                                       sourceName.value())));
                }
            }

            state = stampSourceHandle(state, rawToml, sourceName, source, providerName);
        }

        return state;
    }

    /// #994 — the cleanup ledger must be able to NAME a paid VM the moment that VM exists, and the
    /// credential that reaps it must already be resolvable when the first server is created. Both used
    /// to be written only by [#buildUpdatedState], which runs ONLY after every source provisioned
    /// successfully — so a failure part-way through PROVISION (observed 2026-09-11: a dedicated-core
    /// quota refusal on the third of three servers) left the ledger holding the firewall and NO VMs.
    /// Cleanup then had nothing to delete but the firewall, which the two surviving servers still held;
    /// it retried `422 resource_in_use` six times, gave up, and never issued a single server delete.
    ///
    /// Records go to the PERSISTED state, not to the in-memory context: a failed phase returns a
    /// `Result` failure, which carries no context, and
    /// [ClusterBootstrapOrchestrator#cleanupOnFailure] re-loads the state FILE. The file is therefore
    /// the only channel that survives the failure, which makes it the authority for created resources.
    @Contract
    private static void persistCleanupHandle(BootstrapContext ctx, SourceName sourceName, SourceProfile source) {
        if (source.type() != SourceType.CLOUD) {
            return;
        }

        persistCleanupHandle(ctx.state().clusterName(),
                             ctx.rawTomlContent(),
                             sourceName,
                             source);
    }

    /// Takes the cluster name rather than the whole context so the persist is exercisable on its own:
    /// the context carries nothing else this needs, and a seam that demands a full `BootstrapContext`
    /// is a seam no test drives.
    ///
    /// #994 verification finding SF-1 — reads through [BootstrapStatePersistence#read] and CONSUMES the
    /// save, because every step here used to fail silently. The real incident artifact recorded
    /// `sources: []`, which is precisely this handle missing, and teardown without it cannot re-derive the
    /// provisioning token at all. A silent no-op costs the operator that credential mapping with nothing
    /// in the transcript to say so.
    @Contract
    static void persistCleanupHandle(ClusterName clusterName,
                                     String rawToml,
                                     SourceName sourceName,
                                     SourceProfile source) {
        var _ = BootstrapStatePersistence.read(clusterName)
                                         .onFailure(cause -> warnHandleNotPersisted(clusterName,
                                                                                    sourceName,
                                                                                    "the persisted ledger is unreadable: " + cause.message()))
                                         .or(Option.empty())
                                         .onEmpty(() -> warnHandleNotPersisted(clusterName,
                                                                               sourceName,
                                                                               "no bootstrap state is persisted yet"))
                                         .map(state -> withSourceHandle(state,
                                                                        rawToml,
                                                                        sourceName,
                                                                        source,
                                                                        resolveProviderName(source)))
                                         .onPresent(state -> saveOrWarnHandle(state, clusterName, sourceName));
    }

    @Contract
    private static void saveOrWarnHandle(BootstrapState state, ClusterName clusterName, SourceName sourceName) {
        var _ = BootstrapStatePersistence.save(state).onFailure(cause -> warnHandleNotPersisted(clusterName,
                                                                                                sourceName,
                                                                                                "the ledger write failed: " + cause.message()));
    }

    @Contract
    private static void warnHandleNotPersisted(ClusterName clusterName, SourceName sourceName, String reason) {
        System.err.printf("  WARN: the cleanup handle for source '%s' was NOT persisted — %s.%n", sourceName, reason);
        System.err.printf("  Teardown of cluster '%s' will have no credential mapping for this source and may fall"
                         + " back to a raw provider env var naming a different account. Reap with"
                         + " 'tools/cloud-reaper.sh --cluster %s --destroy' if bootstrap fails.%n",
                          clusterName,
                          clusterName);
    }

    /// #994 — appends the VM to the persisted cleanup ledger as soon as the provider reports it created,
    /// so a refusal on a LATER node of the same role group still leaves every already-paid server
    /// nameable by teardown. The role is passed in rather than parsed back out of the node id by
    /// [#extractRole], because here it is known exactly.
    ///
    /// Duplicate-free on the success path: [#buildUpdatedState] rebuilds the resource list from the
    /// pre-phase in-memory state and [ClusterBootstrapOrchestrator] saves THAT, replacing these
    /// incremental records with an equal set rather than appending to them.
    ///
    /// #994 verification finding SF-1 — **every way this can fail to record is now printed WITH the server
    /// id.** It was a silent no-op when the ledger was absent (which is what an unchecked pre-phase save
    /// leaves behind), a silent no-op when the file was torn, and it discarded the save's `Result`. Each
    /// of those drops a server that is already billing, and the recovery an operator needs is exactly the
    /// id — so the id goes to stderr even though the ledger cannot hold it. It does NOT fail the
    /// provisioning: the VM exists either way, aborting does not un-bill it, and a hard failure here would
    /// turn a full disk into a dead bootstrap.
    @Contract
    static void recordProvisionedVm(ClusterName clusterName,
                                    String providerName,
                                    SourceName sourceName,
                                    NodeRole role,
                                    ProvisionedNode node) {
        var _ = BootstrapStatePersistence.read(clusterName)
                                         .onFailure(cause -> warnVmNotRecorded(node,
                                                                               clusterName,
                                                                               "the persisted ledger is unreadable: " + cause.message()))
                                         .or(Option.empty())
                                         .onEmpty(() -> warnVmNotRecorded(node,
                                                                          clusterName,
                                                                          "no bootstrap state is persisted for this cluster"))
                                         .map(state -> state.withResource(CreatedResource.ProvisionedVm.provisionedVm(providerName,
                                                                                                                      node.serverId(),
                                                                                                                      sourceName.value(),
                                                                                                                      role.value())))
                                         .onPresent(state -> saveOrWarnVm(state, node, clusterName));
    }

    @Contract
    private static void saveOrWarnVm(BootstrapState state, ProvisionedNode node, ClusterName clusterName) {
        var _ = BootstrapStatePersistence.save(state).onFailure(cause -> warnVmNotRecorded(node,
                                                                                           clusterName,
                                                                                           "the ledger write failed: " + cause.message()));
    }

    /// The id is the whole point of this message. With the ledger broken it is the only place the server
    /// is named at all, so it has to be printed rather than logged at a level nobody reads — #994's cost
    /// was two `ccx23` servers whose ids had to be reconstructed by hand from `hcloud server list`.
    @Contract
    private static void warnVmNotRecorded(ProvisionedNode node, ClusterName clusterName, String reason) {
        System.err.printf("  WARN: VM %s (node %s) was NOT recorded in the cleanup ledger — %s.%n",
                          node.serverId(),
                          node.nodeId(),
                          reason);
        System.err.printf("  This server IS PAID and 'aether cluster destroy' will not find it. Remove it with"
                         + " 'tools/cloud-reaper.sh --cluster %s --destroy', or directly by id %s.%n",
                          clusterName,
                          node.serverId());
    }

    /// #994 — Aspects: wraps a per-node provisioner so every node it creates is recorded BEFORE the
    /// group's overall outcome is known, and records nothing for an attempt that failed. Composed in
    /// [#provisionAndRecordRoleGroup], which is what production calls and what
    /// `BootstrapPhaseProvisionLedgerTest` drives — so the line wiring this aspect to the recorder is
    /// itself covered, not merely each half of it.
    static ZoneProvisioner recordingProvisioner(ZoneProvisioner seam, Consumer<ProvisionedNode> recorder) {
        return (nodeId, globalIndex, zone) -> seam.provisionInZone(nodeId, globalIndex, zone)
                                                  .onSuccess(recorder);
    }

    static BootstrapState stampSourceHandle(BootstrapState state,
                                            String rawToml,
                                            SourceName sourceName,
                                            SourceProfile source,
                                            String providerName) {
        if (source.type() != SourceType.CLOUD) {
            return state;
        }

        warnOnUnmappedCredentials(sourceName, providerName, extractEnvVarNames(rawToml, sourceName.value()));

        return withSourceHandle(state, rawToml, sourceName, source, providerName);
    }

    /// The handle stamp WITHOUT the unmapped-credential warning, so #994's pre-provision persist and the
    /// success-path stamp can both write the handle while the operator still reads the warning exactly
    /// once — from [#stampSourceHandle], which is the only caller that warns.
    static BootstrapState withSourceHandle(BootstrapState state,
                                           String rawToml,
                                           SourceName sourceName,
                                           SourceProfile source,
                                           String providerName) {
        if (source.type() != SourceType.CLOUD) {
            return state;
        }

        var envVars = extractEnvVarNames(rawToml, sourceName.value());
        var handle = SourceCleanupHandle.sourceCleanupHandle(providerName, source.region(), envVars);

        return state.withSource(sourceName.value(), handle);
    }

    /// #521 — a cloud source whose `[source.<name>]` stanza yields no `${env:NAME}` credential leaves the
    /// persisted handle without the mapping `aether cluster destroy` needs to re-derive the provisioning
    /// token. Cleanup then falls back to the raw provider env var, which may name a different account.
    /// Say so at bootstrap time rather than discovering it hours later with paid VMs on the line.
    @Contract
    private static void warnOnUnmappedCredentials(SourceName sourceName,
                                                  String providerName,
                                                  Map<String, String> envVars) {
        if (!envVars.isEmpty()) {
            return;
        }

        System.err.printf("  WARN: source '%s' (provider %s) records no credential env-var mapping — its "
                         + "[source.%s] stanza declares no credentials = \"${env:NAME}\". Cleanup will fall back "
                         + "to the raw provider env var.%n",
                          sourceName,
                          providerName,
                          sourceName);
    }

    // RET-06: `rawToml` is raw operator TOML content; the null/empty coalesce is parse-boundary handling.
    @SuppressWarnings({"JBCT-PAT-01", "JBCT-RET-06"})
    static Map<String, String> extractEnvVarNames(String rawToml, String sourceName) {
        if (rawToml == null || rawToml.isEmpty()) {
            return Map.of();
        }

        var stanza = extractStanza(rawToml, sourceName);

        if (stanza.isEmpty()) {
            return Map.of();
        }

        var envName = matchCredentialEnvName(stanza);

        if (envName == null) {
            return Map.of();
        }

        var result = new LinkedHashMap<String, String>();

        for (var alias : CREDENTIAL_FIELD_KEYS) {
            result.put(alias, envName);
        }

        return Map.copyOf(result);
    }

    private static String matchCredentialEnvName(String stanza) {
        var pattern = Pattern.compile("(?m)^\\s*credentials\\s*=\\s*\"\\$\\{env:([A-Z_][A-Z0-9_]*)\\}\"");
        var matcher = pattern.matcher(stanza);

        return matcher.find()
               ? matcher.group(1)
               : null;
    }

    /// #521 — the stanza header is derived from the parser's own `SOURCE_PREFIX`, never re-spelled here.
    /// A local literal is exactly how this broke: the CLI mined a plural `[sources.<name>]` while the
    /// grammar is singular `[source.<name>]`, so it matched no real config and every persisted handle
    /// carried an empty `credentialEnvVars`, leaving destroy unable to re-derive the provisioning token.
    /// The header is anchored to the start of a line so a mention inside a comment cannot be mistaken for
    /// the section itself.
    private static String extractStanza(String rawToml, String sourceName) {
        var header = Pattern.compile("(?m)^[ \\t]*\\[" + Pattern.quote(ClusterBootstrapConfigParser.SOURCE_PREFIX + sourceName)
                                    + "][ \\t]*(#.*)?$");
        var matcher = header.matcher(rawToml);

        if (!matcher.find()) {
            return "";
        }

        var headerIndex = matcher.start();
        var after = rawToml.indexOf("\n[", matcher.end());

        return after < 0
               ? rawToml.substring(headerIndex)
               : rawToml.substring(headerIndex, after);
    }

    List<String> CREDENTIAL_FIELD_KEYS = List.of("api_token", "access_key", "credentials_file");

    static String resolveProviderName(SourceProfile source) {
        return source.provider()
                     .map(CloudProviderName::value)
                     .or(source.type().value());
    }

    private static String extractRole(String nodeId, String sourceName) {
        var suffix = nodeId.substring(sourceName.length() + 1);
        var dashIndex = suffix.lastIndexOf('-');

        return dashIndex > 0
               ? suffix.substring(0, dashIndex)
               : suffix;
    }

    @SuppressWarnings("JBCT-PAT-01")
    private static Result<List<ProvisionedNode>> provisionSource(BootstrapContext ctx,
                                                                 SourceName sourceName,
                                                                 SourceProfile source,
                                                                 int managementPort,
                                                                 ClusterName clusterName) {
        return switch (source.type()) {
            case CLOUD -> provisionCloudSource(ctx, sourceName, source, clusterName);
            case DOCKER -> provisionDockerSource(sourceName, source, clusterName);
            case SSH -> provisionSshSource(sourceName, source);
            case FORGE -> provisionForgeSource(sourceName, source, managementPort);
        };
    }

    @SuppressWarnings("JBCT-PAT-01")
    private static Result<List<ProvisionedNode>> provisionCloudSource(BootstrapContext ctx,
                                                                      SourceName sourceName,
                                                                      SourceProfile source,
                                                                      ClusterName clusterName) {
        var providerName = resolveProviderName(source);
        var sshKeyIds = ctx.sshKeyIdsFor(providerName);
        // Ids from BootstrapPhaseFirewall, applied AT create so the node is never up-and-unfirewalled
        // (§6.2 — a Hetzner server with no firewall association accepts all inbound traffic).
        var firewallIds = ctx.firewallIdsFor(sourceName);

        return ProviderResolver.resolveCloudCompute(source, sshKeyIds, "", clusterName, firewallIds).flatMap(compute -> provisionCloudWithCompute(compute,
                                                                                                                                                  ctx,
                                                                                                                                                  sourceName,
                                                                                                                                                  source,
                                                                                                                                                  clusterName));
    }

    @SuppressWarnings("JBCT-PAT-01")
    private static Result<List<ProvisionedNode>> provisionDockerSource(SourceName sourceName,
                                                                       SourceProfile source,
                                                                       ClusterName clusterName) {
        return ProviderResolver.resolveDockerCompute().flatMap(compute -> provisionWithCompute(compute,
                                                                                               sourceName,
                                                                                               source,
                                                                                               clusterName));
    }

    @SuppressWarnings({"JBCT-PAT-01", "JBCT-EX-01"})
    private static Result<List<ProvisionedNode>> provisionWithCompute(ComputeProvider compute,
                                                                      SourceName sourceName,
                                                                      SourceProfile source,
                                                                      ClusterName clusterName) {
        var allNodes = new ArrayList<ProvisionedNode>();
        var roleOrder = List.of(NodeRole.CORE, NodeRole.WORKER, NodeRole.SPOT);

        for (var role : roleOrder) {
            var roleTable = option(source.roles().get(role));
            var result = roleTable.flatMap(rt -> rt.count())
                                  .map(count -> provisionRoleGroup(compute, sourceName, role, count, source, clusterName));

            if (result.isPresent()) {
                var provisionResult = result.unwrap();

                if (provisionResult.isFailure()) {
                    return provisionResult;
                }

                var _ = provisionResult.onSuccess(allNodes::addAll);
            }
        }

        return success(List.copyOf(allNodes));
    }

    /// RFC-0017 stage 7 — for CLOUD sources bootstrap seeds the CORE quorum ONLY. Worker/spot
    /// entries are published in the topology at formation and provisioned by the CLUSTER (the
    /// stage-5 worker reconciler) once the leader activates — with LIVE core peers rather than
    /// seeds baked at create (no stale-seed failure mode), through ONE provisioning mechanism, and
    /// parallel-capable on large topologies. Bootstrap returns when the core quorum has formed;
    /// worker convergence is asynchronous and observable via the topology and provisioning
    /// diagnostics surfaces. SSH sources keep their fixed-host registration (no cloud API for the
    /// cluster to provision through) and DOCKER sources keep all roles (the integration harness
    /// creates its workers at bootstrap).
    List<NodeRole> CLOUD_BOOTSTRAP_ROLES = List.of(NodeRole.CORE);

    @SuppressWarnings({"JBCT-PAT-01", "JBCT-EX-01"})
    private static Result<List<ProvisionedNode>> provisionCloudWithCompute(ComputeProvider compute,
                                                                           BootstrapContext ctx,
                                                                           SourceName sourceName,
                                                                           SourceProfile source,
                                                                           ClusterName clusterName) {
        var allNodes = new ArrayList<ProvisionedNode>();
        var nodeIndex = 0;

        for (var role : CLOUD_BOOTSTRAP_ROLES) {
            var roleTable = option(source.roles().get(role));
            var count = roleTable.flatMap(rt -> rt.count()).or(0);

            if (count == 0) {
                continue;
            }

            var result = provisionCloudRoleGroup(compute, ctx, sourceName, role, count, source, clusterName, nodeIndex);

            if (result.isFailure()) {
                return result;
            }

            var _ = result.onSuccess(allNodes::addAll);

            nodeIndex += count;
        }

        return success(List.copyOf(allNodes));
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Result<List<ProvisionedNode>> provisionRoleGroup(ComputeProvider compute,
                                                                    SourceName sourceName,
                                                                    NodeRole role,
                                                                    int count,
                                                                    SourceProfile source,
                                                                    ClusterName clusterName) {
        logProvisionRole(sourceName, source.type(), role, Option.some(count));
        var instanceType = source.roles().containsKey(role)
                           ? source.roles().get(role).instanceType().or("default")
                           : "default";
        var zone = source.zone().or("default");
        var labels = Map.of("aether-cluster",
                            clusterName.value(),
                            "aether-source",
                            sourceName.value(),
                            "aether-role",
                            role.value());
        var group = NodeGroupConfig.nodeGroupConfig(sourceName, role.value(), count, instanceType, zone, labels);

        return CloudProviderSupport.provisionVia(compute, group).await();
    }

    /// #994 verification finding SF-2 — package-visible so a test can drive **the real call site**, not
    /// merely the composition it calls. The restructure into [#provisionAndRecordRoleGroup] closed the
    /// original gap one level down and opened it again here: with only that function pinned, replacing the
    /// call below with a direct [#rotateZonesForRoleGroup] — i.e. deleting the entire recording behaviour —
    /// left all 724 `aether/cli` tests green (measured, probe V1). #994 **was** an unwired mechanism:
    /// `buildUpdatedState` existed and worked and simply never ran on the failure path. A regression that
    /// re-unwires recording is the same defect class, so the wiring itself needs a pin rather than an
    /// argument that the delegation "carries no logic".
    @SuppressWarnings("JBCT-EX-01")
    static Result<List<ProvisionedNode>> provisionCloudRoleGroup(ComputeProvider compute,
                                                                 BootstrapContext ctx,
                                                                 SourceName sourceName,
                                                                 NodeRole role,
                                                                 int count,
                                                                 SourceProfile source,
                                                                 ClusterName clusterName,
                                                                 int nodeIndexBase) {
        logProvisionRole(sourceName, source.type(), role, Option.some(count));
        ZoneProvisioner provisionOne = (nodeId, globalIndex, zone) -> provisionOneInZone(compute,
                                                                                         ctx,
                                                                                         sourceName,
                                                                                         source,
                                                                                         role,
                                                                                         clusterName,
                                                                                         nodeId,
                                                                                         globalIndex,
                                                                                         zone);

        return provisionAndRecordRoleGroup(ctx.state().clusterName(),
                                           resolveProviderName(source),
                                           sourceName,
                                           role,
                                           count,
                                           nodeIndexBase,
                                           source.effectiveZones(),
                                           provisionOne);
    }

    /// #994 — the whole composition that must hold for a mid-group refusal to be survivable: zone rotation
    /// over `count` nodes, each attempt wrapped by [#recordingProvisioner] so a created VM reaches the
    /// persisted ledger before the group's outcome is known.
    ///
    /// It is one package-visible function rather than three lines inside [#provisionCloudRoleGroup] because
    /// of what that cost: with the composition assembled inline, a test could exercise the aspect and the
    /// recorder separately while the LINE THAT WIRES THEM TOGETHER stayed uncovered — measured, not assumed,
    /// by deleting the wrapper at the old call site and watching all 724 `aether/cli` tests pass. Driving
    /// this function instead leaves only an argument-passing delegation untested.
    static Result<List<ProvisionedNode>> provisionAndRecordRoleGroup(ClusterName clusterName,
                                                                     String providerName,
                                                                     SourceName sourceName,
                                                                     NodeRole role,
                                                                     int count,
                                                                     int nodeIndexBase,
                                                                     List<String> zones,
                                                                     ZoneProvisioner provisionOne) {
        var seam = recordingProvisioner(provisionOne,
                                        node -> recordProvisionedVm(clusterName, providerName, sourceName, role, node));

        return rotateZonesForRoleGroup(sourceName, role, count, nodeIndexBase, zones, seam);
    }

    /// Provisions one node into a SPECIFIC zone: builds the spec without placement, applies
    /// the candidate `zone` (empty string → provider default / no placement hint), then
    /// provisions. Returned as a blocking `Result` because the bootstrap phase is synchronous.
    @SuppressWarnings("JBCT-EX-01")
    private static Result<ProvisionedNode> provisionOneInZone(ComputeProvider compute,
                                                              BootstrapContext ctx,
                                                              SourceName sourceName,
                                                              SourceProfile source,
                                                              NodeRole role,
                                                              ClusterName clusterName,
                                                              String nodeId,
                                                              int globalIndex,
                                                              String zone) {
        return buildCloudProvisionSpec(ctx, sourceName, source, role, nodeId, globalIndex, clusterName).map(spec -> applyZone(spec,
                                                                                                                              zone))
                                      .flatMap(spec -> CloudProviderSupport.provisionOne(compute, nodeId, spec).await());
    }

    /// Serial per-role-group zone rotation with a cursor shared across the group's nodes:
    /// once a working zone is found, subsequent nodes start there (known-full zones are not
    /// re-tried for every node). Capacity-unavailable advances the cursor; any other failure
    /// aborts immediately (non-retryable); cursor exhaustion fails with a clear message.
    /// An empty zone list means "single attempt, provider default" (backward-compatible).
    @SuppressWarnings({"JBCT-EX-01", "JBCT-PAT-01"})
    static Result<List<ProvisionedNode>> rotateZonesForRoleGroup(SourceName sourceName,
                                                                 NodeRole role,
                                                                 int count,
                                                                 int nodeIndexBase,
                                                                 List<String> zones,
                                                                 ZoneProvisioner seam) {
        var nodes = new ArrayList<ProvisionedNode>();
        var cursor = new int[]{0};

        for (int i = 0; i < count; i++) {
            var nodeId = sourceName.value() + "-" + role.value() + "-" + i;
            var globalIndex = nodeIndexBase + i;
            var attempt = provisionWithRotation(sourceName, nodeId, globalIndex, zones, cursor, seam);

            if (attempt.isFailure()) {
                return attempt.map(_ -> List.<ProvisionedNode> of());
            }

            var _ = attempt.onSuccess(nodes::add);
        }

        return success(List.copyOf(nodes));
    }

    /// Provisions a single node, rotating from the shared cursor across the candidate zones.
    /// Advances the cursor past capacity-exhausted zones (so the next node skips them) and
    /// leaves it pointing at the zone that succeeded.
    @SuppressWarnings({"JBCT-EX-01", "JBCT-PAT-01"})
    private static Result<ProvisionedNode> provisionWithRotation(SourceName sourceName,
                                                                 String nodeId,
                                                                 int globalIndex,
                                                                 List<String> zones,
                                                                 int[] cursor,
                                                                 ZoneProvisioner seam) {
        if (zones.isEmpty()) {
            return seam.provisionInZone(nodeId, globalIndex, "");
        }

        while (cursor[0]< zones.size()) {
            var zone = zones.get(cursor[0]);
            var attempt = seam.provisionInZone(nodeId, globalIndex, zone);

            if (attempt.isSuccess()) {
                return attempt;
            }

            if (!isCapacityUnavailable(attempt)) {
                return attempt;
            }

            logZoneRotation(nodeId, zone, nextZoneLabel(zones, cursor[0]));
            cursor[0]++;
        }

        return zonesExhausted(sourceName, nodeId, zones);
    }

    private static boolean isCapacityUnavailable(Result<ProvisionedNode> attempt) {
        return attempt.fold(BootstrapPhaseProvision::isCapacityCause, _ -> false);
    }

    private static boolean isCapacityCause(Cause cause) {
        return cause instanceof EnvironmentError.CapacityUnavailable;
    }

    private static String nextZoneLabel(List<String> zones, int currentIndex) {
        var next = currentIndex + 1;

        return next < zones.size()
               ? zones.get(next)
               : "(no more zones)";
    }

    @Contract
    private static void logZoneRotation(String nodeId, String fromZone, String toZone) {
        System.out.printf("  WARN: zone %s capacity-unavailable for %s, retrying in %s%n", fromZone, nodeId, toZone);
    }

    private static Result<ProvisionedNode> zonesExhausted(SourceName sourceName, String nodeId, List<String> zones) {
        return new ZoneRotationError("all configured zones exhausted for source " + sourceName
                                    + " (node " + nodeId
                                    + "): " + String.join(", ", zones)).result();
    }

    record ZoneRotationError(String message) implements Cause {}

    interface ZoneProvisioner {
        Result<ProvisionedNode> provisionInZone(String nodeId, int globalIndex, String zone);
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Result<ProvisionSpec> buildCloudProvisionSpec(BootstrapContext ctx,
                                                                 SourceName sourceName,
                                                                 SourceProfile source,
                                                                 NodeRole role,
                                                                 String nodeId,
                                                                 int nodeIndex,
                                                                 ClusterName clusterName) {
        var instanceType = source.roles().containsKey(role)
                           ? source.roles().get(role).instanceType().or("default")
                           : "default";
        var roleImage = roleImage(source, role);
        var context = ProvisionContext.forBootstrap(clusterName, role.value(), sourceName, nodeId);

        return NodeConfigBuilder.compose(ctx,
                                         source,
                                         nodeIndex,
                                         role,
                                         Option.empty(),
                                         Option.some(ctx.clusterSecret()))
                                .map(composedConfig -> renderUserData(ctx,
                                                                      source,
                                                                      role,
                                                                      nodeId,
                                                                      nodeIndex,
                                                                      clusterName,
                                                                      composedConfig))
                                .flatMap(userData -> ProvisionSpec.provisionSpec(InstanceType.ON_DEMAND,
                                                                                 instanceType,
                                                                                 role.value(),
                                                                                 context)
                                                                  .map(spec -> spec.withUserData(userData))
                                                                  .map(spec -> applyImage(spec, roleImage)));
    }

    /// RFC-0016 W2 — tier-1 per-role image: applies the role's OWN `image` (VM boot image / snapshot
    /// id) to the spec's `imageId` when present, so `ProvisionRequest.resolve` boots the node from the
    /// operator's prepared snapshot for THAT role. Absent → the spec carries no `imageId` and
    /// resolution falls to tier-2 (`[cloud.compute] image`) then the loud stock default. NEVER applies
    /// an empty image, and never a sibling role's image (no cross-role fallback).
    private static ProvisionSpec applyImage(ProvisionSpec spec, Option<String> image) {
        return image.map(spec::withImage)
                    .or(spec);
    }

    private static Option<String> roleImage(SourceProfile source, NodeRole role) {
        return option(source.roles().get(role)).flatMap(RoleSubTable::image);
    }

    private static String renderUserData(BootstrapContext ctx,
                                         SourceProfile source,
                                         NodeRole role,
                                         String nodeId,
                                         int nodeIndex,
                                         ClusterName clusterName,
                                         TomlDocument composedConfig) {
        return UserDataTemplate.render(ctx.config(),
                                       source,
                                       role,
                                       nodeId,
                                       nodeIndex,
                                       ctx.clusterSecret(),
                                       clusterName,
                                       composedConfig,
                                       ctx.sshPublicKeys(),
                                       List.of());
    }

    /// Applies a single candidate zone to a built spec as a placement hint. An empty or
    /// "default" zone means "no placement" — the provider falls back to its default region.
    private static ProvisionSpec applyZone(ProvisionSpec spec, String zone) {
        return zone.isEmpty() || "default".equals(zone)
               ? spec
               : spec.withPlacement(PlacementHint.zoneHint(zone));
    }

    @SuppressWarnings("JBCT-PAT-01")
    private static Result<List<ProvisionedNode>> provisionSshSource(SourceName sourceName, SourceProfile source) {
        var nodes = new ArrayList<ProvisionedNode>();

        for (var entry : source.roles().entrySet()) {
            var role = entry.getKey();

            entry.getValue().hosts().onPresent(hosts -> addSshNodes(nodes, sourceName, role, hosts));
        }

        logProvisionRole(sourceName,
                         source.type(),
                         NodeRole.CORE,
                         Option.some(nodes.size()));

        return success(List.copyOf(nodes));
    }

    @Contract
    private static void addSshNodes(List<ProvisionedNode> nodes,
                                    SourceName sourceName,
                                    NodeRole role,
                                    List<String> hosts) {
        for (int i = 0; i < hosts.size(); i++) {
            var nodeId = sourceName.value() + "-" + role.value() + "-" + i;

            nodes.add(ProvisionedNode.provisionedNode(nodeId, "ssh", hosts.get(i)));
        }
    }

    @SuppressWarnings("JBCT-PAT-01")
    private static Result<List<ProvisionedNode>> provisionForgeSource(SourceName sourceName,
                                                                      SourceProfile source,
                                                                      int managementPort) {
        System.out.println("  Forge source: nodes are virtual (in-process via EmberCluster)");
        System.out.println("  Start the forge binary separately: aether forge --config <forge.toml>");
        var nodes = new ArrayList<ProvisionedNode>();
        var counter = 0;
        var roleOrder = List.of(NodeRole.CORE, NodeRole.WORKER, NodeRole.SPOT);

        for (var role : roleOrder) {
            var count = option(source.roles().get(role)).flatMap(rt -> rt.count()).or(0);

            for (int i = 0; i < count; i++) {
                var nodeId = sourceName.value() + "-" + role.value() + "-" + i;
                var nodePort = managementPort + counter;

                nodes.add(ProvisionedNode.provisionedNode(nodeId, "forge", "127.0.0.1"));
                counter++;
            }

            if (count > 0) {
                logProvisionRole(sourceName, source.type(), role, Option.some(count));
            }
        }

        return success(List.copyOf(nodes));
    }

    @Contract
    private static void logProvisionRole(SourceName sourceName, SourceType type, NodeRole role, Option<Integer> count) {
        count.onPresent(c -> System.out.printf("  [%s/%s] %s: provisioning %d node(s)%n",
                                               sourceName,
                                               type.value(),
                                               role.value(),
                                               c));
    }
}
