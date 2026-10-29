// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.replica;

/// The numbers of a replica feed client. Every number is a guess (spec D7, section 2.8) to be tuned by the M-OBS measurement.
///
/// @param staleMillis a source whose newest counted heartbeat is older than this is stale (readiness b)
/// @param lagMillis a heartbeat must be this old before the replica is required to have caught up to its `nextSlot` (readiness c)
/// @param silenceMillis a source silent for this long is abandoned and the selector asked again
/// @param bufferBytes byte bound of the receiver buffer that holds live entries while the snapshot transfers
/// @param tickMillis how often the client checks silence and refreshes readiness
public record ReplicaClientLimits(long staleMillis,
                                  long lagMillis,
                                  long silenceMillis,
                                  long bufferBytes,
                                  long tickMillis) {
    public static final ReplicaClientLimits DEFAULT = new ReplicaClientLimits(30_000L,
                                                                              5_000L,
                                                                              3_000L,
                                                                              8L * 1024 * 1024,
                                                                              250L);
}
