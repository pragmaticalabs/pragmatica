// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.pragmatica.lang.Result;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.pragmatica.lang.Result.success;


@SuppressWarnings("JBCT-SEQ-01") public record BootstrapState(String clusterName,
                                                              String configHash,
                                                              String startedAt,
                                                              Map<BootstrapPhase, PhaseStatus> phases,
                                                              List<CreatedResource> createdResources,
                                                              List<String> provisionedNodeIds,
                                                              List<String> collectedAddresses,
                                                              String clusterSecret) {
    @SuppressWarnings("JBCT-VO-02") public static BootstrapState bootstrapState(String clusterName,
                                                                                String configHash,
                                                                                String startedAt,
                                                                                Map<BootstrapPhase, PhaseStatus> phases,
                                                                                List<CreatedResource> createdResources,
                                                                                List<String> provisionedNodeIds,
                                                                                List<String> collectedAddresses,
                                                                                String clusterSecret) {
        return new BootstrapState(clusterName,
                                  configHash,
                                  startedAt,
                                  Map.copyOf(phases),
                                  List.copyOf(createdResources),
                                  List.copyOf(provisionedNodeIds),
                                  List.copyOf(collectedAddresses),
                                  clusterSecret);
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
                              "");
    }

    public static BootstrapState initialState(String clusterName, String configHash, String startedAt) {
        var phases = new EnumMap<BootstrapPhase, PhaseStatus>(BootstrapPhase.class);
        for (var phase : BootstrapPhase.values()) {phases.put(phase, PhaseStatus.PENDING);}
        return bootstrapState(clusterName, configHash, startedAt, phases, List.of(), List.of(), List.of(), "");
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
                              clusterSecret);
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
                              clusterSecret);
    }

    public BootstrapState withProvisionedNodeIds(List<String> ids) {
        return bootstrapState(clusterName,
                              configHash,
                              startedAt,
                              phases,
                              createdResources,
                              ids,
                              collectedAddresses,
                              clusterSecret);
    }

    public BootstrapState withCollectedAddresses(List<String> addrs) {
        return bootstrapState(clusterName,
                              configHash,
                              startedAt,
                              phases,
                              createdResources,
                              provisionedNodeIds,
                              addrs,
                              clusterSecret);
    }

    public BootstrapState withClusterSecret(String secret) {
        return bootstrapState(clusterName,
                              configHash,
                              startedAt,
                              phases,
                              createdResources,
                              provisionedNodeIds,
                              collectedAddresses,
                              secret);
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
