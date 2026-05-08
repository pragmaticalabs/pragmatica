// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.pragmatica.lang.Result;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


@SuppressWarnings("JBCT-SEQ-01") public record BootstrapState(String clusterName,
                                                              String configHash,
                                                              String startedAt,
                                                              Map<BootstrapPhase, PhaseStatus> phases,
                                                              List<CreatedResource> createdResources,
                                                              List<String> provisionedNodeIds,
                                                              List<String> collectedAddresses,
                                                              String clusterSecret,
                                                              Map<String, SourceCleanupHandle> sources) {
    public BootstrapState {
        phases = Map.copyOf(phases);
        createdResources = List.copyOf(createdResources);
        provisionedNodeIds = List.copyOf(provisionedNodeIds);
        collectedAddresses = List.copyOf(collectedAddresses);
        sources = Map.copyOf(sources);
    }

    @SuppressWarnings("JBCT-VO-02") public static BootstrapState bootstrapState(String clusterName,
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
                                  sources);
    }

    @SuppressWarnings("JBCT-VO-02") public static BootstrapState bootstrapState(String clusterName,
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

    @SuppressWarnings("JBCT-VO-02") public static BootstrapState bootstrapState(String clusterName,
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

    public static BootstrapState initialState(String clusterName, String configHash, String startedAt) {
        var phases = new EnumMap<BootstrapPhase, PhaseStatus>(BootstrapPhase.class);
        for (var phase : BootstrapPhase.values()) {phases.put(phase, PhaseStatus.PENDING);}
        return bootstrapState(clusterName, configHash, startedAt, phases, List.of(), List.of(), List.of(), "", Map.of());
    }

    public BootstrapState withPhaseStatus(BootstrapPhase phase, PhaseStatus status) {
        var updated = new EnumMap<>(phases);
        updated.put(phase, status);
        return bootstrapState(clusterName,
                              configHash,
                              startedAt,
                              updated,
                              createdResources,
                              provisionedNodeIds,
                              collectedAddresses,
                              clusterSecret,
                              sources);
    }

    public BootstrapState withResource(CreatedResource resource) {
        var updated = new ArrayList<>(createdResources);
        updated.add(resource);
        return bootstrapState(clusterName,
                              configHash,
                              startedAt,
                              phases,
                              updated,
                              provisionedNodeIds,
                              collectedAddresses,
                              clusterSecret,
                              sources);
    }

    public BootstrapState withProvisionedNodeIds(List<String> ids) {
        return bootstrapState(clusterName,
                              configHash,
                              startedAt,
                              phases,
                              createdResources,
                              ids,
                              collectedAddresses,
                              clusterSecret,
                              sources);
    }

    public BootstrapState withCollectedAddresses(List<String> addrs) {
        return bootstrapState(clusterName,
                              configHash,
                              startedAt,
                              phases,
                              createdResources,
                              provisionedNodeIds,
                              addrs,
                              clusterSecret,
                              sources);
    }

    public BootstrapState withClusterSecret(String secret) {
        return bootstrapState(clusterName,
                              configHash,
                              startedAt,
                              phases,
                              createdResources,
                              provisionedNodeIds,
                              collectedAddresses,
                              secret,
                              sources);
    }

    public BootstrapState withSources(Map<String, SourceCleanupHandle> newSources) {
        return bootstrapState(clusterName,
                              configHash,
                              startedAt,
                              phases,
                              createdResources,
                              provisionedNodeIds,
                              collectedAddresses,
                              clusterSecret,
                              newSources);
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
