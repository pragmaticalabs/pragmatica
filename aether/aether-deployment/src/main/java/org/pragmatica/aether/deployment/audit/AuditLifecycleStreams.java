// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.audit;

import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamCompression;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.lang.Option;


/// Stream configuration constants for the lifecycle-audit topic.
///
/// The deployment module is not a `@Slice`, so the topic registration cannot ride through
/// the slice-DI `resources.toml` -> `StreamPublisherFactory.provision(...)` path. Instead,
/// the node-boot code (`AetherNode.start()`) reads the constant defined here, calls
/// `StreamPartitionManager.createStream(...)`, and builds a `DefaultStreamPublisher` bound
/// to it.
public sealed interface AuditLifecycleStreams {
    String TOPIC_AUDIT_LIFECYCLE_COMMANDS = "audit.lifecycle.commands";
    int AUDIT_PARTITIONS = 4;
    long AUDIT_MAX_EVENT_SIZE_BYTES = 16L * 1024L;
    long AUDIT_RETENTION_MAX_COUNT = 50_000L;
    long AUDIT_RETENTION_MAX_BYTES = 8L * 1024L * 1024L;
    long AUDIT_RETENTION_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L;

    StreamConfig AUDIT_LIFECYCLE_COMMANDS = StreamConfig.streamConfig(TOPIC_AUDIT_LIFECYCLE_COMMANDS,
                                                                      AUDIT_PARTITIONS,
                                                                      RetentionPolicy.retentionPolicy(AUDIT_RETENTION_MAX_COUNT,
                                                                                                      AUDIT_RETENTION_MAX_BYTES,
                                                                                                      AUDIT_RETENTION_MAX_AGE_MS),
                                                                      "latest",
                                                                      AUDIT_MAX_EVENT_SIZE_BYTES,
                                                                      ConsistencyMode.EVENTUAL,
                                                                      0,
                                                                      StreamCompression.NONE,
                                                                      Option.none());

    record unused() implements AuditLifecycleStreams {}
}
