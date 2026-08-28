// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource;

/// Declared durability class of a pub/sub topic (durable-pubsub-spec §3).
///
/// The class is a *declaration*, not a runtime mode switch: it selects which delivery substrate
/// backs the topic, and (once D5 lands) which publisher shape the slice-processor generates.
///
/// - [#EPHEMERAL] — today's RPC fan-out: dispatch is attempted to currently-registered subscriber
///   groups, nothing is persisted or queued, delivery is at-most-once per group. The default, so
///   the zero-config path stays cheap and durability is an explicit, costed choice.
/// - [#DURABLE] — the topic is backed by a replicated stream (`min-sync == replicas >= 2`,
///   spec §3 v1 constraint): `publish` resolves at the replication floor, consumer groups read
///   through durable cursors at-least-once, exhausted redeliveries land in the topic's DLQ stream.
public enum TopicDurability {
    EPHEMERAL,
    DURABLE
}
