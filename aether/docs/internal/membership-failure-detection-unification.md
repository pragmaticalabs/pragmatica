<!--
SPDX-License-Identifier: BUSL-1.1
Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
-->

# Membership & Failure-Detection — Structural Research & Decision Log

**Status:** Living research doc (open). Started 2026-05-24.
**Branch target:** `release-1.0.0-rc1` and beyond.
**Owners:** (membership/consensus)
**Related specs:** the v1 membership-architecture spec (§16 scenario oracle) and the cluster-convergence-reconciler spec — both removed; see git history — plus [`swim-driven-topology-spec.md`](../specs/swim-driven-topology-spec.md), [`quic-transport-spec.md`](../specs/quic-transport-spec.md). **Note:** this analysis predates membership-v2 (derive-from-reality); see [`../specs/membership-architecture-v2-spec.md`](../specs/membership-architecture-v2-spec.md) for the current model.

> Purpose: track *all* considerations, options, constraints, and decisions for the membership / failure-detection redesign in one place, so we stop rediscovering the same tradeoffs. This is a decision log, not a spec — when a decision lands and stabilizes, fold it into the relevant spec and link back here.

---

## 1. Why this doc exists — the structural diagnosis

Cluster-B chaos stabilization has cost weeks of whack-a-mole: dead reconciler (codec), self-drain cascade (quorum source), QUIC backpressure drops, detection timing, reason-vocabulary fragmentation, topology over-counts, GC-vs-poll test races. Each fix was real; they keep coming. **That pattern is the signal: these are symptoms of one structural condition, not independent bugs.**

**Root condition:** membership/failure handling is **N independent control loops, each with its own clock, its own authority, and its own vocabulary, all mutating the same lifecycle state — with no single source of truth.**

Loops observed in the live system:

| Loop | Signal source | Clock / cadence | Mutates lifecycle? |
|---|---|---|---|
| `ReachabilityAggregator` | QUIC `connectedPeers` + follower pong observations, quorum `(N/2)+1`, TTL 15s | ping cadence + leader warmup | via FSM (leader-gated) |
| **ClusterSync ping-pong** (1s) | all-cores gossip **backbone**: carries SWIM-health obs + QUIC-connectivity obs + leader reachability snapshot + metrics + readiness; 3-miss (~3s) ping-timeout → local QUIC disconnect + eviction hint | 1s | no — feeds aggregator/FSM (it is the *substrate*, not a separate decider) |
| SWIM | gossip suspicion → FAULTY → DEPARTED; output **injected into the ping-pong** as `PeerHealthObservation` | own probe (~1s) / suspect 10s | via FSM |
| `LifecycleReconciler` rules | KV scan (e.g. `JoiningTimeout`) | 10s tick + per-rule budgets | **direct KV write** (until the 2026-05-24 fix) |
| `ClusterTopologyManager` (CTM) | deficit / auto-heal / provisioning | reconcile tick | yes (provisioning slots) |
| `SelfDrainCoordinator` | local quorum view | immediate | self-`halt(2)` |
| `DecommissionedAtomGc` | retention | 1-min | removes STOPPED atoms |
| `MembershipFsm` | **intended** convergence point (reducer per `(state,event)`) | event-driven (HLC) | yes — but not sovereign |

There are also **three distinct "topology" notions** that are easy to conflate:
- **Decision plane** — KV `NodeLifecycleKey` entries (`kvTrackedPeersSupplier`, `AetherNode.java:1030-1042`): "who we track."
- **Observation plane** — QUIC `connectedPeers` (`AetherNode.java:1054`): "who we can reach right now."
- **Consensus quorum** — fixed configured `clusterSize` (`TopologyManager.java:44-49`, read by `SelfDrainCoordinator`): "how many votes split-brain-safe."

And **four reason vocabularies** for the same terminal transition: `transport-failure`, `swim-faulty`, `swim-departed`, `join-timeout` (canonical, in `ClusterMembershipReducer`), plus `operator-forced` (what the reconciler stamped when it bypassed the FSM).

### Symptoms → which loop interaction produced them
- **"got 6" over-count** — provision-replacement races decommission-of-dead; *add new + remove old* is not atomic.
- **GC-vs-poll `<absent>`** — `DecommissionedAtomGc` (1-min) reclaims a STOPPED atom before a test/operator polls it; mutable state is the contract surface.
- **Budget misses by 1–2s** — *which* detector wins is nondeterministic; the fallbacks (45s JoiningTimeout / ~61s ON_DUTY SwimDeparted / 90s) sit right at contract edges.
- **Reason fragmentation** — each loop stamps its own vocabulary; the reconciler bypassed the one reducer that assigns canonical reasons (`MembershipFsm.applyEffect(EmitDomainEvent)`, `MembershipFsm.java:971`).
- **Leader-warmup / `LocalDisconnect` fragility** — the aggregator loop has startup transients SWIM doesn't; they disagree during exactly the churn window that matters.

**The deepest tell:** `MembershipFsm` was *designed* to be the convergence point (a reducer cell for every `(state,event)` — `ClusterMembershipReducer.applyJoining/applyOnDuty/...`). But it is **not sovereign**: detectors bypass it, it's leader-gated and warmup-fragile, and CTM / self-drain / GC mutate state on their own. We keep strengthening it one bypass at a time. **The structural move is to make it sovereign.**

---

## 2. The scenario oracle (acceptance criteria)

Any structural change must satisfy **all 20 scenarios** in the v1 membership-architecture spec §16 (S01–S20; spec removed, see git history). This is the test oracle — a redesign is only "done" when it covers these, and they are exactly what cluster-B integration exercises.

| ID | Scenario | Stresses |
|---|---|---|
| S01 | JOINING-window kill (before SWIM HEALTHY) | join-deadline reclaim, reason tagging |
| S02 | ON_DUTY single non-leader kill | steady-state detection |
| S03 | Two simultaneous non-leader kills (<1s) | multi-failure quorum |
| S04 | Brief transport flap < 5s (reconnects) | **no false decommission** |
| S05 | 2-vs-3 partition (majority side) | partition handling |
| S06 | Partition heal (before minority exits) | reconvergence, no over-count |
| S07 | Graceful operator drain | drain path |
| S08 | Drain timeout (hard deadline) | failed-drain path |
| S09 | Drain during partition | drain × partition |
| S10 | Operator force-decommission | operator authority |
| S11 | Restart inside revival TTL (same NodeId) | identity revival |
| S12 | Restart outside revival TTL (post-GC) | GC retention boundary |
| S13 | SWIM-only failure (QUIC OK) | detector disagreement |
| S14 | Transport-only failure (SWIM HEALTHY) | detector disagreement |
| S15 | Cold-start formation (5 simultaneous) | bootstrap |
| S16 | Cold-start + simultaneous kill | bootstrap × failure |
| S17 | Aggregator quorum lost (UNKNOWN / no snapshot) | detector degradation |
| S18 | Leader kill + re-election | leadership transfer |
| S19 | Quorum-loss → self-drain (≥(N/2)+1 unreachable ≥8s) | self-drain |
| S20 | Self-drain → restart → rejoin | recovery |

