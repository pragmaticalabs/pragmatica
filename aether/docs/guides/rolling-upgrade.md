# Rolling Cluster Upgrade

This guide covers upgrading an Aether cluster to a new version by **rolling replacement**: the
leader replaces every node that does not yet run the target version, one node at a time, each under
a **new NodeId**. No node is ever restarted under its own id. A node id never returns: a stopped,
killed or restarted-in-place node is refused when it tries to rejoin under terminal removal (fresh
boot token), so an upgrade that restarted nodes on a new binary would not work. See
[`../operators/deployment-recovery.md`](../operators/deployment-recovery.md).

Slices stay served throughout, because each replacement joins, is confirmed by a canary, and only
then drains and retires the old node. That claim is scoped to slice serving; it does not mean zero
risk to the cluster's consensus tier. A core replacement swaps a voter seat, so a second, unrelated
core failure inside the same window can still push the cluster into quorum loss (writes paused,
minority self-fences — see [`../reference/guarantees.md`](../reference/guarantees.md) §3). The run
never replaces two nodes at once, to keep that window to one node at a time.

## Prerequisites

- A cluster bootstrapped with `aether cluster bootstrap` (the upgrade rewrites the stored cluster
  config; with none stored the command is refused and names the bootstrap step)
- `aether` CLI pointed at the cluster, with an ADMIN API key to start an upgrade (pause, resume and abort need OPERATOR)
- A healthy cluster with quorum; the run starts from the current membership and replaces one node at a time
- No runtime profile that pins the launch artifact literally: an `image` (container) or `jar_url`
  (JVM) written without the `{version}` placeholder wins over the version and the upgrade is refused
  with HTTP 409 naming the profile. Write the pin as `image = "registry/aether-node:{version}"` or
  `jar_url = ".../v{version}/aether-node.jar"` in the config the cluster was bootstrapped with
- For a `docker` source, every role's runtime profile pins an `image` that carries `{version}`
  (`[runtime.app] type = "container"`, `image = "registry/aether-node:{version}"`, `runtime = "app"` on the role).
  A docker replacement boots that image at the committed version; without it the provider would start the same
  image again, so the upgrade is refused with HTTP 409 naming the source (`UpgradeDockerImageUnversioned`) before
  any node is touched
- The target version's image or jar published where the cluster's runtime profile points

## Procedure

```bash
aether cluster upgrade --version 0.26.0 --wait
```

What happens:

1. The CLI stores the target version in the cluster config (the version every node that is replaced
   or scaled up from now on boots).
2. The leader starts a run and replaces every node not reporting the target version, **one at a
   time**: core nodes first, the current leader last among the cores, then workers. Each step is a
   node replacement (`POST /api/v1/nodes/replace/{id}` semantics): the new node joins, the old
   node's seat is swapped to it, a canary confirms it, then the old node drains and retires.
3. With `--wait` the command blocks until the run ends.

A re-issued `aether cluster upgrade --version <same version>` is idempotent: it starts the run again
only if a node still does not report the version, and otherwise answers `already at version`. An
upgrade to a different version while a run is live is refused (HTTP 409).

### Options

| Option | Description |
|--------|-------------|
| `--version <X.Y.Z>` | Target version (required) |
| `--wait` | Block until the run completes, aborts, pauses, or the timeout elapses |
| `--wait-timeout-minutes <N>` | Timeout for `--wait`, in minutes (default 180) |

`--wait` exit codes:

| Exit code | Meaning |
|-----------|---------|
| `0` | The run completed: every node reports the target version |
| `1` | The run was aborted or paused; the reason is printed |
| `2` | `--wait` timed out. The run is **not** stopped: it continues on the cluster, and can be followed with `aether cluster upgrade-status` |

## What You Will See

```bash
aether cluster upgrade-status
```

