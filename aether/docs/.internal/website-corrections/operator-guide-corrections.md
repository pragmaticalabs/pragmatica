# Operator Guide Corrections

Comparison of `https://pragmaticalabs.io/docs/operator-guide.html` against actual codebase (release-1.0.0-rc1).

Generated: 2026-04-05

---

## 1. CLI Command Syntax — Deploy

**Website shows:**
```bash
aether artifact upload target/my-service-1.0.0.jar
aether deploy target/blueprint.toml
aether deploy target/blueprint.toml --strategy canary
```

**Actual CLI structure:** The `deploy` command is a subcommand group with its own subcommands:
```
aether deploy <blueprint-or-artifact>       # Deploy (main command)
aether deploy list                           # List active deployments
aether deploy status                         # Show deployment status
aether deploy promote                        # Advance (increase traffic / switch env)
aether deploy rollback                       # Rollback deployment
aether deploy complete                       # Finalize deployment
```

**Issues:**
- `aether artifact upload` — verify this is the correct subcommand. The CLI has `ArtifactCommand` but the exact upload syntax needs checking.
- `--strategy canary` — the deploy command accepts strategy configuration but the flag name needs verification against the actual `DeployCommand.java` options.

---

## 2. CLI Command Syntax — Backup

**Website shows:**
```bash
aether backup create
aether backup list
aether backup restore --timestamp 2026-04-05T10:30:00Z
```

**Actual CLI:**
```bash
aether backup trigger    # NOT "create"
aether backup list       # correct
aether backup restore    # correct, but restore parameter is commit ID, not timestamp
```

**Issues:**
- `create` → should be `trigger`
- `--timestamp` → the BackupService uses git commit IDs, not timestamps. The CLI command `restore` takes a commit reference, not a timestamp.

---

## 3. CLI Command Syntax — Scaling

**Website shows:**
```bash
aether scale my-service --instances 5
aether cluster scale --nodes 7
```

**Actual CLI:**
- `aether scale` — exists as `ScaleCommand`. Verify exact flag: likely `--instances` or positional arg.
- `aether cluster scale` — exists as `ClusterScaleCommand`. Verify exact flag: likely `--nodes` or `--size`.

These are probably correct but should be verified against the actual `@Option` annotations.

---

## 4. CLI Command Syntax — Node Lifecycle

**Website shows:**
```bash
aether node drain node-3
aether node shutdown node-3
```

**Actual CLI:** These exist as:
- `aether node drain` (with node ID as argument)
- `aether node shutdown` (with node ID as argument)
- Also available: `aether node activate`

The format `node-3` is a friendly alias — the actual runtime uses `NodeId` values (UUIDs or KSUID-based IDs). The CLI may accept hostname or NodeId — verify the actual parameter.

---

## 5. CLI Command Syntax — Monitoring

**Website shows:**
```bash
aether health
aether alerts
aether thresholds set cpu_warning 0.75
aether thresholds set cpu_critical 0.90
```

**Actual CLI subcommands:**
- `aether health` → exists as `HealthCommand`
- `aether alerts` → exists as `AlertsCommand`
- `aether thresholds` → exists as `ThresholdsCommand`
- `aether observability enable TRACING my-service.process` → exists as `ObservabilityCommand`
- `aether logging set org.example DEBUG` → exists as `LoggingCommand`

The `thresholds set` syntax needs verification — check if it takes metric name + value as shown.

---

## 6. Canary Traffic Percentages

**Website claims:** "1% → 5% → 25% → 50% → 100% with auto-evaluation"

**Actual:** Canary stages are configurable via `CanaryStage` list, not hardcoded to these percentages. The default stages (if any) need verification. The stages are specified in the deploy request, not fixed by the system.

Should say: "Progressive traffic shift through configurable stages (e.g., 5% → 25% → 50% → 100%) with auto-evaluation at each stage."

---

## 7. Cloud Integration Table — Hetzner Secrets

**Website shows:** Hetzner has "—" for Secrets column.

**Actual:** Hetzner DOES have secrets support via `EnvSecretsProvider` — it resolves `${secrets:path}` from environment variables prefixed with `AETHER_SECRET_`. The `HetznerEnvironmentIntegration` always provides `EnvSecretsProvider` wrapped in `CachingSecretsProvider`.

Correct table entry for Hetzner Secrets: "Yes (env vars)" not "—".

---

## 8. Cloud Integration Table — Certificates Column

**Website shows:** Only AWS (ACM), GCP (Certificate Manager), Azure (Key Vault) have certificate support.

**Actual:** `CloudCertificateProvider` implements the `CertificateProvider` SPI for all providers. Hetzner uses self-signed certificates (no cloud CA). The table should clarify:
- Hetzner: Self-signed (HKDF from cluster secret)
- AWS: ACM
- GCP: Certificate Manager
- Azure: Key Vault

