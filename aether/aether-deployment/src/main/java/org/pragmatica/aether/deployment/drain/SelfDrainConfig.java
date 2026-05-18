// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.drain;

import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Configuration for the node-side self-drain protocol.
///
/// `triggerThreshold` — sustained-below-quorum duration that must elapse before the
/// periodic 1Hz check trips the drain (immediate triggers from
/// QuorumStateNotification.DISAPPEARED and RabiaEngine.Paused bypass this debounce).
///
/// `inflightGrace` — maximum time the drain coordinator waits for the in-flight request
/// tracker to drop to zero after `setAcceptingNewWork(false)`. When the grace expires,
/// the drain proceeds to `Runtime.halt(2)` regardless.
///
/// Defaults match membership-architecture-spec.md §16.1: 8s triggerThreshold, 30s
/// inflightGrace.
public record SelfDrainConfig(TimeSpan triggerThreshold, TimeSpan inflightGrace) {
    public static SelfDrainConfig selfDrainConfig() {
        return new SelfDrainConfig(timeSpan(8).seconds(), timeSpan(30).seconds());
    }
}
