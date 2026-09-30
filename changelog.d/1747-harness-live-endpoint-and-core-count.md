### Fixed (2026-09-30 — integration harness: pinned-endpoint probes and a restore gate blind to an uncounted core)
- **Cluster liveness probes no longer read the pinned `CLUSTER_ENDPOINT`.** `12-network`'s encrypted-transport
  probe got HTTP 000 once `13-edge-cases` replaced core-0's VM. It and the other read-only cluster probes in
  00, 05, 07, 08 and 11 now go through `_resolve_live_endpoint`. Mutating calls and the pre-bootstrap / post-destroy
  liveness questions keep the pinned endpoint on purpose (listed in the PR).
- **`restore_cluster_baseline` now requires active == leader counted == target.** The terminal gate passed on
  counted >= target, so a present core the leader does not count (active 6, counted 5 — the phantom-core
  signature) went through with both numbers logged. A bounded wait follows; on a miss it fails naming both
  numbers and the active core ids. Limitation: the provisioning endpoint exposes no counted ids, so the
  uncounted node is among the ids listed, not computed.
- **`pick_non_leader` no longer offers the harness's entry-point node.** Cloud never sets
  `MGMT_ENTRY_POINT_NODE`, so 12-network's S05 partitioned the node its own leader query went through
  (`hetzner-eu-core-2`), read the minority's leaderless view and reported a majority failure that never
  happened. The entry-point node is identified through the LOCAL `/health/live` route (never forwarded, names
  the answering node); `/api/v1/nodes/status` cannot be used because it is leader-targeted and names the
  leader whichever node was asked. Entry-point nodes are deferred and offered only when too few other
  candidates exist (logged fallback).
- **S05 reads the majority through the leader and tells a failed read from a violation.** A failed read is
  unknown and retried at the next poll; only a SUCCESSFUL read reporting no leader or `quorate=false` is a
  violation. Unknown is bounded: 3 consecutive failed reads fail as leader-unreachable, and fewer than 3
  successful reads across the window is inconclusive — never a pass.
  [verified: `aether/tests/integration/test/test-harness-endpoint-and-core-count.sh` (9 stub tests against the
  real functions) and `test-partition-heal-on-failure.sh` (12); removing the restore gate reddens 3, reverting
  the probe reddens 1, the previous status-nodeId implementation and a disabled deferral redden P1 and P2 (the stub models
  leader forwarding), scoring a failed read as a violation reddens S1 and S2, reading through the pinned
  endpoint reddens S4, removing both unknown bounds turns S1, S5 and S6 into PASS. No cluster or cloud run.]
