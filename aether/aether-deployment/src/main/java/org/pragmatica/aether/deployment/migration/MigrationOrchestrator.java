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
    /// Plan a migration: analyze current topology, compute target state, and produce ordered steps.
    Result<MigrationPlan> plan(MigrationRequest request);

    /// Execute a planned migration step-by-step.
    Promise<Unit> execute(MigrationPlan plan);

    /// Rollback a failed migration by reversing completed steps.
    Promise<Unit> rollback(MigrationPlan plan);
}
