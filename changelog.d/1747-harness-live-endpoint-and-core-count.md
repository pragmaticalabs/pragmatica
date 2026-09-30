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
  [verified: `aether/tests/integration/test/test-harness-endpoint-and-core-count.sh` (6 stub tests against the
  real functions); removing the gate call reddens 3, reverting the probe reddens 1. No cluster or cloud run.]
