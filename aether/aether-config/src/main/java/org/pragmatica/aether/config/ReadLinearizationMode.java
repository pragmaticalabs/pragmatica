// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config;


/// The `LINEARIZABLE`-read mechanism (spec §8.1, durable-entity primitive) — an ops knob, not
/// caller-visible: swapping it never changes what callers may assume, only the cost/assumption
/// profile of a linearizable read. Parsed from `[durable-entity] read-linearization`.
///
/// Only [#NO_OP_ROUND] ships in this increment (#345 item 1e-a): the read is ordered through a no-op
/// consensus round and served after the local apply reaches it — no clock assumptions, correct on
/// Rabia by construction. The spec's `lease` mechanism (owner serves under a time-bounded lease) is
/// future work and is rejected at config parse until its clock-skew chaos validation gate is green.
public enum ReadLinearizationMode {
    NO_OP_ROUND
}
