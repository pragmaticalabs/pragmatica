# Aether CLI Surface — 2026-05-21

Source HEAD: `a52dd99d4fb72dc15e0008dfcf2e7e56a2da54ef` (branch `release-1.0.0-rc1`)

**Global flags (apply to all subcommands; `OutputOptions` is INHERIT-scoped mixin):** `--format json|table|value|csv`, `--field <dot.path>`, `--quiet/-q`, `--no-color`, `-c/--connect/--endpoint`, `--config`, `--api-key`, `-k/--tls-skip-verify`, `--request-timeout`. The `--format` answer in the JSON column below is therefore "Y" for every leaf — what matters is whether the underlying response is JSON-shaped. All "Y" entries below were verified to flow through `OutputFormatter.printQuery/printAction` (which honors `--format`).

| Command | Purpose | JSON | Hits | Notes |
|---|---|---|---|---|
| `aether ab-tests` | Show A/B tests (default subcommand) | Y | GET /api/ab-tests | Default action lists |
| `aether ab-tests conclude <id> --winner` | Conclude A/B test with winner | Y | POST /api/ab-tests/conclude/{id} | — |
| `aether ab-tests create <group:artifact> --version-a --version-b [--split --instances]` | Create A/B test | Y | POST /api/ab-tests/create | — |
| `aether ab-tests list` | List A/B tests | Y | GET /api/ab-tests | — |
| `aether ab-tests metrics <id>` | A/B comparison metrics | Y | GET /api/ab-tests/metrics/{id} | — |
| `aether ab-tests status <id>` | A/B test status | Y | GET /api/ab-tests/{id} | — |
| `aether alerts` | List alerts (default action) | Y | GET /api/alerts | — |
| `aether alerts active` | Active alerts only | Y | GET /api/alerts/active | — |
| `aether alerts clear` | Clear active alerts | Y | POST /api/alerts/clear | — |
| `aether alerts history` | Alert history | Y | GET /api/alerts/history | — |
| `aether alerts inject -n -s -m [--metric --value]` | Inject synthetic alert | Y | POST /api/alerts/inject | — |
| `aether alerts list` | List all alerts | Y | GET /api/alerts | — |
| `aether artifacts delete <g:a:v>` | Delete artifact | Y | DELETE /repository/{g}/{a}/{v} | — |
| `aether artifacts deploy <jar> -g -a -v` | Deploy JAR to repo | Y | PUT /repository/{g}/{a}/{v}/{file} | — |
| `aether artifacts info <g:a:v>` | Artifact metadata | Y | GET /repository/info/{g}/{a}/{v} | — |
| `aether artifacts list` | List artifacts | Y | GET /repository/artifacts | — |
| `aether artifacts metrics` | Artifact storage metrics | Y | GET /api/artifacts/metrics | — |
| `aether artifacts push <g:a:v>` | Push blueprint+slices from local m2 | Y | PUT /repository/... (multi) | Walks `META-INF/blueprint.toml` |
| `aether artifacts versions <g:a>` | List artifact versions | Y | GET /repository/{g}/{a}/maven-metadata.xml | XML response, not JSON |
| `aether backups list` | List backups | Y | GET /api/backups | — |
| `aether backups restore <commit>` | Restore from commit | Y | POST /api/backups/restore | — |
| `aether backups trigger` | Trigger manual backup | Y | POST /api/backups | — |
| `aether blueprints apply <path>` | Apply blueprint .toml | Y | POST /api/blueprints (BLUEPRINT_PUBLISH_BODY) | — |
| `aether blueprints delete <id> [--force]` | Delete blueprint | Y | DELETE /api/blueprints/{id} | Interactive confirm without `--force` |
| `aether blueprints deploy <g:a:v> [--wait --timeout]` | Deploy from artifact in repo | Y | POST /api/blueprints/deploy | — |
| `aether blueprints get <id>` | Blueprint details | Y | GET /api/blueprints/{id} | — |
| `aether blueprints list` | List blueprints | Y | GET /api/blueprints | — |
| `aether blueprints status <id>` | Deployment status | Y | GET /api/blueprints/status/{id} | — |
| `aether blueprints upload <jar> -g -a -v` | Upload + deploy blueprint JAR | Y | PUT /repository + POST /api/blueprints/deploy | — |
| `aether blueprints validate <path>` | Validate without deploy | Y | POST /api/blueprints/validate | — |
| `aether certs` | List certs (default action) | Y | GET /api/certificates | — |
| `aether certs status` | Certificate status/expiry | Y | GET /api/certificates | Alias of bare `certs` |
| `aether cluster apply <toml> [--dry-run --yes --resume --rollback --full-check]` | Apply cluster TOML | Y | GET+POST /api/cluster/config | Two-phase: fetch version then POST |
| `aether cluster await-quiesced --epoch T:C [--timeout]` | Wait for cluster quiesce | Y | POST /api/cluster/await-quiesced?epoch=&timeout= | — |
| `aether cluster bootstrap <toml> [--yes --resume --full-check --wait --timeout --cluster --ssh-public-key --keep-on-failure]` | Bootstrap new cluster from TOML | Y | (provisioner; polls GET /api/cluster/status) | Local orchestration; no single mgmt-route |
| `aether cluster create-key [--role --name]` | Create new API key | Y | POST /api/cluster/keys | Role ADMIN/OPERATOR/VIEWER |
| `aether cluster destroy [--yes --keep-resources --cluster]` | Destroy active cluster | Y | GET /api/nodes/lifecycle, POST /api/nodes/drain/{id}, POST /api/nodes/shutdown/{id} | Multi-call orchestration |
| `aether cluster drain <id> [--wait --timeout]` | Drain a node | Y | POST /api/nodes/drain/{id}; polls GET /api/nodes/lifecycle/{id} | Duplicates `aether nodes drain` |
| `aether cluster export [--with-status]` | Export cluster TOML | partial | GET /api/cluster/config (+ optional /api/cluster/status) | Prints raw TOML body, not JSON |
| `aether cluster generation` | Cluster generation snapshot | Y | GET /api/cluster/generation | Has TABLE summary fallback |
| `aether cluster init [--output --force --name --target --provider --region ...]` | Generate cluster-config.toml | N | (none — local file gen) | Wizard or batch flags |
| `aether cluster list` | List registered clusters (local registry) | Y | (none — local file) | Reads `~/.aether/clusters/registry.toml` |
| `aether cluster list-keys [--audit]` | List API keys | Y | GET /api/cluster/keys [+ /api/cluster/keys/audit] | — |
| `aether cluster migrate --target --zone [--strategy --dns --dry-run]` | Migrate cluster across cloud | Y | POST /api/cluster/migrate or /api/cluster/migrate/plan | — |
| `aether cluster remove <name>` | Remove cluster from registry | Y | (none — local file) | Local registry only |
| `aether cluster revoke-key <id> [--immediate]` | Revoke API key | Y | POST /api/cluster/keys/revoke/{id} | — |
| `aether cluster rotate-key [--grace-period --role]` | Rotate API key | Y | POST /api/cluster/keys + POST /api/cluster/keys/revoke/{id} | Writes new key to `~/.aether/clusters/<name>/api-key` |
| `aether cluster scaffold --name --template [--nodes --image --mgmt-port-base --app-port-base --cluster-port]` | Emit docker-compose template | N | (none — local file gen) | Stdout TOML/YAML, no API call |
| `aether cluster scale <source> <role> --count` / `--core <N>` | Scale cluster nodes | Y | GET /api/cluster/config, POST /api/cluster/scale | — |
| `aether cluster status` | Cluster status | Y | GET /api/cluster/status | — |
| `aether cluster tasks` | List task-group assignments (default action) | Y | GET /api/cluster/tasks | TABLE summary |
| `aether cluster tasks list` | List task-group assignments | Y | GET /api/cluster/tasks | — |
| `aether cluster tasks reassign --group --target` | Reassign task group | Y | PUT /api/cluster/tasks/reassign/{group} | — |
| `aether cluster tasks status <group>` | One task-group assignment | Y | GET /api/cluster/tasks | Client-side filter |
| `aether cluster topology` | Show topology | Y | GET /api/cluster/topology | — |
| `aether cluster topology auto-heal disable` | Disable CTM auto-heal | Y | POST /api/cluster/topology/auto-heal/disable | — |
| `aether cluster topology auto-heal enable` | Enable CTM auto-heal | Y | POST /api/cluster/topology/auto-heal/enable | — |
| `aether cluster topology auto-heal status` | Auto-heal enabled? | Y | GET /api/cluster/topology/auto-heal | — |
| `aether cluster topology circuit-breaker reset` | Reset CTM circuit breaker | Y | POST /api/cluster/topology/circuit-breaker/reset | — |
| `aether cluster topology circuit-breaker status` | CTM circuit breaker state | Y | GET /api/cluster/topology/circuit-breaker | — |
| `aether cluster upgrade --version` | Upgrade cluster | Y | GET /api/cluster/config + POST /api/cluster/upgrade | — |
| `aether cluster use <name>` | Switch active context | Y | (none — local registry) | — |
| `aether config` | Show config (default action) | Y | GET /api/config | — |
| `aether config list` | All config (base + overrides) | Y | GET /api/config | — |
| `aether config overrides` | Dynamic overrides only | Y | GET /api/config/overrides | — |
| `aether config remove <key> [--node]` | Remove override | Y | DELETE /api/config/{key} or /api/config/nodes/{id}/{key} | — |
| `aether config set <key> <value> [--node]` | Set override | Y | POST /api/config | — |
| `aether controller config [--cpu-up --cpu-down --call-rate --interval]` | Get or update controller config | Y | GET or POST /api/controller/config | Read or write based on flags |
| `aether controller evaluate` | Force controller eval | Y | POST /api/controller/evaluate | — |
| `aether controller status` | Controller status | Y | GET /api/controller/status | — |
| `aether deploy [<g:a:v>] [--canary --blue-green --rolling --traffic --instances --error-rate --latency --drain-timeout --manual-approval --wait --timeout]` | Top-level deploy with strategy | Y | POST /api/deploy (or /api/blueprints/deploy if no strategy) | Bare `deploy` defers to strategy or immediate |
| `aether deploy complete <id>` | Finalize deployment | Y | POST /api/deploy/complete/{id} | — |
| `aether deploy list` | List active deployments | Y | GET /api/deploy | — |
| `aether deploy promote <id> [--traffic]` | Advance deployment | Y | POST /api/deploy/promote/{id} | — |
| `aether deploy rollback <id>` | Rollback deployment | Y | POST /api/deploy/rollback/{id} | — |
| `aether deploy status <id>` | Show deployment status | Y | GET /api/deploy/{id} | — |
| `aether events [--since]` | Cluster events | Y | GET /api/events[?since=] | — |
| `aether generate-completion` | Generate shell-completion script | N | (none — picocli builtin) | From `picocli.AutoComplete.GenerateCompletion` |
| `aether health` | Cluster health | Y | GET /api/health | — |
| `aether invocation-metrics` | List invocation metrics (default action) | Y | GET /api/invocations/metrics | — |
| `aether invocation-metrics list` | All invocation metrics | Y | GET /api/invocations/metrics | — |
| `aether invocation-metrics slow` | Slow invocations | Y | GET /api/invocations/metrics/slow | — |
| `aether invocation-metrics strategy [<type> <p1> <p2>]` | Get or set threshold strategy | Y | GET or POST /api/invocations/metrics/strategy | Server rejects set at runtime |
| `aether logging` | List log levels (default action) | Y | GET /api/logging/levels | — |
| `aether logging list` | List log levels | Y | GET /api/logging/levels | — |
| `aether logging reset <logger>` | Reset to default | Y | DELETE /api/logging/levels/{logger} | — |
| `aether logging set <logger> <level>` | Set log level | Y | POST /api/logging/levels | — |
| `aether metrics` | Cluster metrics | Y | GET /api/metrics | — |
| `aether nodes` | List nodes (default action) | Y | GET /api/nodes | — |
| `aether nodes activate <id>` | Node to ON_DUTY | Y | POST /api/nodes/activate/{id} | — |
| `aether nodes drain <id>` | Drain node | Y | POST /api/nodes/drain/{id} | Simpler than `cluster drain` (no wait) |
| `aether nodes health [<id>] [--liveness]` | Per-node readiness/liveness | Y | GET /health/ready or /health/live (per-node variant uses `/{id}`) | — |
| `aether nodes inflight [<id>]` | In-flight requests | Y | GET /api/nodes/inflight[/{id}] | — |
| `aether nodes lifecycle [<id>] [--state]` | Lifecycle entries | Y | GET /api/nodes/lifecycle[/{id}][?state=] | — |
| `aether nodes metrics [<id>]` | Per-node metrics | Y | GET /api/nodes/metrics[/{id}] | — |
| `aether nodes routes [<id>]` | HTTP routes on node | Y | GET /api/nodes/routes[/{id}] | — |
| `aether nodes shutdown <id>` | Shutdown node | Y | POST /api/nodes/shutdown/{id} | — |
| `aether nodes slices [<id>]` | Slices on node | Y | GET /api/nodes/slices[/{id}] | — |
| `aether observability depth` | List depth overrides | Y | GET /api/observability/depth | — |
| `aether observability depth-remove <art#method>` | Remove depth override | Y | DELETE /api/observability/depth/{art}/{method} | — |
| `aether observability depth-set <art#method> <N>` | Set depth threshold | Y | POST /api/observability/depth | — |
| `aether routes` | Cluster-wide HTTP routes | Y | GET /api/routes | — |
| `aether scale <g:a:v> -n [--placement --wait --timeout]` | Scale a slice | Y | POST /api/scale; polls GET /api/slices | — |
| `aether scheduled-tasks` | List tasks (default action) | Y | GET /api/scheduled-tasks | — |
| `aether scheduled-tasks get <section>` | Tasks for config section | Y | GET /api/scheduled-tasks/{section} | — |
| `aether scheduled-tasks list` | List tasks | Y | GET /api/scheduled-tasks | — |
| `aether scheduled-tasks pause <section> <art> <method>` | Pause task | Y | POST /api/scheduled-tasks/pause/{section}/{art}/{method} | — |
| `aether scheduled-tasks resume <section> <art> <method>` | Resume task | Y | POST /api/scheduled-tasks/resume/{section}/{art}/{method} | — |
| `aether scheduled-tasks trigger <section> <art> <method>` | Trigger task | Y | POST /api/scheduled-tasks/trigger/{section}/{art}/{method} | — |
| `aether schema baseline <ds> -v` | Baseline at version | Y | POST /api/schema/baseline/{ds}?version= | — |
| `aether schema history <ds>` | Migration history | Y | GET /api/schema/history/{ds} | — |
| `aether schema migrate <ds>` | Trigger migration | Y | POST /api/schema/migrate/{ds} | — |
| `aether schema retry <ds>` | Retry failed migration | Y | POST /api/schema/retry/{ds} | — |
| `aether schema status [<ds>]` | Schema status | Y | GET /api/schema/status[/{ds}] | — |
| `aether schema undo <ds> -v` | Undo to version | Y | POST /api/schema/undo/{ds}?targetVersion= | — |
| `aether slices [--state]` | Cluster-wide slices | Y | GET /api/slices[?state=] | — |
| `aether status [<id>]` | Cluster or node status | Y | GET /api/nodes/status[/{id}] | — |
| `aether storage list [--node]` | List storage instances | Y | GET /api/cluster/storage or /api/storage | — |
| `aether storage snapshot <name>` | Force metadata snapshot | Y | POST /api/storage/snapshot/{name} | — |
| `aether storage status <name> [--node]` | Storage status | Y | GET /api/cluster/storage/{name} or /api/storage/{name} | — |
| `aether streams list` | List streams | Y | GET /api/streams | — |
| `aether streams publish <name> <msg>` | Publish to stream | Y | POST /api/streams/publish/{name} | Body base64-encoded |
| `aether streams status <name>` | Stream details | Y | GET /api/streams/{name} | — |
| `aether thresholds` | List thresholds (default action) | Y | GET /api/thresholds | — |
| `aether thresholds list` | List thresholds | Y | GET /api/thresholds | — |
| `aether thresholds remove <metric>` | Remove threshold | Y | DELETE /api/thresholds/{metric} | — |
| `aether thresholds set <metric> -w -c` | Set threshold | Y | POST /api/thresholds | — |
| `aether traces get <id>` | Get traces for request | Y | GET /api/traces/{id} | — |
| `aether traces inject --operation [-d --depth --request-id --trace-id]` | Inject synthetic trace | Y | POST /api/traces/inject | — |
| `aether traces list [-l -m -s]` | List traces | Y | GET /api/traces[?limit=&method=&status=] | — |
| `aether traces stats` | Trace statistics | Y | GET /api/traces/stats | — |
| `aether workers endpoints` | Worker endpoints | Y | GET /api/workers/endpoints | — |
| `aether workers health` | Worker pool health | Y | GET /api/workers/health | — |
| `aether workers list` | Worker nodes | Y | GET /api/workers | — |

