# Suite 12-network Charter

**Test-ID convention:** `TC-12-NETWORK-NNN` — zero-padded 3-digit, stable across reorgs.

**Scope:** Encrypted gossip transport (QUIC + TLS), SWIM failure detection, and the partition-quorum gate that protects single-writer lifecycle writes during network splits. Each test fixes one observable invariant of the cluster's network substrate.

---

## Contracts under test

| ID | Contract | Spec citation |
|---|---|---|
| C1 | Cluster reaches canonical "ready" state with N=5 ON_DUTY healthy cores and an elected leader before any network probe runs | `aether/docs/specs/test-readiness-contract.md §1.1`; `membership-architecture-v2-spec.md §§3-5` |
| C2 | Gossip transport is TLS — every cluster peer-to-peer connection is initiated through `QuicSslContext` and reports a non-zero handshake counter | `aether/docs/specs/quic-transport-spec.md §3.6` (TLS Always On); `aether/docs/specs/swim-driven-topology-spec.md §6` (SWIM → QUIC lifecycle) |
| C3 | TLS handshake success ratio is high — `quic_handshake_failures_total / quic_handshake_total ≤ 0.5` (current threshold; audit §1.14 marks 50% lax — should tighten to ≤ 0.05 post-RC1) | `aether/docs/specs/quic-transport-spec.md §3.6, §3.8` |
| C4 | Health/liveness endpoints serve 200 over the encrypted management path while gossip is encrypted | `aether/docs/specs/quic-transport-spec.md §3.6`; `test-readiness-contract.md §3` |
| C5 | Every cluster peer is QUIC-connected to ≥ N-1 others — partial-connectivity (one-sided peering) is a violation | `aether/docs/specs/quic-transport-spec.md §3.1, §3.7` (one connection per peer pair, NodeId-ordered) |
| C6 | A killed cluster member produces `NODE_FAILED` in the event log AND a CTM-driven replacement NODE_JOINED, without breaking quorum during the transition | `aether/docs/specs/swim-driven-topology-spec.md §6`; `membership-architecture-v2-spec.md §5.1` (ON_DUTY+SwimFaulty→DECOMMISSIONED) |
| C7 | Post-kill convergence: cluster returns to 5 ON_DUTY healthy cores within the convergence budget (180s test window; backed by elastic-cluster model — killed NodeId does NOT revive) | `membership-architecture-v2-spec.md §5.1 note 5` (DECOMMISSIONED revival TTL); `membership-architecture-v2-spec.md §3.3` (CTM replacement) |
| C8 | SWIM detects a faulty node within `DETECTION_TIMEOUT` (default 15s) | `aether/docs/specs/swim-driven-topology-spec.md §6` (SWIM lifecycle); audit §1.14 — **threshold NOT enforced** in the current test |
| C9 | Partition quorum gate S05: within `self-drain` window (8s default), the FSM does NOT write DECOMMISSIONED for minority NodeIds even when the leader sees them as faulty | `membership-architecture-v2-spec.md §5.1` (ON_DUTY+SwimFaulty); single-writer invariant I2; `aether/docs/internal/progress/v1-roadmap.md` #17 (CLOSED in db221dee4) |
| C10 | Partition heal S06: after partition heal, cluster returns to 5 ON_DUTY within `HEAL_BUDGET_S` (30s) | `membership-architecture-v2-spec.md §5.1` (revival TTL window); reconciler loop |

**Contract gaps surfaced by this audit:**
- `[CONTRACT-GAP-12.A]` — Per-node QUIC connectivity coverage: spec mandates full mesh but `test_all_nodes_connected` samples only the entry node (audit §1.14, MEDIUM). The "every node sees ≥ N-1 peers" assertion is structurally untestable today; needs a `for node in $(cluster_node_list); do api_get $node/api/network/quic; done` iteration helper.
- `[CONTRACT-GAP-12.B]` — TLS handshake failure ceiling: spec §3.6 says "always on" but does not pin a numeric tolerance. Test allows up to 50% failures; need spec sentence pinning ≤ 5% post-cold-boot churn.
- `[CONTRACT-GAP-12.C]` — Passive LB / management-port encryption: spec §3.6 covers cluster-internal QUIC only; `test_health_probes_over_encrypted_transport` checks the management HTTPS path but no spec section enumerates the management-port TLS contract.