---

## 9. Blue-Green Switchover Timing

**Website claims:** "Atomic ~100ms switchover via consensus"

**To verify:** The switchover goes through Rabia consensus to update the VersionRouting KV entry. Consensus round latency is 2-5ms in same-DC. The 100ms claim seems high — it should be closer to 5-10ms for the consensus round. However, full traffic draining from the old version may take longer.

Suggest: "Atomic switchover via consensus (~5ms routing change, plus drain period for in-flight requests)"

---

## 10. Bootstrap Configuration Format

**Website shows:**
```toml
[cluster]
name = "production"
size = 5

[cloud]
provider = "hetzner"
region = "fsn1"
instance_type = "cx31"

[security]
cluster_secret = "${secrets:cluster/secret}"
```

**To verify:** The `ClusterBootstrapCommand` reads a config file with `BootstrapOrchestrator`. The exact TOML structure (section names, field names) needs verification against the actual config parser. The `[cluster]`, `[cloud]`, `[security]` sections may use different names.

Also: the bootstrap config likely includes `[deployment]` section with `type`, `instances`, `runtime`, `ports` based on the code in `ClusterBootstrapCommand`.

---

## 11. Backup Description — "Continuously Serialized"

**Website claims:** "Cluster metadata is continuously serialized to a local git repository"

**Actual:** `GitBackedPersistence` saves snapshots, but only when explicitly triggered or on disconnect. The `BackupService` has automatic periodic + on-change triggers, but it's NOT continuous — it's event-driven. The word "continuously" implies streaming, which is incorrect.

Should say: "Cluster metadata is periodically serialized to a local git repository — triggered on state changes and at configurable intervals."

---

## 12. Missing Sections

### Storage Management
No mention of AHSE (Hierarchical Storage Engine), which is a major operational concern:
- Per-instance configuration (tiers, retention, replication)
- Storage metrics and monitoring
- CLI: `aether storage` commands
- Garbage collection and demotion policies

### Streaming Operations
No mention of stream management:
- Stream creation/deletion
- Consumer group management
- Lag monitoring
- Dead-letter handling

### Worker Pool Management
No mention of worker pools, which are a key scaling mechanism:
- `aether workers list`
- `aether workers health`
- Governor election monitoring
- Community scaling

### Cluster Tasks (Control Plane Delegation)
The CLI has `aether cluster tasks` and `aether cluster tasks reassign` — these allow operators to redistribute control plane tasks. Not mentioned.

### Schema Management
No mention of `aether schema` commands:
- `aether schema status`
- `aether schema retry`
- Migration monitoring

### Certificate Management
No mention of `aether cert` commands for certificate operations.

### Declarative Cluster Management
The `aether cluster apply` command applies configuration changes declaratively. Not mentioned.

### Cluster Migration
The `aether cluster migrate` command for cross-environment migration. Not mentioned.

### Cluster Destroy
The `aether cluster destroy` command for tear-down. Not mentioned.

---

## 13. Scaling Description — Worker Groups

**Website says:** "Worker group scaling — scale to thousands via SWIM gossip. Zero consensus overhead"

**Mostly correct** but should clarify: workers don't participate in Rabia consensus (they use `ForwardingClusterNode` for KV writes). They ARE part of SWIM health detection and DHT-backed ReplicatedMap. "Zero consensus overhead" is accurate for the worker nodes themselves but they still consume DHT bandwidth.

---

## 14. Security — Missing Details

**Website mentions RBAC but doesn't explain:**
- How API keys are configured (TOML `[security.api_keys.*]`)
- Three roles: ADMIN, OPERATOR, VIEWER with specific permissions
- Per-route security in `routes.toml [security]` section
- `--api-key` flag on CLI commands

Should include the operational steps for setting up RBAC.

---

## Summary of Actions

| Priority | Issue | Action |
|----------|-------|--------|
| **Critical** | Backup CLI syntax wrong | `create` → `trigger`, restore uses commit ID not timestamp |
| **High** | Hetzner secrets marked as "—" | Change to "Yes (env vars)" |
| **High** | Missing sections | Add: Storage, Streaming, Worker pools, Schema, Certs, Tasks, Migration |
| **High** | Canary percentages hardcoded | Clarify stages are configurable |
| **Medium** | Blue-green timing | "~100ms" → "~5ms routing + drain period" |
| **Medium** | Backup "continuously" | Change to "periodically, triggered on changes" |
| **Medium** | Deploy CLI structure | Show subcommand group: list, status, promote, rollback, complete |
| **Medium** | Bootstrap config format | Verify section names match actual parser |
| **Medium** | Node ID format | Clarify NodeId vs hostname in CLI |
| **Low** | Worker scaling nuance | Clarify DHT bandwidth vs consensus overhead |
| **Low** | Security operational steps | Add RBAC setup guide |