## Notable gaps observed

- **`ManagementRoute.CLUSTER_GOVERNORS` (`GET /api/cluster/governors`)** is declared in the enum but has no CLI invocation site. Operators wanting governor state must hit the REST API directly.
- **`STREAM_CREATE` (`POST /api/streams`), `STREAM_DELETE` (`DELETE /api/streams/{name}`), `STREAM_PARTITION` (`GET /api/streams/{name}/{partition}`), `STREAM_READ` (`GET /api/streams/read/{name}/{partition}`), `STREAM_CONSUMERS` (`GET /api/streams/consumers/{name}`), `CONSUMER_GROUP_JOIN/LEAVE/STATUS`** — entire stream lifecycle (create/delete/read/consumer-group ops) is REST-only; CLI only covers `list`, `status`, `publish`.
- **`METRICS_COMPREHENSIVE`, `METRICS_DERIVED`, `METRICS_PROMETHEUS`, `METRICS_HISTORY`, `METRICS_TRANSPORT`** — five metrics variants exposed in REST; CLI only wires plain `aether metrics`. Test scripts wanting Prometheus or history must curl directly.
- **`SLICES_STATUS` (`GET /api/slices/status`) and `SLICE_TOPOLOGY` (`GET /api/slices/topology`)** are in the route enum but have no CLI surface — only `aether slices` (which hits `SLICES_LIST`) is exposed.
- **`TTM_STATUS` (`GET /api/ttm/status`) and `TTM_TRAINING_DATA` (`GET /api/ttm/training-data`)** — no CLI command at all. Foundation-model/TTM telemetry only via REST.
- **`BLUEPRINT_PUBLISH_ARTIFACT` (`POST /api/blueprints/publish`)** is enum-declared but never referenced by any CLI command — only `BLUEPRINT_PUBLISH_BODY` (used by `blueprints apply`) and `BLUEPRINT_DEPLOY` (used by `blueprints deploy/upload` and bare `deploy`) are wired.
- **`ARTIFACT_GET` and `ARTIFACT_POST`** are in the enum (GET/POST `/repository/{g}/{a}/{v}/{file}`) without CLI wrappers — CLI only uses `ARTIFACT_PUT`/`ARTIFACT_INFO`/`ARTIFACT_DELETE`/`MAVEN_METADATA`/`REPOSITORY_ARTIFACTS_LIST`. Reading a specific artifact byte stream from the cluster is not CLI-reachable.
- **`SCHEDULED_TASK_STATE` (`GET /api/scheduled-tasks/state/{section}/{art}/{method}`)** has no CLI command; `scheduled-tasks get <section>` only filters by section, not by individual task identity.
- **`aether cluster export` prints raw TOML**, not JSON — `--format json` is silently ignored for the body of the export.
- **`aether artifacts versions` returns Maven `maven-metadata.xml`**, not JSON — `--format` formats the XML opaquely.
- **`aether cluster drain` duplicates `aether nodes drain`** with extra `--wait` polling. Test scripts that need wait semantics should prefer `cluster drain`; those that don't can use the simpler `nodes drain`.
- **`aether cluster init` / `cluster scaffold` / `cluster list` / `cluster use` / `cluster remove`** are purely local (registry / TOML file ops); no HTTP traffic. Useful as "exists but offline" for tests that want to validate config without a live cluster.
