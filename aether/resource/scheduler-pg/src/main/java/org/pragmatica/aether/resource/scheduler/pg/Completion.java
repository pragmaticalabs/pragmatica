// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.scheduler.pg;

/// Outcome of a fenced write by a claimer (completion, reschedule after failure, release on close).
///
/// `REFUSED` is not a failure: it means the writer is no longer the current claimer of the job (its token was
/// superseded by a later claim, or the job was cancelled), so the write was not applied.
public enum Completion {
    ACCEPTED,
    REFUSED
}