**Note S13 vs S14** explicitly encode *detector disagreement* as required behavior — the system is *specced* to have two detectors (SWIM and transport) that can disagree. Any "collapse to one detector" proposal must re-express what S13/S14 are testing, or argue they fold away.

---

## 3. Hard constraints (what any redesign must respect)

These came out of the 2026-05-24 design review (and are the reason "just delete SWIM" is not free):

- **C1 — Transport precedes consensus.** Rabia cannot start without transport. *Some* bootstrap advertising mechanism must exist before consensus/membership is up. Today: a joining node broadcasts SWIM **ANNOUNCE** datagrams at 1 Hz to the static `PEERS` list until quorum connectivity (`swim-driven-topology-spec.md` §5.2). That ANNOUNCE is the **only** path a NodeId enters QUIC's address book — KV/PEERS-direct dialing was deliberately removed (§"What Was Removed", lines 217-225).
- **C2 — SWIM has TWO jobs, not one.** (1) **Discovery/advertising** — ANNOUNCE-based bootstrap + telling QUIC whom to dial (`JoinAnnounced→connect`, `FaultyObserved→disconnect`, `swim-driven-topology-spec.md` §6). (2) **Failure detection** — suspicion→FAULTY→DEPARTED. **Eliminating SWIM requires replacing BOTH.** This is the crux: the failure-detection conversation keeps forgetting the discovery role.
- **C3 — QUIC as-configured is NOT a sufficient detector.** Only `maxIdleTimeout(30s)`, **passive** (`quic-transport-spec.md` §3.3:139-141). No active keepalive/PING: a silently-dead peer (hung process, no FIN) isn't noticed until a send is attempted + 30s. Slower than SWIM's ~15s, and a bare transport signal can't distinguish **partition from death** on its own (the same limitation the aggregator has). QUIC *could* be configured with active PING keepalive — but that just makes QUIC *a* probe, see C5.
- **C4 — Consensus-liveness has a steady-state hole.** Rabia rounds happen only when there are proposals; a quiet steady-state cluster produces no consensus traffic, hence no liveness signal. Using "who's acking proposals" as the failure detector therefore needs **synthetic heartbeat rounds** in steady state — which is a ping by another name (see C5).
- **C5 — You cannot eliminate periodic liveness probing.** SWIM gossip, QUIC keepalive-PING, aggregator ping-pong, and "Rabia heartbeats" are **the same mechanism wearing different hats**: *something* must generate periodic traffic to detect silent death. **The achievable win is collapsing to ONE probe substrate and making it authoritative — not reducing to zero.**
- **C6 — S04 forbids trigger-happy detection.** A <5s transport flap must NOT decommission. Any faster detector must still debounce transient blips.
- **C7 — The current detector is a *2-plane co-confirmation*, and that's intentional CFT safety.** Today decommission of an ON_DUTY peer requires BOTH planes to agree: `ReachabilityGate` only confirms `TransportUnreachable` when local SWIM is *also* non-HEALTHY (`ReachabilityGate.java:11-27`), and the aggregator additionally requires a `(N/2)+1` quorum of observers. This is deliberately CFT (not BFT): it stops a single node — or a stale CONNECTED flap — from unilaterally evicting a peer. S04 (flap), S13 (SWIM-only), S14 (transport-only) are precisely the tests of this co-confirmation. **Any collapse to one plane must replace the co-confirmation's debounce/false-positive protection** (e.g. φ-accrual suspicion), not just delete a plane.
- **C8 — The metrics channel is permanent.** The ping-pong's `metrics` payload feeds the scaling mechanism (CTM scale-up/down decisions); it is load-bearing and stays regardless. **Therefore the 1s ping-pong backbone exists no matter what — failure detection riding it is "already paid for."** This argues for consolidating detection *onto* the ping-pong rather than maintaining a separate probe plane.
- **C9 — Detection must not depend on a leader (or any single control-plane role); the leader can *be* the failure.** Today the ping-pong's *fold/decision* is leader/spokesman-gated — only the leader ingests pongs and produces the canonical `AggregatedReachabilitySnapshot` (`AetherNode.java:1065-1067, 1123-1135`). So detection **stalls during exactly the window it's needed most**: leader death / re-election / new-leader warmup. This is the mechanism behind the S01 budget misses *and* S18. SWIM, by contrast, is **leaderless** — every node detects independently via gossip; that is its decisive structural advantage. **Architectural consequence — separate sense from decide:** *detection should be **AP** (leaderless, always-on, eventually-consistent); only the membership **decision/write** should be **CP** (consensus-ordered, single-writer FSM — Pillar 1).* The current design wrongly enforces consistency at the *detection fold* (leader-canonical snapshot) instead of at the *write*. Centralizing detection at the leader is the root of the warmup/churn fragility we kept hitting.

---

## 3.5 Refined model — it's not "N detectors", it's a 2-plane co-confirming detector over one 1s backbone

The investigation (2026-05-24) corrected the naive "redundant detectors" framing. The real shape:

- **One gossip backbone — the ClusterSync ping-pong (1s, all-cores).** Targets come from `MembershipDecision`/consensus topology (not `connectedPeers`, not SWIM). The **ping** carries: full metrics map, fencing terms, the *leader's* `AggregatedReachabilitySnapshot`, and `evictionHints`. The **pong** carries: `PeerConnectivityObservation`s (CONNECTED/DISCONNECTED/STALE), `PeerHealthObservation`s (HEALTHY/SUSPECTED/FAULTY), `readyCandidate`, metrics, lifecycle-state string. (`ClusterSyncMessage.java:43-49, 100-109`.)
- **Two observation planes ride that backbone**, folded by *one* `ReachabilityAggregator` (which maps SWIM SUSPECTED/FAULTY **and** QUIC DISCONNECTED/STALE both → UNREACHABLE, `ReachabilityAggregator.java:148-149`):
  - **Plane A — QUIC connectivity:** `PeerConnectivityReporter` (installed every node, `AetherNode.java:2368`) + a 5s self-emission from `connectedPeers()` + the ping-pong's own **3-miss (~3s) timeout** (`ClusterSyncContext.java:288-309`) which locally disconnects the peer and broadcasts an eviction hint.
  - **Plane B — SWIM:** `CoreSwimHealthDetector` probes independently and **pushes** its HEALTHY/SUSPECTED/FAULTY into the same pong stream (`SwimHealthContext.java:231`).
- **One fold policy:** aggregator quorum `(N/2)+1` + TTL 15s, leader/spokesman-gated ingest, then `MembershipFsm` + `ReachabilityGate` co-confirmation → KV write.

**Consequences for the redesign:**
1. The ping-pong is **not redundant and not removable** — it's the backbone, and C8 pins it permanently (metrics/scaling). The "general-purpose gossip" is really *metrics + leader-derived reachability snapshot + eviction hints* piggybacked on the metrics channel; it is **not** authoritative membership flooding (KV/Rabia remains source of truth, `AggregatedReachabilitySnapshot.java:18-21`).
2. The ping-pong **already is a fast (~3s) detector primitive** — but deliberately *advisory*: a missed-ping strips the false-REACHABLE vote and disconnects locally; it does not itself decommission (that needs quorum + SWIM co-confirm).
3. So **SWIM's distinct value reduces to two things the ping-pong does NOT provide:** (i) **discovery/ANNOUNCE bootstrap** — the ping-pong's targets come from already-known consensus topology, so it cannot bootstrap a node into the address book (C1/C2); (ii) **the second confirmation plane** (C7). Everything else SWIM contributes (per-peer suspicion) the ping-pong already produces.

**Reframed unification target:** keep the ping-pong as the permanent 1s detection substrate; replace the fragile fold *policy* (quorum + TTL + leader-warmup) and/or the SWIM second plane with a principled debounce (**φ-accrual on per-peer ping-miss timing**) that preserves C7's false-positive protection on a single plane; and **decompose SWIM into a thin discovery-only layer** (ANNOUNCE bootstrap) so its detection role can retire without losing C1/C2.

---

## 4. Pillars of a structural fix

**Pillar 1 — One writer (FSM sovereignty).** Every detector (aggregator, SWIM, reconciler, CTM, self-drain *intent*) becomes a pure **event producer**; `MembershipFsm` is the **sole** mutator of lifecycle KV state, under one HLC clock. The 2026-05-24 `JoiningTimeout` fix is the first brick (reconciler now `enqueueOperatorEvent` instead of writing KV). *Finish it:* audit every lifecycle `Put`/`Remove` and route through the FSM. This dissolves bypass races and unifies the reason vocabulary by construction (reasons exist only in the reducer). **Highest-leverage, RC1-sized.**

**Pillar 2 — One probe substrate (collapse the detectors).** SWIM and the ReachabilityAggregator are two overlapping liveness detectors that disagree during churn. Pick/forge one authoritative substrate (see §5 options). Must respect C1–C6, and must re-express S13/S14.

**Pillar 3 — Event stream is the contract (not mutable state).** The GC-vs-poll flake and budget-poll races exist because observers read *transient mutable KV*. Evidence in miniature: the smoking-gun test (asserts on the `NODE_FAILED` *domain event*) passed reliably twice; the budget test (polls KV state) flaked three different ways. Make the **append-only lifecycle event log the observability contract** (we already have `audit.lifecycle.commands` + `RecentCommandsBuffer` — promote it to *the* surface) and make chaos tests assert on **events + convergence invariants** ("eventually exactly N ON_DUTY, quiesced"), not wall-clock state polls.

---

## 5. Design options (with tradeoffs)

### 5A. The "one probe substrate" question (Pillar 2)

| Option | Idea | Pros | Cons / risks | C-constraints |
|---|---|---|---|---|
| **2a — SWIM sovereign, aggregator demoted** | Keep SWIM as the single liveness + discovery authority; make `ReachabilityAggregator` purely **advisory/diagnostic** (or delete it). | Least disruptive; SWIM already does discovery (C2) + detection + debounce; removes the warmup-fragile, quorum-brittle second loop that caused most timing disagreements. | Must prove SWIM alone covers S14 (transport-only failure where SWIM still HEALTHY) — possibly by feeding QUIC connection-loss as a SWIM input. | Satisfies C1/C2 natively. |
| **2b — φ-accrual detector** | Replace binary REACHABLE/UNREACHABLE + fixed TTL/quorum with a continuous per-peer suspicion value + one threshold (Hayashibara). | One principled, self-tuning signal; natural debounce (C6); removes magic `(N/2)+1`+15s+SWIM-timer interplay. | New mechanism; still needs a probe substrate underneath (gossip or ping); doesn't by itself solve discovery (C2). | Needs C5 substrate. |
| **2c — Consensus-liveness** | Derive liveness from Rabia participation (who acks rounds). | Most authoritative signal, "free" when traffic exists; could retire a separate detector. | **C4 steady-state hole** → needs heartbeat rounds (= a ping); doesn't solve discovery (C2); couples membership to consensus internals. | Blocked by C4 without heartbeats. |
| **2d — QUIC active-keepalive substrate** | Enable QUIC PING keepalive; use connection state as the probe. | Reuses the transport we already maintain; point-to-point, no gossip fan-out cost. | C3: needs active keepalive added; transport-only signal can't distinguish partition/death; doesn't solve discovery (C2); per-connection (no membership dissemination). | Needs C2 discovery layer + debounce (C6). |
| **2e — Ping-pong substrate + φ-accrual + SWIM→discovery-only** | Keep the (permanent, C8) 1s ping-pong as the detection substrate; replace the aggregator's quorum+TTL fold and the SWIM second-plane with a **φ-accrual** suspicion over per-peer ping-miss timing; reduce SWIM to a thin ANNOUNCE/discovery layer. | Reuses the substrate we pay for anyway (C8); one principled, self-tuning signal with natural debounce (C6/C7); retires the warmup-fragile quorum policy *and* the redundant probe plane; keeps discovery (C2). | Must prove φ-accrual on one plane preserves C7's false-positive protection across S04/S13/S14; biggest behavioral change to detection. | Best fit to C1–C8; the current working hypothesis. |

