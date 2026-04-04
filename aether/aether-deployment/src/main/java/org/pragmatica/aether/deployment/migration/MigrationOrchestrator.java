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
