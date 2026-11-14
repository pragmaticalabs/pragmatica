// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.scheduler.pg;

/// One claimed firing of a job, handed to the [JobHandler].
///
/// @param id         the job
/// @param name       the name the job was scheduled under; the handler dispatches on it
/// @param payload    the bytes the job was scheduled with
/// @param attempt    1 for the first firing, incremented by every later claim of the same job
/// @param fenceToken the claim's fencing token, greater than every earlier token of the same job
public record FiredJob(JobId id, String name, byte[] payload, int attempt, long fenceToken) {
    public static FiredJob firedJob(JobId id, String name, byte[] payload, int attempt, long fenceToken) {
        return new FiredJob(id, name, payload, attempt, fenceToken);
    }
}