**Working hypothesis (to validate, post 3.5/C8/C9):** the decisive property is **C9 — detection must be leaderless.** That re-weights the options: the fragile part is not "two detectors," it's that the ping-pong's *fold is centralized at the leader*. So the target is **leaderless sense + CP decide**: every node forms its own per-peer suspicion (φ-accrual over direct ping-miss timing + gossiped hints), and *any* node may propose a decommission to the single-writer FSM, which orders it via consensus (Pillar 1). Two ways to get leaderless sense, both keeping the permanent ping-pong backbone (C8):
- **(i) Keep SWIM as the leaderless detector, demote the leader-gated aggregator.** SWIM already has C9 for free; lowest behavioral risk; SWIM's leaderlessness flips from "redundant" to "the point."
- **(ii) Make the ping-pong fold itself leaderless** (local φ-accrual per node, gossiped quorum, propose-to-consensus), then shrink SWIM to discovery-only.

This *revises* the earlier "centralized φ-accrual 2e" — a φ-accrual that still runs only on the leader would reproduce the C9 fragility. The earlier "2a (keep SWIM)" instinct is back in contention precisely because of C9. **Spike-1 must compare leaderless variants and measure detection continuity across leader-kill/re-election (S18) and warmup, not just steady-state latency.**

### 5B. Atomic replacement (kills "got 6")
Model replacement as an **atomic slot swap with generation fencing**: a provisioning *slot* has exactly one occupant; a replacement **supersedes** its predecessor in a single FSM transition (old occupant fenced by generation/epoch), instead of "add new + remove old" as two racing ops. Directly retires the over-count symptom (S06/S16/S20).

### 5C. Timing-invariant composition
Derive all lifecycle timers (join deadline, reclaim budget, GC retention, detector window, aggregator TTL) from a small base parameter set with a **composition invariant**: `GC_retention > max_detection_budget > propagation`. Makes GC-can't-race-a-poll and reclaim-can't-undercut-its-deadline true *by construction* rather than by tuning. (We currently hand-tune: reclaim 45s, FSM join deadline 60s, GC 60s, test budget 90s — these were aligned manually and remain fragile.)

