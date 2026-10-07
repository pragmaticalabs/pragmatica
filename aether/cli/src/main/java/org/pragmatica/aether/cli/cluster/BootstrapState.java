// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.lang.Result;


@SuppressWarnings("JBCT-SEQ-01")
public record BootstrapState(ClusterName clusterName,
                             String configHash,
                             String startedAt,
                             Map<BootstrapPhase, PhaseStatus> phases,
                             List<CreatedResource> createdResources,
                             List<String> provisionedNodeIds,
                             List<String> collectedAddresses,
                             String clusterSecret,
                             Map<String, SourceCleanupHandle> sources,
                             List<String> startedNodeIds) {
    public BootstrapState {
        startedNodeIds = List.copyOf(startedNodeIds);
        phases = Map.copyOf(phases);
        createdResources = List.copyOf(createdResources);
        provisionedNodeIds = List.copyOf(provisionedNodeIds);
        collectedAddresses = List.copyOf(collectedAddresses);
        sources = Map.copyOf(sources);
    }

    @SuppressWarnings("JBCT-VO-02")
    public static BootstrapState bootstrapState(ClusterName clusterName,
                                                String configHash,
                                                String startedAt,
                                                Map<BootstrapPhase, PhaseStatus> phases,
                                                List<CreatedResource> createdResources,
                                                List<String> provisionedNodeIds,
                                                List<String> collectedAddresses,
                                                String clusterSecret,
                                                Map<String, SourceCleanupHandle> sources) {
        return new BootstrapState(clusterName,
                                  configHash,
                                  startedAt,
                                  phases,
                                  createdResources,
                                  provisionedNodeIds,
                                  collectedAddresses,
                                  clusterSecret,
                                  sources,
                                  List.of());
    }

    @SuppressWarnings("JBCT-VO-02")
    public static BootstrapState bootstrapState(ClusterName clusterName,
                                                String configHash,
                                                String startedAt,
                                                Map<BootstrapPhase, PhaseStatus> phases,
                                                List<CreatedResource> createdResources,
                                                List<String> provisionedNodeIds,
                                                List<String> collectedAddresses,
                                                String clusterSecret) {
        return bootstrapState(clusterName,
                              configHash,
                              startedAt,
                              phases,
                              createdResources,
                              provisionedNodeIds,
                              collectedAddresses,
                              clusterSecret,
                              Map.of());
    }

    @SuppressWarnings("JBCT-VO-02")
    public static BootstrapState bootstrapState(ClusterName clusterName,
                                                String configHash,
                                                String startedAt,
                                                Map<BootstrapPhase, PhaseStatus> phases,
                                                List<CreatedResource> createdResources,
                                                List<String> provisionedNodeIds,
                                                List<String> collectedAddresses) {
        return bootstrapState(clusterName,
                              configHash,
                              startedAt,
                              phases,
                              createdResources,
                              provisionedNodeIds,
                              collectedAddresses,
                              "",
                              Map.of());
    }

    public static BootstrapState initialState(ClusterName clusterName, String configHash, String startedAt) {
        var phases = new EnumMap<BootstrapPhase, PhaseStatus>(BootstrapPhase.class);

        for (var phase : BootstrapPhase.values()) {
            phases.put(phase, PhaseStatus.PENDING);
        }

        return bootstrapState(clusterName, configHash, startedAt, phases, List.of(), List.of(), List.of(), "", Map.of());
    }

    public BootstrapState withPhaseStatus(BootstrapPhase phase, PhaseStatus status) {
        var updated = new EnumMap<>(phases);

        updated.put(phase, status);

        return new BootstrapState(clusterName,
                                  configHash,
                                  startedAt,
                                  updated,
                                  createdResources,
                                  provisionedNodeIds,
                                  collectedAddresses,
                                  clusterSecret,
                                  sources,
                                  startedNodeIds);
    }

    public BootstrapState withResource(CreatedResource resource) {
        var updated = new ArrayList<>(createdResources);

        updated.add(resource);

        return new BootstrapState(clusterName,
                                  configHash,
                                  startedAt,
                                  phases,
                                  updated,
                                  provisionedNodeIds,
                                  collectedAddresses,
                                  clusterSecret,
                                  sources,
                                  startedNodeIds);
    }

    public BootstrapState withProvisionedNodeIds(List<String> ids) {
        return new BootstrapState(clusterName,
                                  configHash,
                                  startedAt,
                                  phases,
                                  createdResources,
                                  ids,
                                  collectedAddresses,
                                  clusterSecret,
                                  sources,
                                  startedNodeIds);
    }

    public BootstrapState withCollectedAddresses(List<String> addrs) {
        return new BootstrapState(clusterName,
                                  configHash,
                                  startedAt,
                                  phases,
                                  createdResources,
                                  provisionedNodeIds,
                                  addrs,
                                  clusterSecret,
                                  sources,
                                  startedNodeIds);
    }

    public BootstrapState withClusterSecret(String secret) {
        return new BootstrapState(clusterName,
                                  configHash,
                                  startedAt,
                                  phases,
                                  createdResources,
                                  provisionedNodeIds,
                                  collectedAddresses,
                                  secret,
                                  sources,
                                  startedNodeIds);
    }

    /// #1543 — the per-node "started" ledger: ids whose node process this CLI has launched. `--resume`
    /// consults it so a node is never launched twice under one id.
    public BootstrapState withStartedNodeId(String nodeId) {
        var updated = new ArrayList<>(startedNodeIds);

        updated.add(nodeId);

        return withStartedNodeIds(updated);
    }

    public BootstrapState withStartedNodeIds(List<String> ids) {
        return new BootstrapState(clusterName,
                                  configHash,
                                  startedAt,
                                  phases,
                                  createdResources,
                                  provisionedNodeIds,
                                  collectedAddresses,
                                  clusterSecret,
                                  sources,
                                  ids);
    }

    public BootstrapState withSources(Map<String, SourceCleanupHandle> newSources) {
        return new BootstrapState(clusterName,
                                  configHash,
                                  startedAt,
                                  phases,
                                  createdResources,
                                  provisionedNodeIds,
                                  collectedAddresses,
                                  clusterSecret,
                                  newSources,
                                  startedNodeIds);
    }

    public BootstrapState withSource(String sourceName, SourceCleanupHandle handle) {
        var merged = new HashMap<String, SourceCleanupHandle>(sources);

        merged.put(sourceName, handle);

        return withSources(merged);
    }

    public String toJson() {
        return BootstrapStateJson.toJson(this);
    }

    public static Result<BootstrapState> fromJson(String json) {
        return BootstrapStateJson.fromJson(json);
    }

    public enum PhaseStatus {
        PENDING,
        IN_PROGRESS,
        COMPLETED,
        FAILED
    }
}
