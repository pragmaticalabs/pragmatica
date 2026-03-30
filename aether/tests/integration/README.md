# Aether On-Premises Docker Cluster Integration Tests

Comprehensive test suite for running Aether clusters on a target machine via SSH + Docker.
Tests cover cluster formation, stability, chaos engineering, scaling, streaming, security,
deployment strategies, and cluster management.

## Prerequisites

- **Target machine**: Linux host with SSH access and Docker installed
- **SSH key**: Passwordless SSH key for the target machine
- **Docker**: Installed on target (or run `scripts/setup.sh` to install)
- **Network**: Ports 5150, 6000, 6100, 8070 accessible from test runner to target
- **Tools on test runner**: `bash`, `curl`, `python3`, `ssh`
- **Aether CLI** (optional): `aether` in PATH for bootstrap/destroy commands

## Environment Variables

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `TARGET_HOST` | Yes | — | IP or hostname of target machine |
| `AETHER_SSH_USER` | Yes | — | SSH username on target |
| `AETHER_SSH_KEY` | Yes | — | Path to SSH private key |
| `AETHER_CLUSTER_SECRET` | Yes | — | Cluster TLS secret |
| `AETHER_API_KEY` | No | (empty) | API key for management endpoints |
| `AETHER_ADMIN_API_KEY` | No | `$AETHER_API_KEY` | Admin-role API key |
| `AETHER_VIEWER_API_KEY` | No | (empty) | Viewer-role API key (for RBAC tests) |
| `AETHER_OPERATOR_API_KEY` | No | `$AETHER_API_KEY` | Operator-role API key |
| `MGMT_PORT` | No | 5150 | Management API port |
| `APP_PORT` | No | 8070 | App HTTP port |
| `SKIP_BOOTSTRAP` | No | false | Skip cluster bootstrap (assume running) |
| `SKIP_CLEANUP` | No | false | Skip cluster teardown after tests |
| `COLLECT_METRICS` | No | false | Collect thread/heap/RSS metrics per test |
| `METRICS_DIR` | No | `/tmp/aether-test-metrics` | Directory for metrics output files |

## Quick Start

```bash
# 1. Set environment
export TARGET_HOST=192.168.1.100
export AETHER_SSH_USER=ubuntu
export AETHER_SSH_KEY=~/.ssh/id_rsa
export AETHER_CLUSTER_SECRET=my-secret
export AETHER_API_KEY=admin-key

# 2. One-time setup (installs Docker, pulls image)
./scripts/setup.sh

# 3. Run all tests
./scripts/run-all.sh

# 4. Or run a specific suite
./scripts/run-suite.sh 02-chaos
```

## Test Suites

| Suite | Description | Duration |
|-------|-------------|----------|
| `00-smoke` | Cluster formation, slice deployment | ~5 min |
| `01-stability` | 4-hour soak, streaming soak | ~5 hours |
| `02-chaos` | Kill node, kill leader, kill multiple, kill under load | ~20 min |
| `03-scaling` | Scale up/down under load, quorum safety | ~15 min |
| `04-streaming` | Publish, consume, sustained stream load | ~10 min |
| `05-security` | Route security, cert rotation, principal injection | ~5 min |
| `06-deployment` | Rolling upgrade, canary, blue-green, schema migration | ~15 min |
| `07-cluster-mgmt` | Bootstrap, config apply, export, destroy | ~10 min |
| `08-resources` | SQL connector, HTTP client, pub/sub, scheduled tasks, streaming | ~15 min |
| `09-artifacts` | Push/resolve, large artifacts (64KB-5MB), DHT replication | ~10 min |
| `10-database` | Versioned schema, retry, baseline | ~5 min |
| `11-observability` | Prometheus, transport metrics, traces, alerts, certificates | ~10 min |
| `12-network` | QUIC connectivity, SWIM detection, gossip encryption | ~15 min |
| `13-edge-cases` | Stale route cleanup, disruption budget, concurrent deploys | ~15 min |

Stability tests (`01-stability`) are long-running. Override duration with environment variables:

```bash
SOAK_DURATION=300 STREAM_DURATION=300 ./scripts/run-suite.sh 01-stability
```

## Metrics Collection

Enable per-test thread count, RSS, and Java heap metrics with `COLLECT_METRICS=true`:

```bash
COLLECT_METRICS=true ./scripts/run-suite.sh 00-smoke
```

Each test produces before/after snapshots in `$METRICS_DIR` (default `/tmp/aether-test-metrics`).
The test runner prints a delta summary (threads, RSS) after each test completes.

Metrics files are named `<timestamp>_before-<test>.txt` and `<timestamp>_after-<test>.txt`.
Each file contains per-node `/proc/1/status` fields (Threads, VmRSS, VmSize, VmPeak) and
`jcmd 1 GC.heap_info` output.

## Adding New Tests

1. Create a new script in the appropriate `suites/XX-name/` directory
2. Name it `test-<description>.sh`
3. Source the shared libraries:
   ```bash
   source "${SCRIPT_DIR}/../../lib/common.sh"
   source "${SCRIPT_DIR}/../../lib/cluster.sh"
   ```
4. Write test functions and register them with `run_test`
5. End with `print_summary`
6. Make executable: `chmod +x suites/XX-name/test-<description>.sh`

## Directory Structure

```
integration/
├── README.md                          # This file
├── cluster-config.toml                # Default 5-node cluster config
├── scripts/
│   ├── setup.sh                       # One-time target machine setup
│   ├── run-all.sh                     # Run all suites sequentially
│   ├── run-suite.sh                   # Run a specific suite
│   └── cleanup.sh                     # Tear down cluster
├── suites/
│   ├── 00-smoke/                      # Basic formation and deployment
│   ├── 01-stability/                  # Long-running soak tests
│   ├── 02-chaos/                      # Node failure and recovery
│   ├── 03-scaling/                    # Scale up/down, quorum safety
│   ├── 04-streaming/                  # Event streaming
│   ├── 05-security/                   # Auth, RBAC, TLS
│   ├── 06-deployment/                 # Rolling, canary, blue-green
│   ├── 07-cluster-mgmt/              # Bootstrap, config, destroy
│   ├── 08-resources/                 # SQL, HTTP, pub/sub, scheduled tasks, streaming
│   ├── 09-artifacts/                 # Push, resolve, large artifacts, replication
│   ├── 10-database/                  # Schema versioned, retry, baseline
│   ├── 11-observability/             # Prometheus, transport, traces, alerts, certs
│   ├── 12-network/                   # QUIC, SWIM detection, gossip encryption
│   └── 13-edge-cases/               # Stale routes, disruption budget, concurrent deploy
└── lib/
    ├── common.sh                      # Logging, assertions, HTTP helpers
    ├── cluster.sh                     # Cluster operations
    └── load.sh                        # Load generation
```
