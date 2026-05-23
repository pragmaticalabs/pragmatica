# State authority — node lifecycle endpoints

Status: ratified 2026-05-20. Implemented in commit (Phase B.2 — state authority cleanup).

## Two endpoints, two contracts

Aether exposes **two complementary views** of node state. They are not redundant — they answer different operator questions and have intentionally different semantics. Tests, dashboards, and operator tooling must pick the right one for the question being asked.

### `/api/nodes/lifecycle` and `/api/nodes/lifecycle/{id}` — KV-direct, FSM authority

| Aspect | Contract |
|---|---|
| **Source** | Direct read of `NodeLifecycleKey` from the consensus-replicated KV-Store |
| **Latency** | Bounded by consensus apply (~10ms LAN, ~30ms on follower replication) |
| **Filtering** | None — returns exactly what the FSM wrote |
| **List form** (`/api/nodes/lifecycle`) | Iterates `kvStore.forEach(NodeLifecycleKey.class, ...)` — every entry the FSM has written |
| **Single-id form** (`/api/nodes/lifecycle/{id}`) | `kvStore.get(NodeLifecycleKey)` — 404 if no entry exists |
| **What "no entry" means** | Peer is known to SWIM but the FSM has not yet committed JOINING (transient bring-up window) |
| **Normalization** | `SHUTTING_DOWN` → `DRAINING` for external viewers (`NodeLifecycleRoutes.externalStateName`). Internal FSM and NodeDeploymentManager still distinguish — the transient `SHUTTING_DOWN` write triggers self-shutdown — but the operator-visible API collapses the distinction since both states mean "node is going away" |

**When to use**:
- Verifying that an FSM transition has committed (e.g., post-action assertions in tests)
- Distinguishing "FSM wrote DECOMMISSIONED" from "MembershipView decided to drop the peer"
- Quorum/partition gate tests where the question is "did the FSM act?"
- Anything that requires authoritative FSM intent without reachability overlay

**Latency profile**:

| Source transition | Time visible at this endpoint |
|---|---|
| Leader writes KV | ~10ms LAN |
| Follower receives consensus apply | +10-30ms replication |
| Peer is up but FSM hasn't written | 404 (use `/api/nodes/status` to see SWIM-detected presence) |

### `/api/nodes/status` `cluster.nodes[]` — operator-visible derived view

| Aspect | Contract |
|---|---|
| **Source** | `MembershipView.statusOf(NodeId)` — projection over KV ∪ SWIM ∪ aggregated reachability snapshot ∪ quorum gate |
| **Latency** | Same as the slowest input. SWIM is fastest (~3 SWIM periods to detect faulty); KV-direct dominates for transition acks |
| **Filtering** | MembershipView already applies "absence ≠ demotion" (post-commit `3f3142ded`). `StatusRoutes.toNodeInfo` adds a second route-layer downgrade: if KV says ON_DUTY but aggregated reachability snapshot reports a quorum of observers as UNREACHABLE, the route returns `derivedStatus="UNKNOWN"` even though `kvState="ON_DUTY"` |
| **Two fields per node** | `kvState` (KV-direct, same source as `/api/nodes/lifecycle/{id}`) AND `derivedStatus` (the SWIM+reachability+quorum-overlaid projection). Operators can compare to see when the projection is diverging from FSM intent |

**When to use**:
- Operator dashboards — `derivedStatus` is the "is this peer healthy and reachable right now"
- "Pick an ON_DUTY peer to use as a target" (test helper `pick_non_leader` style) — `derivedStatus` filters out peers that are formally ON_DUTY but practically unreachable

**The two-field design**: the divergence between `kvState` and `derivedStatus` is informational, not a bug. When they differ:
- `kvState=ON_DUTY, derivedStatus=UNKNOWN`: cluster has lost transport to the peer but the FSM hasn't yet committed a transition. Operator sees "something is wrong"; tests can assert that the FSM has NOT yet acted.
- `kvState=DRAINING, derivedStatus=DRAINING`: FSM committed; transition observable consistently.
- `kvState="" (no KV entry), derivedStatus=JOINING`: peer is known to SWIM during bring-up; FSM hasn't committed the JOINING atom yet.
- `kvState=DECOMMISSIONED, derivedStatus=UNKNOWN`: peer was decommissioned and removed from MembershipView snapshot (UNTRACKED → UNKNOWN); the FSM transition is recorded.

## State enum value mapping

The wire-level state strings on these endpoints come from `NodeLifecycleState` (KV-Store enum). Mapping is:

| Wire value | KV state | Meaning |
|---|---|---|
| `JOINING` | JOINING | FSM is provisioning a slot for this peer |
| `ON_DUTY` | ON_DUTY | FSM has committed; peer is a full member |
| `DRAINING` | DRAINING **or** SHUTTING_DOWN | Operator-initiated graceful drain in progress (SHUTTING_DOWN collapsed for external view) |
| `DECOMMISSIONED` | DECOMMISSIONED | Drain complete; slot released |
| `FAILED_DRAIN` | FAILED_DRAIN | Drain timeout / failure; manual cleanup required |
| `UNKNOWN` | (derivedStatus only) | Peer is unreachable, untracked, or in projection lag — see `kvState` for FSM intent |

`MembershipView.MemberStatus` is a derived enum used internally and does not include `SHUTTING_DOWN` — collapse happens at the projection boundary in `MembershipView.mapKvState` (`MembershipView.java:287`). External wire output normalization happens in `NodeLifecycleRoutes.externalStateName` and `StatusRoutes.externalStateName`.

## Migration notes

This contract supersedes the pre-commit-`Phase B.2` behavior where:
- `/api/nodes/lifecycle` (list form) was MembershipView-derived (inconsistent with `/{id}` single form)
- `/api/nodes/status cluster.nodes[].lifecycleState` was a single field carrying the derived view, with no way to inspect the FSM intent without a second endpoint call
- `SHUTTING_DOWN` was preserved at the wire on KV-direct reads but collapsed in MembershipView — silent asymmetry

Wire format change: the NodeInfo record gains a `kvState` field and renames `lifecycleState` → `derivedStatus`. Hard cut for RC1; CLI helpers and integration tests updated in the same commit.

## Future hardening (not in scope for this contract)

- **B5 indexing** (`cli-gap-audit.md`): MembershipView per-peer reads iterate the KV table — fine for typical RC1 cluster sizes (5–20 nodes), should be revisited for clusters past a few hundred peers. Tracked as an RC2 concern.
- **ClusterStatusNodeInfo** (`/api/cluster/status` cluster.nodes[].lifecycleState) has the same shape question and may want the same kvState/derivedStatus split for consistency. Separate follow-up — it's a different endpoint family.
- **Bulk lifecycle endpoint variants** with filters (e.g. `?state=ON_DUTY`) are a CLI ergonomic gap tracked separately.

## References

- `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/membership/view/MembershipView.java` — the projection itself
- `aether/node/src/main/java/org/pragmatica/aether/api/routes/NodeLifecycleRoutes.java` — KV-direct list + single-id endpoints
- `aether/node/src/main/java/org/pragmatica/aether/api/routes/StatusRoutes.java` — derived view with route-layer downgrade
- `aether/docs/specs/cluster-membership-fsm-spec.md` §R6 — `SHUTTING_DOWN` legacy semantics
- `aether/docs/specs/reachability-aggregator-spec.md` — aggregated reachability snapshot input to MembershipView
- Commit `3f3142ded` (resolveOnDutyStatus) — the foundational fix that established "absence ≠ demotion" before this contract was formalized
