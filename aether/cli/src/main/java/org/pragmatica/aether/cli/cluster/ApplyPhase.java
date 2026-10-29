// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

/// Where an apply stands. `ABORTED` is the state that opens `--rollback` only (a failed fence or a
/// definitively refused commit, cluster-apply spec 4.6); nothing in this change moves a state there yet.
public enum ApplyPhase {
    PLANNED,
    RUNNING,
    COMMITTING,
    COMMITTED,
    ROLLING_BACK,
    ABORTED
}
