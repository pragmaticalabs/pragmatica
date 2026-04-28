// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.controller.fsm;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;


public final class ControlLoopEvents {
    private ControlLoopEvents() {}

    public record Activate() implements ClusterFsmEvent{}

    public record Deactivate() implements ClusterFsmEvent{}

    public record ActivationTimeReached() implements ClusterFsmEvent{}

    public record CooldownRequested(Artifact artifact, long cooldownStartMs) implements ClusterFsmEvent{}

    public record CooldownExpired() implements ClusterFsmEvent{}
}
