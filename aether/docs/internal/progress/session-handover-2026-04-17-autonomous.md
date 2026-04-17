# Session Handover — 2026-04-17 (autonomous $800 run)

**Branch:** `release-1.0.0-rc1` · **HEAD:** `6e3fb40fa`

## What landed (3 commits on top of `cc91c07bb`)

| Commit | Subject |
|---|---|
| `534dae0ed` | fix: slice loading with stream resources — config binding, extensions, defaults |
| `4d2fe7eb6` | test: event-driven topology helpers, kill_node CLUSTER_ID normalization, CTM cleanup |
| `6e3fb40fa` | fix: move test-persistence migration to blueprint's schema/ dir |

## Test status (progression across 7 full runs)

| Suite | Before | After | Notes |
|---|---|---|---|
| 04-streaming | 3p/1f | **4p/0f** ✅ | Stable across all runs |
| 12-network | 0p/3f | **3p/0f** ✅ | kill_node + state-restore + drop_ctm_replacements |
| 08-resources (4 of 5 tests) | 3p/2f | **4p/1f** ✅ | StatusRoutes fix + CLI JSON path fix |
| 08-resources test-sql-connector | Fail | Intermittent | Migration pipeline now wired; hangs in parallel runs |

**Net result:** 11/12 target tests now passing vs. baseline 6/12.

## Root causes fixed

1. **`StreamRoutes.createStream` not idempotent** — `STREAM_ALREADY_EXISTS` returned non-2xx, breaking suite reruns. Pre-check via `streamInfo` before create. (`StreamRoutes.java`)

2. **`StatusRoutes.buildStatusResponse` sourced `cluster.nodes` from ephemeral `connectedPeerIds`** — returned empty/self-only during lifecycle transitions. Switched to authoritative `topologyManager.topology()`, matching `ClusterTopologyRoutes`. (`StatusRoutes.java`)

3. **Config binding couldn't resolve `name` for records whose TOML section is dotted** — `[streams.test-events]` had no `name` key so `bindToClass` failed with `Config section not found: StreamConfig.name`. Added `deriveNameFromSectionSuffix` fallback: when component is `name`/String and section has a dot, derive from section suffix. Applied to BOTH `TomlConfigService` and `ProviderBasedConfigService` (runtime uses the latter via overlay). (`TomlConfigService.java`, `ProviderBasedConfigService.java`)

4. **`StreamConfig` had no `DEFAULT` field** — record binding needs `DEFAULT` as fallback for fields absent from TOML (e.g. nested `RetentionPolicy`). Added `public static final StreamConfig DEFAULT`. (`StreamConfig.java`)

5. **Runtime extensions not registered for stream factories** — `StreamPublisherFactory`/`StreamAccessFactory` pull `StreamPartitionManager`, `Serializer`, `Deserializer` from `ProvisioningContext.extension(...)`. These were never registered on `SpiResourceProvider`. Registered in `registerForwardExtensionsOnSpi`. (`AetherNode.java`)

6. **`JsonMapper.navigatePath` didn't handle numeric array indices** — `aether status --field cluster.nodes.0.id` returned `Path not found`. Patched to interpret numeric segments as array indices when current node is an array. (`JsonMapper.java`, local CLI reinstalled)

7. **Schema migrations in the wrong location** — author put SQL at `src/main/resources/db/migration/` (Flyway convention) but `BlueprintArtifactParser` reads `schema/*.sql` from the blueprint JAR, and `PackageBlueprintMojo` only picks up from `${project.basedir}/schema/`. Moved `V1__create_kv.sql` to `schema/`. (`test-persistence/schema/V1__create_kv.sql`)

8. **Test infra `kill_node` targeted wrong container** — used `aether-${node_id}` (e.g. `aether-node-2`) but compose files name containers `aether-a-node-2` / `aether-b-node-2`. Introduced `CLUSTER_ID` export from `run-tests.sh`, `_docker_container_name` helper. Also `restart_all_nodes`/`restore_baseline` filters updated. (`cluster.sh`, `run-tests.sh`)

9. **`CLUSTER_ID` got overwritten by `suite.conf`** — `cluster=destructive` in `suite.conf` overrode `local cluster=a|b` in `run_suite`. Derive a separate `cluster_id` from MGMT endpoint selection before exporting. (`run-tests.sh`)

10. **Auto-heal produced 6-node clusters across sequential destructive tests** — test killed a node, CTM provisioned replacement, then `start_node` restored the original → 6 nodes. Added `drop_ctm_replacements` helper and called it before `start_node` in 12-network recovery tests. (`cluster.sh`, `12-network/*.sh`)

## Test infra additions

- **`aether/tests/integration/lib/topology.sh`** — new helper file. Event-driven replacements for snapshot-polling assertions.
  - `wait_for_node_departure` — polls `/api/events` for `NODE_LEFT`/`NODE_FAILED`
  - `wait_for_replacement_of` — matches `NODE_JOINED` with different nodeId
  - `observe_quorum_window` — replays `clusterSize` field to assert quorum never broke
  - `topology_events_since` — UNIONs events across all node ports (the LB-routed `/api/events` only returns one node's buffer; each node has its own local view)

## Remaining work

**08-resources test-sql-connector intermittent hang:** the blueprint JAR now has the correct `schema/V1__create_kv.sql`. SchemaOrchestratorService wires it through. But when 04+08 run in parallel, test-sql-connector's `blueprint deploy` can hang (observed in run7). Suspect task-group activation lag ("all task groups ACTIVE" WARN) plus deploy ordering. When run in isolation after cluster fully settles, should pass.

**Next session starting points:**

1. Run `04,08,12` sequentially (not parallel) by temporarily setting `max_parallel=1` in `run_cluster_a_suites` — confirm 08 passes end-to-end.
2. If green, full 15-suite run.
3. Hetzner validation.
4. Investigate task-group activation timing — "all task groups ACTIVE (timed out)" WARN during parallel suite startup means deploy task-group registration races with suite entry. This is a genuine runtime stabilization bug, not a test-side race. File issue.

## GitHub issues filed this session

- **#164** feat: emit TaskAssignmentChange / TaskReassigned events to /api/events (rc2)
- **#165** feat: persist cluster events via streaming subsystem (rc2)
- **#166** bug: CTM phantom nodes persist HEALTHY in KV after container removal (rc1)
- **#167** feat: validate schema/*.sql presence at build time when blueprint declares [database] (rc1)

## Watch-outs

- The locally-installed `aether` CLI (`~/.aether/lib/aether.jar`) now has the array-index fix. Any fresh install via `./build.sh + scp` needs to replace this jar too.
- Docker image `aether-node:local` on the remote target host (`192.168.0.71`) is built from `~/aether-build/node/target/aether-node.jar`. After any code change in aether/node, scp new jar + `docker build` + `docker compose down -v && up -d` both clusters.
- Blueprint JAR in `~/.m2/repository/.../test-persistence/1.0.0/` must be regenerated after any blueprint resource change (schema, resources.toml). `mvn clean install` in the blueprint dir does this.
- `cluster=destructive` in `suite.conf` no longer corrupts exported `CLUSTER_ID` — but if you add new suite.conf vars, check for similar collisions with run-tests.sh locals.
