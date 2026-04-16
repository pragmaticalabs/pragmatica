// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.migration;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;


/// Orchestrates cross-environment cluster migration.
///
/// Plans a migration by analyzing current topology and computing the target state,
/// then executes the plan step-by-step. Supports rollback of failed migrations.
///
/// Implementations coordinate with ComputeProvider (provisioning/termination),
/// DnsProvider (record updates), and the cluster management API (drain/sync).
public interface MigrationOrchestrator {
    Result<MigrationPlan> plan(MigrationRequest request);
    Promise<Unit> execute(MigrationPlan plan);
    Promise<Unit> rollback(MigrationPlan plan);
}