Reports the run (`GET /api/v1/upgrade/status`): target version, state (`RUNNING`, `PAUSED`,
`COMPLETED`, `ABORTED`), the pending stop request (`NONE`, `PAUSE`, `ABORT`), how many nodes are
done out of the total, the node being replaced now, the replacement order, and the reason a run is
paused or ended. For a replacement in flight, `GET /api/v1/nodes/replacements` shows its phase
(`PROVISIONING`, `JOINING`, `SWAPPING`, `CANARY`, `DRAINING_OLD`, `RETIRING_OLD`, `DONE`, or one of
`REVERTING`, `ROLLED_BACK`, `FAILED_KEPT_BOTH`).

Operator events:

| Event | Meaning |
|-------|---------|
| `upgrade-started` | The run began |
| `upgrade-completed` | Every node reports the target version |
| `upgrade-aborted` | The run was ended by an operator |
| `upgrade-paused` | The run stopped; the reason is in the event and in `upgrade-status` |
| `upgrade-resumed`, `upgrade-pause-ended` | Recoveries from a pause |

Each node replacement also emits the existing `node-replacement-*` events.

## Pause, Resume, Abort

```bash
aether cluster upgrade-pause
aether cluster upgrade-resume
aether cluster upgrade-abort
```

(REST: `POST /api/v1/upgrade/pause`, `/resume`, `/abort`.)

- **Pause and abort are requests.** They take effect when the replacement in flight reaches a
  terminal state (immediately when none is in flight). A replacement is never cut off mid-phase.
- **Resume** continues a paused run. A node whose replacement was rolled back is tried again.
- **Abort** ends the run and is not resumable. Nodes already replaced stay replaced, the others stay
  as they are. A new `aether cluster upgrade --version ...` starts a new run.

## Failure Handling

A rolled-back or kept-both replacement pauses the run with a reason. The cluster stays in a valid
mixed-version state in the meantime, so there is no urgency to complete a paused run, though it
should be resolved.

| Situation | What the run does | What to do |
|-----------|-------------------|------------|
| Replacement rolled back (`ROLLED_BACK`) | Pauses, reason names the node | Read the `node-replacement-*` events and the new node's logs, fix the cause (image or jar unreachable, config, capacity), then `aether cluster upgrade-resume` |
| Replacement kept both nodes (`FAILED_KEPT_BOTH`) | Pauses, reason names the node | Settle the pair: `POST /api/v1/nodes/replacements/settle/{id}` with `{"outcome": "keep-new"}` (finish retiring the old node) or `{"outcome": "roll-back"}` (give the new node up); then `aether cluster upgrade-resume`. Resuming before settling pauses the run again |
| Refusal when starting (for example a literal `image` or `jar_url` pin, or a docker source whose image has no `{version}`) | Not started, HTTP 409 | Fix the config as described in the prerequisites and re-issue the command |
| `--wait` exits `2` | Run continues | Follow with `aether cluster upgrade-status`; nothing needs undoing |
| Need to stop | Applied after the in-flight replacement | `aether cluster upgrade-pause` (resumable) or `aether cluster upgrade-abort` (final) |

Do not restart a failed or stopped node by hand under its old id: it is refused. A failed node is
replaced under a fresh id, by CTM auto-heal or by `POST /api/v1/nodes/replace/{id}`.

## Known Limit: Workers Are Replaced Serially

Workers are replaced one at a time, with one live replacement cluster-wide. A cluster with many
workers therefore takes proportionally longer; size `--wait-timeout-minutes` accordingly (or omit
`--wait` and follow `upgrade-status`). Parallel community batches come in a later release.

## Verify

Every node should report the target version:

```bash
curl -s http://<node>:8080/api/v1/nodes/lifecycle | jq '.[] | {nodeId, version}'
```

`version` is the software version the node advertises. An empty value means unknown (the answering
node holds no version label for that peer), not old; ask another node, or wait for the label to
arrive. Confirm that `aether cluster upgrade-status` reports `COMPLETED` and that the old node ids
are gone from the membership.

## Mixed-Version Clusters

Aether supports mixed-version clusters through envelope versioning. A partially-upgraded cluster is
fully functional. See [`../reference/versioning-and-compatibility.md`](../reference/versioning-and-compatibility.md)
for what that does and does not cover.