### 5D. Deterministic simulation testing (highest ROI, highest cost)
The reason this has cost weeks is non-determinism: every chaos run lands differently (3 runs of S01 → JOINING/45s, ON_DUTY/61s, absent/GC'd-92s) — un-debuggable by construction. **Forge is already single-JVM** — the ideal substrate to run the whole cluster on a controlled logical clock + simulated network where a **seed reproduces the exact interleaving** (FoundationDB / TigerBeetle style). Converts flaky chaos into deterministic, bisectable tests and gives cheap exhaustive coverage of S01–S20 forever. Treat as a funded spike; it changes the economics of everything after.

### 5E. φ-accrual design sketch (the option-2e detector)

The continuous, leaderless detector that satisfies C5C/C6/C7/C8/C9 + the anti-flap lesson simultaneously.

- **Per-(observer, target) state:** sliding window of the last K pong inter-arrival intervals → running mean μ, stddev σ (K ~ 100; bounded O(K) memory per peer). Heartbeat source = the pongs the node *already* receives at 1s (C8); a pong requires the peer to run code, so this is an **app-level** liveness signal (covers S13 hung-process).
- **Suspicion:** at time t, `elapsed = t − lastArrival`; `φ = −log₁₀(1 − F(elapsed))` where F is the CDF of the modelled inter-arrival distribution. φ ≈ 0 just after a pong, rises monotonically with silence. **No vote to corrupt** → the stale-CONNECTED-flap bug (the aggregator's raison d'être) cannot recur; silence is silence.
- **Thresholds (decoupled consumers — the "accrual" payoff):** `Φ_evict` (e.g. 8) drives decommission; optional `Φ_degraded` (e.g. 5) feeds CTM/scaling "degraded" hints. One sensor, many readers (synergy with C8's permanent metrics consumer).
- **Leaderless gossip:** each node piggybacks its **suspected set** `{X : φ(X) > Φ_evict}` (sparse — not the full O(N) φ vector) onto the pong it already sends. Bounds payload at O(suspected). This gives every node "how many of us can't reach X" → partition-vs-death discrimination, **subsuming SWIM indirect probing**, leaderlessly.
- **Decision (the only CP step):** a node proposes `TransportUnreachable(X)` to the single-writer FSM when it observes ≥`(N/2)+1` distinct observers suspecting X (own + gossiped). Rabia orders + dedupes (idempotent on STOPPED). Optional: lowest-id suspecter proposes, to avoid a herd.
- **Coverage split (important):** φ covers **established/ON_DUTY** peers (they have heartbeat history). A **never-connected JOINING** peer has *no* history → φ undefined → **JOINING reclaim stays deadline-based** (`JoiningTimeout`, the 45s path). φ complements it, does not replace it. Cold-start (S15/S16): warmup prior — never suspect a peer with `< K_min` samples.
- **Self-drain (S19) falls out for free:** a node seeing ≥`(N/2)+1` of its peers at φ > Φ_evict concludes it is on the minority side and self-drains — same signal, local leaderless decision.
- **Clock:** local monotonic only (arrivals measured at self) → **no cross-node clock sync** required.
- **Distribution caveat:** classic φ-accrual assumes ~normal inter-arrivals; real RTT is often heavy-tailed → may need the exponential/heavy-tail variant or generous σ. Pick from measured data (Spike-1).

---

## 6. Proposed sequencing

- **RC1 (finish the foundation, bounded):**
  - Pillar 1 — FSM single-writer: audit & route every lifecycle mutation through the FSM (extend the `JoiningTimeout` pattern to CTM scale-down, self-drain, drain-coordinator, bootstrap).
  - Pillar 3-lite — convert cluster-B chaos assertions from KV-state polls to event + invariant assertions (retire GC-vs-poll/edge-budget flakiness).
  - 5B — atomic slot-swap for replacements (kills over-count).
  - 5C — timing-invariant cleanup.
- **RC2 / research spikes:**
  - **Spike-1 (measurement):** instrument & compare detection latency + false-positive rate of SWIM vs aggregator vs QUIC-keepalive vs consensus-heartbeat on the S0x scenarios. Decides 5A direction with data, not opinion.
  - **Spike-2:** deterministic simulation harness on Forge (5D).
  - Then Pillar 2 (probe-substrate collapse) per Spike-1.

### 6.1 Spike-1 plan — φ-accrual shadow-mode evaluation (built as a production seed)

Goal: decide option-2e vs status quo **with data**, via a detector that ships shadow-first and can be promoted — not a throwaway. Designed so each phase is independently valuable and reversible.

**Prerequisites / dependencies**
- **Fair comparison needs Spike-2 (or at least determinism).** Flaky remote runs can't compare φ vs current fairly (3 runs of S01 landed 3 ways). Spike-2's seeded sim — or, minimally, the S01–S20 harness with per-observer logging — is a *precondition* for trustworthy Spike-1 numbers. Treat Spike-2 as the enabler, not a parallel nicety.
- **Pre-step (zero-risk, do first): capture real inter-arrival traces.** Log per-(observer,target) pong inter-arrival intervals across S01–S20 on a live cluster *before* fixing the distribution model + Φ. This is read-only and tells us whether normal/heavy-tail, and a defensible Φ/K.

**Build phases (each reversible)**
- **A — Shadow:** compute φ on every node from existing pong arrivals; log φ + its verdict; compare to the live aggregator's verdict on identical runs. **No action taken.** Gossip the suspected-set in the pong behind a flag, **codec/`ENVELOPE_FORMAT_VERSION`-versioned and backward-compatible** (a node not emitting φ must still interoperate during rolling upgrade). Zero production risk — this is the spike's spine.
- **B — Canary:** act on φ proposals for decommission behind a flag, on a test cluster; aggregator still runs; **kill-switch reverts to aggregator**.
- **C — Cutover:** φ becomes the proposer; aggregator → advisory; SWIM → discovery-only. **Only after S01–S20 are green and gates pass.**

**Measurement (decision gate), per scenario S01–S20**
- Detection latency (per-observer + cluster: kill → ≥quorum φ>Φ).
- **Detection continuity through leader-kill (S18) + warmup** — the property the current leader-gated fold *fails*; the leaderless detector *must* hold. This is the headline metric.
- False-positive rate on S04 (5s flap) — must stay ≈0.
- Partition/quorum (S05/S06/S17), self-drain (S19/S20), detector-disagreement (S13/S14).
- Side-by-side φ-verdict vs aggregator-verdict from the shadow logs.

**Production-readiness gates (for inclusion)**
- **Config (CLAUDE.md rule):** `Φ_evict`, `Φ_degraded`, window `K`, `K_min`, distribution model, gossip cadence — all configurable + `TimeSpan` where temporal; defaults pass S01–S20.
- **Observability (Pillar 3):** per-peer φ exposed via `/api/status` + metrics + the audit/event surface — a φ time-series is the debugging gold we lacked this session.
- **Backward compat:** pong φ-field codec + envelope bump; mixed φ/no-φ rolling-upgrade interop verified.
- **Resource bounds:** window memory O(K·N); gossip O(suspected) per pong — verify/cap at target scale (avoid O(N²) full-vector gossip).
- **Consumer alignment:** `SelfDrainCoordinator` (S19) and CTM scaling read the new signal consistently (Φ_degraded for "degraded", Φ_evict for death).
- **Scope guard:** do **not** remove SWIM in the spike (shadow alongside); discovery/ANNOUNCE untouched; JOINING reclaim stays deadline-based.

**Success criteria (promote vs iterate):** φ-leaderless ≥ current on S02/S03 latency, ≤ current false-positives on S04/S13/S14, **maintains detection through S18** (current fails this), and correct S05/S06/S19 — then promote to canary. Otherwise iterate the distribution model / Φ, or fall back to option (i) keep-SWIM.

---

## 7. Open questions

1. **5A vs status quo:** can SWIM (fed QUIC connection-loss) cover S14 alone, letting us delete the aggregator? Or is the aggregator's quorum-vote genuinely needed for partition cases (S05/S17)?
2. **C4 mitigation cost:** if we ever want consensus-liveness, how cheap are Rabia steady-state heartbeats, and do they perturb the leaderless protocol?
3. **Discovery (C2) decoupling:** can bootstrap/advertising (ANNOUNCE) be separated from the failure detector so the two SWIM jobs can evolve independently? A thin "membership gossip / address book" layer + a separate "liveness probe."
4. **Generation fencing scope:** does slot-swap need a new epoch field on `NodeLifecycleValue`, or can `observedCoreEpoch` carry it?
5. **S04 debounce budget** vs **S01/S19 fast-reclaim** — what's the principled single knob (φ threshold?) that serves both "don't react to 5s flaps" and "reclaim a dead JOINING node fast"?

---

## 8. Decision log

| Date | Decision | Rationale | Refs |
|---|---|---|---|
| 2026-05-24 | **Register `CommandLifecycleEvent` codec at system level** (`NodeCodecs`/`WorkerCodecs`), not slice-DI. | Deployment module isn't a slice; the dead reconciler tick was the root of "cleanup never happens." | commit `a2dfeb0f7` |
| 2026-05-24 | **Do NOT catch the serializer "no codec" exception.** | `Serializer` exceptions are by-design fatal dev/test guards (missing registration); catching would hide regressions. Registration is the fix. | `Serializer.java` design note |
| 2026-05-24 | **Drop Fix C** (excluding JOINING from the aggregator quorum denominator). | "Nodes need to include JOINING to start syncing" — JOINING must stay in the tracked set; reducing the denominator works against the join/sync inclusion. Detection handled via Pillar-1 routing instead. | this doc §3 C2 |
| 2026-05-24 | **B — reconciler `JoiningTimeout` routes through the FSM** (`enqueueOperatorEvent` → `SwimDeparted`), emitting `reason=swim-departed`, not `operator-forced`. | Honest reason (SWIM-driven trigger); first brick of Pillar 1 (FSM single-writer). Validated: smoking-gun passed twice. | commit `247a55fa4` |
| 2026-05-24 | **Reclaim budget 90s→45s** (`JoiningTimeout.BUDGET_MULTIPLIER` 1.5→0.75); FSM join deadline kept 60s. | Faster reclaim of demonstrably-gone (SWIM-faulty/absent) JOINING peers without shortening the healthy-node join window. | commit `247a55fa4` |
| 2026-05-24 | **S01 test budget 25s→90s.** | The budget is racy by construction (JOINING/45s vs ON_DUTY-SwimDeparted/~61s vs GC-reclaim); 90s covers both fallback paths. Test now asserts on the domain event (robust), budget on state (racy) — see Pillar 3. | `test-joining-window-kill.sh` |
| 2026-05-24 | **Adopt FSM sovereignty (Pillar 1) as the RC1 structural direction; fund Spike-1 (detector measurement) + Spike-2 (deterministic sim) for RC2.** | Dissolves the race class rather than re-timing it; matches the single-writer principle. *(pending confirmation)* | this doc §6 |
| 2026-05-24 | **Spike-2 substrate VALIDATED** (in-process Ember/single-JVM chaos). 5-node cluster forms in ~1.2s; force-kill of a non-leader → transport detection (connectedPeers 4→3) in **~4.6s**; **lifecycle decommission NOT observed within 15s** (consistent with Docker's ~61s ON_DUTY SWIM-departed path) — i.e. the substrate *reproduces* the prod behavior. Whole run 58s, no Docker, no active-phase instability. | Gives the fast debug loop the redesign + Spike-1 need; the pre-alpha in-process tests were dropped as "unreliable" but this run was clean → the unreliability was likely the (now-fixed) codec/self-drain/backpressure bugs, so in-process testing is plausibly revivable. | `MembershipChaosSpikeTest`; existing `ClusterFormationTest` (forms in ~7s, 3 nodes) |
| 2026-05-24 | **Spike-2 FULL CYCLE measured** (post-cooldown steady-state non-leader kill): transport-detect **~5.0s**, lifecycle decommission (`kvState`→STOPPED, onDuty 5→4) **~15.2s**, auto-heal replacement ON_DUTY (onDuty→5, connectedPeers→4, replacement `ch-6`) **~20.2s**. Full detect→decommission→heal cycle **~20s in-JVM**; test 83s, no Docker. Decommission ~15s here (QUIC-miss-promotion path, threshold 10 ≈ 10s after the ~5s detect) — *faster than* Docker's ~61s on the S01 JOINING-race, which supports that the Docker slowness was scenario/leader-churn-specific (C9), not inherent to detection. | In-process is **fast + faithful** → validated as the dev loop for the redesign and as the substrate for a *same-scenario* φ-vs-current comparison (Spike-1). AutoHealConfig.DEFAULT: no enable flag (always on), 15s startupCooldown, 10-miss QUIC promotion, 60s decommission retention. | `MembershipChaosSpikeTest` |
| 2026-05-24 | **NEW BUG found by Spike-2 (in <90s) — now FIXED + verified.** The auto-heal replacement reaching ON_DUTY triggered `IllegalStateException: Illegal (state,event): state=OnDuty, event=SlotClaimed` (`ClusterMembershipReducer.applyOnDuty:178`, via `MembershipFsm:770`), thrown uncaught into the executor (one per surviving peer's FSM). A late/duplicate `SlotClaimed` against an already-ON_DUTY peer is benign (auto-heal re-claim / re-delivery), so the cell is now an **idempotent `nop`**, not a throw. Note: there was an explicit test (`onDuty_slotClaimed_isErr`) asserting the *old* illegal behavior — so the invariant was deliberate but wrong in practice (it fired on the normal auto-heal path). Re-ran the spike: 0 exceptions, the 5 SlotClaimed events now flow as benign INFO no-ops, full cycle intact. | Closed-loop demonstration of the substrate's value (found→fixed→verified in ~83s runs) and a concrete **Pillar-1** increment (the sovereign FSM must tolerate redundant/late events — a throwing reducer cell aborts the tick, same shape as the codec bug). | `6ae906fe8`; test `onDuty_slotClaimed_isNop_idempotentReDelivery` |
| 2026-05-24 | **Defer φ-accrual to #231; S01 fix is the DECIDE-plane tombstone, not detection.** Docker S01 trace was decisive: `docker kill` *closes* the socket, detection fires correctly (`OnDuty→Stopped`, `NODE_FAILED ×2`), but the dead id is re-projected `<absent>→ON_DUTY` in ~2s via `ForceOnDuty(Untracked)` → `untrackedDirectToOnDuty` (reducer:302), triggered by the node's self-advertised `readyCandidate` (`NodeReadinessTracker`, per-node) folded by the leader's `ClusterSyncPongSignalFan.fanIfLeader:66`. So S01 is a re-promotion/sovereignty defect, not a SENSE gap. φ is off-plane (SENSE), untuned (premature-eviction risk right after the `e6367986d` cascade fix), and validated against a now-falsified black-hole model. | Avoids riding an untuned detector into RC1 on a bug it doesn't fix; preserves the SENSE/DECIDE separation. φ work kept staged on `wip/membership-tombstone-base` under #231. | handover §10; investigator driver-map |
| 2026-05-24 | **Capture the hierarchical SENSE-plane fusion architecture (§12).** One plane fuses QUIC edges + ping-pong + SWIM, shapes via φ *when warm* (correct without φ, better with it), emits a debounced ordinal `Reachable→Suspect→Unreachable` per-node stream + advisory raw-φ side-channel; two layers (per-link sensing → disseminated per-node verdict) for asymmetry; uniform across tiers with tier-specific source sets; the community structure makes φ's sample-rate viable. Carries the C9-leaderless + Lifeguard-local-health + boundary (SENSE never writes lifecycle) constraints. | Durable RC2 target distilled from the φ-deferral discussion; bounds the design so it can't drift into the N-loops trap or re-create the live-eviction cascade. RC2+, gated behind Spike-1/Spike-2. | this doc §12 |

---

## 9. Validated facts (anchors for future readers)

- `ReachabilityAggregator.foldSelfObservations` marks any `topologySupplier` peer not in `connectedPeers` as UNREACHABLE — **no prior-connection edge required** (`ReachabilityAggregator.java:220-232`). But `derive` needs `unreachable >= (N/2)+1` *observers* (`:289-298`); a single self-fold = 1 observer → degrades to UNKNOWN. Quorum is assembled at the leader from follower pongs (leader-gated, `AetherNode.java:1065-1067, 1113+`).
- Aggregator quorum denominator = `kvTrackedPeersSupplier.size()` (all non-STOPPED incl JOINING) — **separate object** from the consensus quorum (`TopologyManager.quorumSize()` over fixed `clusterSize`). Widening/narrowing one does not touch the other.
- The `reason=` domain-event line is emitted **only** by `MembershipFsm.applyEffect(EmitDomainEvent)` (`MembershipFsm.java:971`). Direct KV writers (pre-fix reconciler) produce no reason line — this is why FSM routing is mandatory for canonical reasons.
- `applyJoining` reducer cells (`ClusterMembershipReducer.java:158-169`): `SwimDeparted→swim-departed` (:162), `JoinDeadlineExpired→join-timeout` (:165), `TransportUnreachable→transport-failure` (:167); `SwimFaulty→nop` (:161, SWIM-faulty alone does NOT decommission a JOINING node).
- QUIC: `maxIdleTimeout(30s)` passive only, no active keepalive (`quic-transport-spec.md` §3.3).
- ClusterSync ping-pong: 1s (`TimeoutsConfig.java:93-103`, `pingInterval` default `timeSpan(1).seconds()`), all-cores, targets from `MembershipDecision`/consensus topology (`ClusterSyncSchedulerAdapter.java:250-268`); ping carries metrics + leader `AggregatedReachabilitySnapshot` + eviction hints; pong carries `PeerConnectivityObservation`s + `PeerHealthObservation`s + `readyCandidate` + metrics (`ClusterSyncMessage.java:43-49,100-109`). 3-miss (~3s) timeout disconnects locally + broadcasts eviction hint but does NOT itself decommission (`ClusterSyncContext.java:288-309`). SWIM output is injected into the pong stream (`SwimHealthContext.java:231`); both planes fold in one aggregator. `metrics` payload is consumed by the scaling mechanism (permanent, C8).

---

## 10. References
- Specs: v1 membership-architecture (§16 S01–S20) and cluster-convergence-reconciler — both removed, see git history; swim-driven-topology-spec.md (§5.2 ANNOUNCE, §6 SWIM→QUIC), quic-transport-spec.md (§3.3).
- Session handover: `progress/session-handover-2026-05-24.md` (codec/DHT/backpressure/self-drain chain).
- Commits: `a2dfeb0f7` (codec revival), `247a55fa4` (FSM-routed reason + reclaim budget), `6ae906fe8` (SlotClaimed-nop), `f1a451958`/`b0d3bfa1d` (Spike-2 harness).
- Tracking issues: **#230** (Pillar-1 FSM sovereignty, `rc1`), **#231** (Spike-1 φ-accrual shadow eval), **#232** (revive in-process Ember chaos substrate).
- Decision (2026-05-24): leaderless-SENSE / CP-DECIDE invariant + φ-accrual endpoint **confirmed**; reducer `illegal()`-cell audit **deferred until after Spike-1** (event alphabet changes if φ-accrual lands).

## 11. Spike-1 first results (2026-05-24)

Measured in-process (Ember single-JVM, `MembershipChaosSpikeTest`), φ-accrual shadow fed by a survivor's pong stream vs the current aggregator, same non-leader kill:

- **φ-accrual is the fastest signal — and it beats the current path.** φ-suspect **1844 ms** vs aggregator transport-detect **4532 ms** → φ ~2.7× / **2.7 s faster**. φ climbed 0 → 1.25 (t+1s) → saturated 9.0 (t+2.2s). This is the headline data point for #231: φ-on-heartbeats detects a dead peer in <2s.
- **Precondition bug found + fixed:** the ClusterSync metrics ping-pong was silently dormant (Pinging but topology unseeded → `tx.ignore()`). Fix: `Pinging` falls back to `network.connectedPeers()` when the MembershipDecision topology is empty (`ClusterSyncState.effectiveTargets()`). This unblocked the pong stream — and made the in-process substrate faithfully exercise the pong/aggregator path (previously dormant).
- **Finding A (design-critical for #231): the pong stream is LEADER-CENTRIC.** After the fix, only the **leader** (`ch-1`) has a non-empty pong map (sees all 4 peers); followers `ch-2..5` are still `{}`. The metrics ping loop is effectively leader/spokesman-driven, so **φ-on-pongs is *not* leaderless** — it inherits the C9 leader dependency. Consequence: a leaderless φ must either (a) make pinging all-cores, or (b) **ride per-node QUIC connection-state** (every node has its own `connectedPeers` + per-peer connect/disconnect edges) rather than ClusterSync pongs. (b) is the natural leaderless substrate and is what already drives in-process detection — likely shifts option-2e's heartbeat source from pongs to QUIC events.
- **Finding B (real bug, possibly a Docker-instability cause): `MembershipFsm` StackOverflow re-entrancy.** 72× in the 6s post-kill window: `processOperatorEventLocked` → `currentReachabilityGate` (`MembershipFsm:1316`) → `ReachabilityAggregator.snapshot()` → (snapshot **dispatches to listeners**) → `MembershipFsm.onTransportSnapshot` → `dispatchTransportEvent` → `enqueueOperatorEvent` → `processOperatorEventLocked` → ∞ → `StackOverflowError`. Root: `snapshot()` is **not a pure read** (it side-effects listener dispatch), but the FSM calls it to get the reachability gate *during* operator-event processing → unbounded recursion. The dormant ping-pong had **masked** this (empty snapshots didn't dispatch); the fix unmasked it. **In Docker the pong stream already flows, so this likely already fires there** — a candidate root cause for cluster-B failure-handling instability. Decommission did not complete this run (`-1`), consistent with the FSM crashing mid-handling.

---

## 12. SENSE plane — hierarchical multi-source fusion architecture (2026-05-24)

**Origin.** Deferring φ from RC1 (it is off-plane for S01 — see §13 / decision log: S01 is a *re-projection* bug on the DECIDE plane, not a detection gap) opened the more durable question: what is the *right* long-term SENSE plane? This section captures it. It elaborates §3.5 (2-plane model) + §5E (φ sketch) + Finding A (§11) into a single fusion architecture and adds the **hierarchical (governor/community)** dimension those sections lack.

### 12.1 Thesis
SENSE is **one plane** that fuses heterogeneous reachability sources, shapes them through φ-accrual *where the data permits*, and emits a normalized **per-node reachability event stream** that every downstream machine (QUIC actuation, CTM, governor election, the sovereign membership FSM) consumes. **SENSE senses and reports; it never decides lifecycle** (Pillar 1) — its events are inputs to the single-writer FSM (DECIDE), which alone owns `ON_DUTY`/`STOPPED` + the tombstone.

### 12.2 Sources (heterogeneous, fused; source-set-agnostic)
| Source | Semantics | Character | Confidence | Tier availability | φ sample source? |
|---|---|---|---|---|---|
| **QUIC connect/disconnect** | OS socket state — authoritative "link gone" | Edge-triggered, instant | Hard (highest) | Every connected link, all tiers | **No** — an edge, not a periodic arrival; a hard override input + co-confirm trigger |
| **ClusterSync ping-pong (1s)** | C8-permanent metrics/scaling backbone, carries reachability obs | Periodic, per-link | Soft | core↔core **and** governor↔community | **Only** where the local node is the *prober* (governor↔community); **not** the leader-folded pong snapshot (Finding A / C9) |
| **SWIM direct probe-ack** | Leaderless per-peer liveness | Periodic, per-link | Soft | within communities + bootstrap (ANNOUNCE/C2) | **Yes** — direct-probe RTT is the clean φ feed |
| **SWIM indirect probe (ping-req)** | Multi-vantage second opinion | On-demand | Confirmation | within communities | **No** — refutation/confirmation backstop (the asymmetry discriminator, §12.5); 2-hop latency would pollute the window |

Fusion combines **whatever sources a given link has**, weighted by confidence — so the same plane runs at every tier despite different source sets.

### 12.3 φ as a conditional shaping STAGE (not the spine)
**Principle: the plane is correct without φ and better with it.** φ replaces the *judgment* (binary timeout → graduated suspicion) on sources that supply a warm sample window. Below `K_min` (cold start, and the never-connected JOINING peer with no history) the plane judges on raw binary signals (QUIC-close edge, SWIM/ping timeout); as the window warms, φ upgrades the suspicion function **in place**. This is what dissolves the multiphase handoff: φ is not a separate phase that must hand off from a formation protocol — it is an internal upgrade of the one plane's own suspicion function. Coverage split from §5E holds: φ covers established peers; **JOINING reclaim stays deadline-based** (`JoiningTimeout`).

### 12.4 Output: small ordinal, debounced; raw φ as advisory side-channel
- Canonical vocabulary: **Reachable → Suspect → Unreachable** (with a `Connected` transport sub-state). Binary connected/disconnected discards φ's graduated **Suspect** band — precisely the early-warning region CTM/scaling want for pre-staging replacements.
- Canonical transitions are **debounced + incarnation/epoch-stamped** so every consumer agrees on the discrete edges → no per-consumer thresholding → no return to N-loops-disagree.
- Raw φ exposed as an **advisory side-channel** (metrics + `/api/status` + the audit/event surface) for consumers that genuinely want their own threshold. Mechanism, not policy — and the φ time-series is the debugging gold §6.1 flagged we lacked.

### 12.5 Two layers: per-link sensing vs per-node verdict (asymmetry)
Reachability is per-(observer, target) and can be **asymmetric** (partition: A↔B up, C→B down). φ is a per-link statistic — it does **not** solve asymmetry.
- **Layer 1 (local sensing):** the per-link reachability *this* node observes — QUIC edges + φ-shaped ping/SWIM.
- **Layer 2 (disseminated verdict):** gossip the sparse suspected-set (§5E), assemble ≥`(N/2)+1` distinct observers → the per-node connected/disconnected event. This is SWIM's indirect-probe + incarnation/refutation role, kept as the per-node aggregation **on top of** φ-shaped per-link sensing. The **per-node event drives DECIDE; the per-link layer is raw input.** Skipping this split is how asymmetric partitions flap.

### 12.6 Hierarchy — uniform plane, tier-specific sources, and why it *rescues* φ
- The plane runs **uniformly at every tier**: core↔core, governor↔community, governor↔core. The source *set* differs by tier (§12.2); the fusion is source-set-agnostic.
- **The hierarchy rescues φ's hardest problem (sample rate).** φ needs dense, regular per-peer samples; flat random-probe SWIM at scale gives ~1/N per-peer rate → starved windows, jittery φ. **Communities are bounded-small → probe every peer every round → dense φ samples where it counts**; governor↔core link-sets are small too. The structure that *motivates* the unified plane is also what makes φ statistically viable here — flat SWIM-φ at 10k nodes would **not** be. Record this as the reason φ-on-SWIM is sound *in this architecture specifically*.
- Governor election consumes the **same** reachability events as core membership → no bespoke per-tier detector; a failing governor is sensed by its community through the same plane.

### 12.7 QUIC's bidirectional-but-non-circular role
QUIC is a **sensor for close-events (input)** and an **actuator for connect/disconnect commands (output)** — never both directions of *decide*. Today QUIC-disconnect *drives* detection; the SENSE plane inverts ownership: SENSE decides reachability, QUIC **actuates** teardown/reconnect on SENSE's command and reports raw socket edges back as input. Naming this explicitly is the antidote to the QUIC↔detection feedback loops behind the reconnection/re-projection storms.

### 12.8 Hard cautions (this session's scars)
- **Observer-load false positives → Lifeguard local-health term is non-negotiable.** φ measures arrival jitter; an observer-side GC/scheduler stall inflates it → false Unreachable → the live-eviction→self-drain cascade fixed in `e6367986d`. Down-weight my own measurements when my local health is degraded (Lifeguard multiplier on the φ threshold).
- **C9 leaderless → φ inputs must be the leaderless sources.** Finding A: ping-pong pongs are leader-folded, so φ-on-pongs inherits C9 fragility. φ's sample sources are per-node QUIC edges + SWIM probes (+ governor↔community ping-pong where the governor is the *local* prober). The leader's aggregated pong snapshot is **not** a φ input.
- **Indirect-probe distribution pollution.** Feed only direct-probe RTT into a peer's φ window; 2-hop indirect acks have a different latency distribution (inflates σ, slows detection). Indirect = refutation backstop, not a sample.
- **Boundary (S01 lesson).** SENSE never writes lifecycle KV. `connected/disconnected` are inputs to the sovereign FSM (DECIDE).

### 12.9 Scope
RC2+/research — the SENSE plane of #230/#231. **Orthogonal to S01:** a perfect SENSE plane does not fix re-projection (DECIDE). Build order unchanged: **RC1 = tombstone (DECIDE sovereignty) + held SWIM bare-join fix**; this plane is the RC2 unification target, gated behind Spike-1 (measurement) + Spike-2 (deterministic sim) per §6. φ stays staged/documented under #231 (untuned; do not ride it into RC1 on S01's coattails).
</content>
