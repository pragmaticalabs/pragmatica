// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment;

import java.util.List;
import java.util.Map;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;


/// Describes a compute instance as observed or returned by a [ComputeProvider].
///
/// [#nodeId] carries the canonical Aether node id the PROVIDER actually used for
/// this instance — Docker maps it to the container name, cloud providers embed it
/// as the `aether-node-id` native tag/label. It is a `String` (not a `NodeId`) to
/// keep the `environment-integration` module a leaf and to mirror
/// [ProvisionContext#nodeId]. It may be [Option#none] for status-only observations
/// where the provider has no node-id signal (e.g. Docker `inspect`).
public record InstanceInfo(InstanceId id,
                           InstanceStatus status,
                           List<String> addresses,
                           InstanceType type,
                           Map<String, String> tags,
                           Option<String> nodeId,
                           Option<String> observedZone) {
    public InstanceInfo {
        observedZone = Option.option(observedZone).or(Option.none()).filter(zone -> !zone.isBlank());
        if (nodeId == null) {
            nodeId = Option.none();
        }
    }

    /// Return a copy with the status replaced. Used by [ComputeProvider#confirmRunning]
    /// to re-stamp a provision result to RUNNING once infra readiness is confirmed.
    public InstanceInfo withStatus(InstanceStatus newStatus) {
        return new InstanceInfo(id, newStatus, addresses, type, tags, nodeId, observedZone);
    }

    /// Provider-observed location, never copied from provisioning intent or user labels.
    public InstanceInfo withObservedZone(Option<String> zone) {
        return new InstanceInfo(id, status, addresses, type, tags, nodeId, zone);
    }

    public InstanceInfo(InstanceId id,
                        InstanceStatus status,
                        List<String> addresses,
                        InstanceType type,
                        Map<String, String> tags) {
        this(id, status, addresses, type, tags, Option.none(), Option.none());
    }

    public static Result<InstanceInfo> instanceInfo(InstanceId id,
                                                    InstanceStatus status,
                                                    List<String> addresses,
                                                    InstanceType type,
                                                    Map<String, String> tags) {
        return instanceInfo(id, status, addresses, type, tags, Option.none());
    }

    public static Result<InstanceInfo> instanceInfo(InstanceId id,
                                                    InstanceStatus status,
                                                    List<String> addresses,
                                                    InstanceType type,
                                                    Map<String, String> tags,
                                                    Option<String> nodeId) {
        return success(new InstanceInfo(id,
                                        status,
                                        List.copyOf(addresses),
                                        type,
                                        Map.copyOf(tags),
                                        nodeId,
                                        org.pragmatica.lang.Option.none()));
    }

    public static Result<InstanceInfo> instanceInfo(InstanceId id,
                                                    InstanceStatus status,
                                                    List<String> addresses,
                                                    InstanceType type) {
        return instanceInfo(id, status, addresses, type, Map.of());
    }
}
