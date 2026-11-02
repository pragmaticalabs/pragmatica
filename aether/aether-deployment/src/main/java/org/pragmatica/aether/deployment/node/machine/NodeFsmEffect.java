// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.machine;

import java.util.List;
import java.util.Map;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.lang.Option;


/// What the node FSM asks its interpreter to do (spec R-3.1.2). Effects are data; completions come back as inputs
/// (`WriteSettled`, `LocalOpSettled`, `TimerFired`).
public sealed interface NodeFsmEffect {
    /// The node is ready: signalled first on every Active entry, before any slice recovery (R-3.4.1 step 1, L-07).
    record SelfReady() implements NodeFsmEffect {}

    /// Submit one batch through consensus. The batch holds at most one observed Put (the only fenced command, R-3.4.9 (4)).
    /// `correlation` comes back in `WriteSettled`; `attempt` counts from 1. `probe` is 0 for an attempt's own submission and the
    /// 1-based probe index for a write probe's resubmission, which carries the attempt's number (spec R-3.4.4, O1; the field is
    /// a guess of shape: the spec names none).
    record Submit(long incarnation,
                  Artifact artifact,
                  long correlation,
                  int attempt,
                  int probe,
                  List<KVCommand<AetherKey>> commands) implements NodeFsmEffect {
        public Submit {
            commands = List.copyOf(commands);
        }

        /// An attempt's own submission.
        public Submit(long incarnation,
                      Artifact artifact,
                      long correlation,
                      int attempt,
                      List<KVCommand<AetherKey>> commands) {
            this(incarnation, artifact, correlation, attempt, 0, commands);
        }

        public boolean isProbe() {
            return probe > 0;
        }
    }

    /// Route a deployment event to the cluster event stream.
    record Route(RoutedEvent event) implements NodeFsmEffect {}

    /// Raise an operator warning (`code` is the kebab code operators match on).
    record Warn(String code, String subject, String message) implements NodeFsmEffect {}

    /// Close the warning `code` raised for `subject` (the recovery event, same subject).
    record WarnResolved(String code, String subject, String message) implements NodeFsmEffect {}

    /// A log line.
    record Log(Level level, String message) implements NodeFsmEffect {}

    /// Arm a timer; the interpreter delivers `TimerFired(timer)` after `delayMs`.
    record Schedule(TimerId timer, long delayMs) implements NodeFsmEffect {}

    /// Disarm a timer. A timer that fires anyway is dropped by its token.
    record Cancel(TimerId timer) implements NodeFsmEffect {}

    /// Add `delta` to the counter `name` with `labels` (R-1.3.1).
    record Metric(String name, Map<String, String> labels, long delta) implements NodeFsmEffect {
        public Metric {
            labels = Map.copyOf(labels);
        }
    }

    /// Run one local step. `token` comes back in `LocalOpSettled`; token 0 means fire and forget: no completion is
    /// expected and none is delivered. `timeoutMs` bounds the step; the interpreter reports `TimedOut` past it.
    record LocalOp(long incarnation,
                   long token,
                   Option<Artifact> artifact,
                   LocalStep step,
                   long timeoutMs,
                   Option<String> argument) implements NodeFsmEffect {}

    /// Log levels the FSM uses. `CRITICAL` is the final line of an exhausted FAILED-write retry (N19).
    enum Level {
        DEBUG,
        INFO,
        WARN,
        ERROR,
        CRITICAL
    }

    /// A deployment event for the cluster event stream, in the observed vocabulary (no ROUTING, with UNLOADED).
    sealed interface RoutedEvent {
        /// An own observed row moved from `from` to `to` (B-118). `from` equals `to` for a first row.
        record StateTransitioned(Artifact artifact, SliceObservedState from, SliceObservedState to, long timestamp) implements RoutedEvent {}

        /// An own observed row says FAILED, with its real reason (N15).
        record DeploymentFailed(Artifact artifact, SliceObservedState failedAt, String reason, long timestamp) implements RoutedEvent {}

        /// The instance entered ACTIVE (N10): routed once per entry.
        record DeploymentCompleted(Artifact artifact, long timestamp) implements RoutedEvent {}
    }

    static NodeFsmEffect selfReady() {
        return new SelfReady();
    }
}
