// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource;

import java.util.List;

import org.pragmatica.aether.slice.ReplicationDeclaration;
import org.pragmatica.aether.slice.ReplicationFactors;
import org.pragmatica.aether.slice.ReplicationWarning;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.parse.TimeSpan;


/// Resolved stream parameters of a DURABLE topic (durable-pubsub-spec §3) — the durable tier's
/// knobs with declaration defaults applied, valid by construction.
///
/// `replication` is the topic's [ReplicationFactors], resolved from its `replication_factor` and
/// `confirmation_factor` against the committed cluster defaults through [ReplicationDeclaration] (#1564; the
/// owner ruling, know 267792392, superseded the fixed `confirmation == replication` of know 8fb91f876 — `CF < RF`
/// is lossless through #1555's promotion gate). `replicationWarnings` are the warnings that declaration raised.
/// The topic's dead-letter stream inherits both factors.
///
/// A retention under 1 ms (`0s`, `500us`) is refused here too (#1549): the topic's stream holds its
/// retention in whole milliseconds and refuses a bound below 1 at creation, so accepting it would fail
/// at the first publish instead of at declaration.
public record DurableTopicSpec(int partitions,
                               ReplicationFactors replication,
                               TimeSpan retention,
                               List<ReplicationWarning> replicationWarnings) {
    public static final int DEFAULT_PARTITIONS = 1;
    public static final TimeSpan DEFAULT_RETENTION = TimeSpan.timeSpan("7d").unwrap();

    public DurableTopicSpec {
        replicationWarnings = List.copyOf(replicationWarnings);
    }

    public static Result<DurableTopicSpec> durableTopicSpec(int partitions,
                                                            ReplicationDeclaration.Resolved replication,
                                                            TimeSpan retention) {
        return checkKnobs(partitions, retention).map(_ -> new DurableTopicSpec(partitions,
                                                                               replication.factors(),
                                                                               retention,
                                                                               replication.warnings()));
    }

    /// The knobs a declaration decides on its own — checked at bind, before the factors can be resolved.
    public static Result<Unit> checkKnobs(int partitions, TimeSpan retention) {
        if (partitions < 1) {
            return TopicConfigError.invalidPartitions(partitions).result();
        }

        if (retention.toMillis() < 1) {
            return TopicConfigError.retentionBelowOneMillisecond(retention).result();
        }

        return Result.unitResult();
    }
}