---

## Test-to-contract map

| TC ID | Test function | File:line | Contract(s) | Severity | Notes |
|---|---|---|---|---|---|
| TC-12-NETWORK-001 | `test_cluster_ready` | `test-gossip-encryption.sh:9` | C1 | smoke | Pre-condition. Audit §1.14 LOW — `log_pass` after `wait_for_cluster_ready` is structural sticker, not a check (acceptable for setup gate). |
| TC-12-NETWORK-002 | `test_cluster_formed_with_encryption` | `test-gossip-encryption.sh:14` | C1 | regression-net | Mis-named in audit — asserts `cluster_active_core_count == 5` but does NOT itself check encryption (encryption claim is covered by TC-12-NETWORK-003/-004). Rename suggested. |
| TC-12-NETWORK-003 | `test_gossip_encryption_active_via_config` | `test-gossip-encryption.sh:27` | C2 | core | Reads `quic_handshake_total` from `/api/metrics/transport`. Empty body fails (prior warn-then-pass RESOLVED, audit §1.14). |
| TC-12-NETWORK-004 | `test_gossip_encryption_via_transport` | `test-gossip-encryption.sh:50` | C2, C3 | core | Asserts `quic_handshake_total ≥ 1` AND `failures ≤ total/2`. Audit §1.14 MEDIUM — 50% ceiling too lax; tighten to ≤ 5% once expected post-chaos churn characterised. |
| TC-12-NETWORK-005 | `test_nodes_communicating_encrypted` | `test-gossip-encryption.sh:79` | C1 | regression-net | Audit §1.14 LOW (WEAK) — proves leader+events present, not encryption. Indirect: redundant with TC-12-NETWORK-003/-004; kept as gossip-live canary. |
| TC-12-NETWORK-006 | `test_health_probes_over_encrypted_transport` | `test-gossip-encryption.sh:90` | C4 | core | Asserts `/health/live` returns 200 while gossip is encrypted. Decoupled from C2 (relies on sibling test for encryption claim). |
| TC-12-NETWORK-007 | `test_cluster_ready` | `test-quic-connectivity.sh:12` | C1 | smoke | Soft `wait_for_phase NORMAL` (warn-then-pass on miss). Documented degraded-cluster pass-through; subsequent tests are the safety net. |
| TC-12-NETWORK-008 | `test_all_nodes_connected` | `test-quic-connectivity.sh:25` | C5 | core | Audit §1.14 MEDIUM — only entry node sampled; a one-sided partition (one node with 4 peers, another with 0) PASSES. Tracked as `[CONTRACT-GAP-12.A]`. |
| TC-12-NETWORK-009 | `test_kill_node_and_detect_drop` | `test-quic-connectivity.sh:49` | C6 | core | Event-driven `wait_for_node_departure` + `wait_for_replacement_of` + `observe_quorum_window`. Prior snapshot-polling RESOLVED. |
| TC-12-NETWORK-010 | `test_connections_recovered` | `test-quic-connectivity.sh:87` | C7 | core | `wait_for "5 ON_DUTY healthy cores" 180` + `assert_cluster_healthy`. Documents the deliberate non-revival (elastic-cluster model). |
| TC-12-NETWORK-011 | `test_cluster_ready` | `test-swim-detection.sh:17` | C1 | smoke | 5 ON_DUTY assertion + soft NORMAL-phase preference. |
| TC-12-NETWORK-012 | `test_swim_detection_time` | `test-swim-detection.sh:32` | C8 | core | **GREEN-STICKER (audit §1.14 HIGH; §2.2 row 17): DETECTION_TIMEOUT (15s) demoted to `log_warn` then `log_pass` for elapsed in [16s, 60s].** A regression to 45s detection silently passes. **RC1-blocker #17 CLOSED in db221dee4** (per task brief); verify the test now fails strictly when elapsed > DETECTION_TIMEOUT before declaring this contract sound. |
| TC-12-NETWORK-013 | `test_recovery_after_detection` | `test-swim-detection.sh:70` | C7 | core | `wait_for "5 ON_DUTY..." 180` + `assert_cluster_healthy`. |
| TC-12-NETWORK-014 | `test_initial_state` | `test-partition-quorum-gate.sh:169` | C1 | smoke | 5 ON_DUTY + NORMAL phase + leader present. Documented soft-phase trade-off: if NORMAL fails, S05 becomes unfalsifiable — flagged in inline comment. |
| TC-12-NETWORK-015 | `test_pick_minority` | `test-partition-quorum-gate.sh:186` | C9 | smoke | Identifies leader + 2 non-leader minority targets, persists for the next test. Hard fails on empty leader or <2 minority. |
| TC-12-NETWORK-016 | `test_partition_does_not_decommission_within_window` | `test-partition-quorum-gate.sh:214` | C9 | core | **Best-in-suite test.** Reads authoritative `/api/nodes/lifecycle/{id}` (KV-direct, not MembershipView); polls 5×@1Hz inside a 5s partition; immediate fail on first DECOMMISSIONED read; EXIT trap heals the partition. Names the FSM cell in failure messages. |
| TC-12-NETWORK-017 | `test_cluster_heals_to_5_onduty` | `test-partition-quorum-gate.sh:268` | C10 | core | Tight 30s budget for post-heal convergence; authoritative `assert_cluster_healthy`. |

---

## Suite-level invariants

- **Pre-conditions:** Cluster A (non-destructive, parallel-safe) for gossip-encryption + SWIM-detection + QUIC connectivity. Cluster B (destructive, sequential) for partition-quorum-gate (`docker network disconnect` requires container-level chaos). NODE_COUNT=5. No prior suite has left ON_DUTY+DRAINING entries.
- **Side effects:**
  - QUIC connectivity + SWIM detection tests **kill nodes** — elastic-cluster model means the killed NodeId is NOT revived; CTM provisions a replacement slot. Subsequent suites that assume "same 5 NodeIds as bootstrap" must `restore_cluster_baseline`.
  - Partition-quorum-gate test **disconnects** containers from the docker network for ≤ 5s and **always** heals on EXIT trap (idempotent — `network connect` is no-op if already connected).
- **Cleanup discipline:** EXIT traps re-attach partitioned containers regardless of test outcome (partition-quorum-gate). CTM auto-heal is left enabled across this suite (failure detection IS the test).

---

## Known limitations

| TC ID | Limitation | Tracking |
|---|---|---|
| TC-12-NETWORK-008 | Per-node QUIC connectivity not iterated — one-sided partitions pass | audit §1.14 MEDIUM; `[CONTRACT-GAP-12.A]` |
| TC-12-NETWORK-004 | 50% TLS handshake failure ceiling is too lax for production regression at 10–40% | audit §1.14 MEDIUM; `[CONTRACT-GAP-12.B]` |
| TC-12-NETWORK-012 | DETECTION_TIMEOUT threshold formerly demoted — verify CLOSED in db221dee4 actually wires strict fail | RC1-blocker #17 CLOSED in db221dee4 (per task brief); confirm before next release |
| TC-12-NETWORK-002 | Test name says "with encryption" but only asserts core count — rename | audit §1.14 LOW |
| TC-12-NETWORK-005 | Implicit reasoning ("if QUIC broken, leader empty") not directly tested — redundant with TC-12-NETWORK-003/-004 | audit §1.14 LOW |

---

## Charter changelog

| Date | Author | Change |
|---|---|---|
| 2026-05-21 | charter pass | Initial charter — 17 tests catalogued from audit §1.14 |
