<!-- SPDX-License-Identifier: BUSL-1.1 -->
<!-- Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko -->

# Architecture Example Batch — Design Spec (DRAFT)

**Status:** Draft for owner review · **Date:** 2026-07-18 · **Author:** spec-writer (team task)
**Related:** #345 (durable-entity epic), #349 (persistence epic), #478 (cursor auto-resume),
#429 / #430 / #431 (streaming e2e fixtures), #416/#417 (SLI catalog + black-box probe),
#457 / #467 / #411 (streaming-debt batch). Durable-entity facades:
[`durable-entity-primitive-spec.md`](../specs/durable-entity-primitive-spec.md) §6, §7, §13.

> **Sourcing note.** #429/#430/#416 are cited from the task brief; this session has no shell, so the
> issue *wording* was not re-read via `gh`. Verify titles before scheduling. Everything about code,
> the `StreamAccess` API, examples layout, suite wiring, and the durable-entity spec is read from the
> tree and cited by path.

---

## 1. Goals / Non-Goals

**Purpose.** A batch of runnable example applications, each named by the **architectural pattern** it
implements on Aether (CQRS, event sourcing, EDA, saga, workflow, outbox, hot-stream admission
control). These are **not book illustrations**. Their primary job is to be **acceptance tests — and
later k6 load tests — that exercise the whole stack end to end**: streaming (publish / fan-out /
cursor-resume / replay), pub-sub, DB (`@Sql`/`@PgSql` + migrations), HTTP routes, durable entities
(fence / single-writer), scaling/rebalance under churn, and crash recovery.

**Goals.**
- **G1** — every stack surface in §2 covered by ≥1 example with *real* assertions (strict counts,
  ordering, resume-offset, error-rate budgets — not "non-empty body accepted", the RC2 tautology
  class the 04-streaming charter flags).
- **G2** — each example deploys as a **blueprint** and plugs into `run-tests.sh` as a suite, reusing
  the existing harness (cluster A non-destructive / cluster B destructive), not bespoke scaffolding.
- **G3** — the saga + workflow examples are written **example-first** to *drive* the #345 facade
  implementation (their acceptance assertions become the facade's done-definition, spec §13 Ph3/Ph4).
- **G4** — consolidate #429/#430 (bespoke stream fixtures) into the CQRS example's load profile, and
  share #416's black-box SLI probe machinery as the examples' assertion helpers.

**Non-Goals.** New runtime features beyond what the examples *surface as gaps* (the outbox example is
a deliberate gap-probe, §3.6); teaching prose (that is the Aether book's job, downstream); replacing
the `04-streaming` / `10-database` unit-of-surface suites (these are *integration-of-surfaces*);
multi-region.

---

## 2. Selection criterion — stack-coverage matrix

Rows = stack surfaces; columns = examples. `●` = primary coverage with the load-bearing assertion;
`○` = incidental coverage. **Every row has ≥1 `●`.**

| Stack surface | CQRS | EventSrc | EDA | Saga | Workflow | Outbox | HotStream |
|---|:--:|:--:|:--:|:--:|:--:|:--:|:--:|
| Stream publish | ● | ● | ○ | | | ● | ● |
| Stream fan-out (multi-partition) | ● | ○ | ○ | | | | ● |
| Cursor-resume (`fetchFromCommitted`, #478) | ● | ○ | | | | | ○ |
| Replay / re-read-from-earliest | ○ | ● | | | | | |
| Pub-sub `@Publisher`/`@Subscriber` | | | ● | | | ○ | |
| Competing consumers (consumer group) | ○ | | ● | | | | ● |
| `@Sql`/`@PgSql` + migrations | ● | ○ | ○ | ○ | | ● | |
| HTTP routes | ● | ● | ● | ● | ● | ● | ● |
| Entity fence / single-writer (#345) | | | | ● | ● | | |
| Scaling / rebalance under churn | ● | | | ○ | ○ | | ● |
| Crash recovery | ● | ● | ○ | ● | ● | ● | ○ |

**Regression sensors baked in:** kill-projection-mid-stream (CQRS → cursor-resume), rebuild-from-log
(EventSrc → replay), kill-owner-mid-run (Saga/Workflow → fence + handover), publish-during-reshuffle
(CQRS/HotStream → rebalance).

---

## 3. Per-example specifications

Common shape (precedent: `examples/url-shortener/`): `examples/<name>/` with `src/main/java` slices,
`src/main/resources/{slices/*.toml, resources.toml, routes.toml, schema/V*.sql, META-INF/dependencies}`,
`forge.toml` (5 nodes), `k6/` harness, `deploy-forge.sh`, `pom.xml`; in-memory persistence doubles in
`src/test/java` for the JBCT unit layer. The blueprint copy the suite deploys lives under
`aether/tests/blueprints/<name>/` (coords `org.pragmatica.aether.test:<name>:1.0.0`, per
`run-tests.sh:deploy_blueprints`).

### 3.1 CQRS / separated read-path — **effort L** (flagship non-entity example)

**Shape.** Four slices: `command` (HTTP `POST /orders` → validates → `StreamAccess.publish`) →
event stream (multi-partition) → `projection` (consumes via **`fetchFromCommitted`** #478, folds into
a `@Sql` read model) → `query` (HTTP `GET /orders/{id}` reads the `@Sql` projection). Write path and
read path never share a table.

**Resources.** `[streams.orders]` (partitions = 8), `[database]` POSTGRESQL + `schema/V001__*.sql`
(read-model tables), routes on command + query slices.

**Acceptance assertions.**
- Publish N commands across 8 partitions; assert strict `success == N` (not `< 400`).
- After settle, `GET /orders/{id}` returns the projected state for every id (projection completeness).
- **Cursor-resume sensor:** kill the projection slice mid-stream, restart; assert it resumes at the
  committed offset (first delivered event == committed offset, not 0) — the black-box analog of
  `CursorAutoResumeRestartTest` (`aether/node/.../CursorAutoResumeRestartTest.java`). No duplicate
  projection rows, no gap.
- **Rebalance sensor:** run the publish load while scaling 5→7 (or killing a partition owner); assert
  the read model still converges to N and strict-2xx stays ≥ 95%.

**Load profile.** *Replaces #429 (multi-partition e2e fixture) and #430 (publish-under-load during
ownership reshuffle).* k6 `ramp-up.js` publishing across partitions + a `run-*.sh` that triggers a
scale/reshuffle mid-ramp. This is the batch's rebalance flagship.

**Surfaces:** publish, fan-out, cursor-resume, replay(○), competing-consumers(○), `@Sql`+migrations,
HTTP, rebalance, crash recovery.

### 3.2 Event sourcing — **effort M**

**Shape.** `append` slice (HTTP command → publishes domain events to a log stream as the *only* source
of truth) + `rebuild` slice (folds the log from offset 0 into current state on demand; holds no
independent DB truth). Demonstrates **log-as-truth + rebuild**.

**Acceptance assertions.**
- Append a known event sequence; `GET /state/{id}` equals the fold of that sequence.
- **Replay-from-0:** wipe the in-memory projection, call rebuild; assert reconstructed state ==
  pre-wipe state (proves `fetch(partition, 0, …)` re-reads the whole log — the guard the #478 test
  asserts via `fetch_honorsExplicitOffset_afterRestart`).
- **Re-read-from-earliest:** a second, independent consumer group reads from earliest and reaches the
  same fold — proves per-group cursors are isolated.

**Surfaces:** publish, replay (primary), crash recovery (rebuild), `@Sql`(○ for snapshot cache).

### 3.3 EDA choreography — **effort M** (evolves `examples/url-shortener`)

**Shape.** Producer slice emits domain events via **`@Publisher`** (`@ResourceQualifier(type =
Publisher.class, config = "…")`, precedent `ClickEventPublisher.java`); ≥2 independent subscriber
slices react via **`@Subscriber`** (`ClickEventSubscription.java` pattern) — one persists analytics
(`@Sql`), one triggers a downstream notification. Adds a **competing-consumers** variant: two
instances of the same subscriber group split the load.

**Acceptance assertions.**
- Every published event is observed by *each* independent subscriber (fan-out completeness).
- Competing-consumers variant: each event handled **exactly once** across the group (no double-count,
  no drop) — asserted on the analytics counter.
- Subscriber crash: kill one competing consumer mid-load; assert no event loss after the survivor
  drains (at-least-once with dedup, framed honestly — see §5 guarantees).

**Surfaces:** pub-sub (primary), competing consumers (primary), `@Sql`(○), HTTP, crash recovery(○).

### 3.4 Saga — **effort L** · **gated on #345 facade (spec §7, §13 Ph4)** · example-first

**Shape.** Targets the specced-but-unimplemented `Saga<C>` facade (`durable-entity-primitive-spec.md`
§7.7). Order saga with three steps + compensations (spec §7.10): `reserve-inventory` (IDEMPOTENT),
`charge-payment` (**RUN_ONCE** — a second charge moves real money), `confirm-order` (IDEMPOTENT).
HTTP `POST /orders/{id}/place` → `saga.run(id, ctx)`.

**Acceptance assertions (these define the facade's done-definition, spec §13 acceptance line).**
- Happy path → `SagaResult.Succeeded`, all three `StepRecord`s present.
- Forward failure at step 2 → compensations run in reverse; terminal `SagaResult.Compensated`.
- **RUN_ONCE crash-window (spec §7.10):** kill the owner after `charge-payment` succeeds but before
  ledger commit; new owner recovers, finds the `StepAttempt(id,1)` marker, does **not** re-charge,
  proceeds to step 3. Assert exactly one charge downstream (dedup on `(sagaId, stepIndex)`).
- Compensation failure → terminal `PartiallyCompensated`, queryable via `status(id)` (spec §7.5).

**Surfaces:** entity fence/single-writer (primary), HTTP, crash recovery (primary), `@Sql`(○ ledger).
**Gating:** requires #345 fence stream-path (piece 1b, MISSING) + per-key serialization + saga facade.
Restart-durable recovery additionally needs #349; on the #345 fence alone the example proves
**HA/owner-handover** recovery, not full-cluster-restart durability (spec §4.4). Frame accordingly.

### 3.5 Workflow — **effort M** · **gated on #345 facade (spec §6, §13 Ph3)** · example-first

**Shape.** Targets `PersistentWorkflow<S,E>` (spec §6.2). `OrderProcess` FSM (spec §6.4): states
`Pending/Confirmed/Shipped/Cancelled`, events `Confirm/Ship/Cancel`, built on the verified
`StateMachineDefinition` builder (`C = Unit`, spec §6.4). HTTP routes per transition +
**signal injection** (`POST /api/workflows/{type}/{id}/signal`, spec §6.6 — the management triad).

**Acceptance assertions.**
- `dispatch` of a valid event advances state; an invalid event rejects with `InvalidEvent` **before**
  any write (spec §6.2 — domain error, not a generic update failure).
- Signal injection routes to `dispatch` on the owner and is fenced like any write (spec §6.6).
- **Owner-handover:** kill the partition owner mid-workflow; in-flight `dispatch` retries transparently;
  the deposed owner cannot commit after handover (fence assertion, spec §8).
- Durable one-shot timer fires the scheduled event after owner handover (spec §4.5) — *if* piece 3
  (durable timers) is in scope for the phase; otherwise deferred.

**Surfaces:** entity fence/single-writer (primary), HTTP, crash recovery. **Same #345/#349 gating as
§3.4.**

### 3.6 Outbox / change-feed — **effort S–M** · **deliberate gap-probe**

**Shape.** Attempts the classic transactional-outbox pattern: atomically commit a DB row (`@Sql`) and
publish the corresponding event, with an at-least-once relay. **Finding expected, not a pass:** the
feature catalog has **no `outbox`/`change-feed` entry** (grep of `feature-catalog.md` returns none),
and there is no dual-write/2PC primitive. The Aether-native answer is **log-as-truth** (§3.2) — the
stream *is* the outbox; the DB read model is derived, so the dual-write problem is dissolved rather
than solved.

**Deliverable.** A short example + a **feature-catalog entry** documenting "no dedicated outbox;
use event-sourcing/log-as-truth" (or, if the owner wants one, a scoped feature request). This example
earns its slot by *producing the gap verdict*, per the sequencing invariant ("tickets are hypotheses").

**Surfaces:** `@Sql`+migrations, publish, crash recovery — but the primary output is a documented gap.

### 3.7 Hot-stream coalescing / admission control — **effort L** · **k6 load flagship**

**Shape.** A single hot stream/partition under extreme publish load, with an admission-control /
coalescing slice in front (batch/deduplicate before append). Directly exercises the acknowledged
**hot-entity bottleneck** (spec §4.6) and the streaming throughput/error-rate budget
(`04-streaming` C4).

**Acceptance assertions.**
- Sustained publish at documented RPS keeps strict-2xx ≥ 95% (`assert_error_rate_below`, the
  `test-stream-under-load.sh` pattern) — *and* asserts coalescing actually reduced appended volume.
- Under overload, admission control sheds/queues **without** cluster health loss (member count == 5,
  `assert_cluster_healthy`) — the C5 invariant.
- Backpressure is observable (a metric/SLI, not log-scraping) per the observability-first invariant.

**Load profile.** The batch's flagship k6 suite: `spike.js` + `load-test.js` (steady) + a per-node
fan (`per-node.js`), mirroring `examples/url-shortener/k6/`.
**Surfaces:** publish, fan-out, competing consumers, rebalance, crash recovery(○).

---

## 4. Infrastructure

**4.1 Suite wiring.** New suite `aether/tests/integration/suites/16-arch-examples/` (or one suite per
example if runtimes diverge). Each carries `suite.conf` (`cluster=non-destructive` for CQRS/EDA/
EventSrc/Outbox/HotStream on cluster A; `cluster=destructive` for Saga/Workflow kill-owner tests on
cluster B), `blueprint=<example>` (auto-deployed by `run-tests.sh:collect_blueprints` →
`deploy_blueprints`), and a `CHARTER.md` mapping each `TC-16-…-NNN` to a spec contract (the
`04-streaming/CHARTER.md` model — contract table + test-to-contract map + known-limitations census).
Add the suite prefixes to `CLUSTER_A_SUITES` / `CLUSTER_B_SUITES` in `run-tests.sh:71-81`.

**4.2 Assertion helpers.** Reuse `lib/common.sh` (`run_test`, `assert_eq`, `assert_ge`,
`assert_contains`, `assert_error_rate_below`, `assert_cluster_healthy`) — no new framework. Add three
shared helpers, which are the **#416 black-box SLI probe** surface reused as test assertions:
`assert_projection_converged` (poll read model to expected count with a budget), `assert_resume_offset`
(committed-offset == first-delivered-offset), `assert_exactly_once` (competing-consumer dedup). Put
them in a new `lib/arch-examples.sh` sourced by the suite.

**4.3 k6 harness.** Copy the established `examples/*/k6/` structure (`load-test.js` steady,
`spike.js`, `ramp-up.js`, `per-node.js`, `env.sh`, `run-steady.sh`/`run-spike.sh`/`run-ramp.sh`).
HotStream (§3.7) and CQRS (§3.1) ship k6 first; others get k6 as a follow-on. k6 stays **opt-in**
(the load tier), gated behind the same `--env`/forge deploy the examples already use; acceptance
(pass/fail) assertions run in the shell suite without k6.

---

## 5. Guarantees framing (consistency lens)

Assertions must name the **per-operation** guarantee, not a system label:
- Competing consumers = **at-least-once + dedup on a stable key**, never "exactly-once" unqualified
  (§3.3, §3.4 downstream dedup on `(sagaId, stepIndex)`, spec §7.4/§10).
- Saga `RUN_ONCE` = **at-most-once invocation** of `forward`; end-to-end once-only requires the
  downstream to dedup (spec §7.4). Do not assert "exactly-once".
- Entity/workflow reads default to **BOUNDED_STALE**; only assert linearizability where the example
  explicitly requests `LINEARIZABLE` (spec §8.1).
- Saga/Workflow crash recovery on the #345 fence alone = **HA/handover-durable**, *not*
  restart-durable until #349 (spec §4.4). State this in the CHARTER, not just the code.

---

## 6. Sequencing

Risk-first, matching the repo's stabilize-foundation-first invariant:

1. **Blocked until streaming-debt batch closes** (#467 forward-misroute fix, #478 restart test +
   auto-resume, #457/#429–#431, #411 serializer). CQRS, EventSrc, EDA, HotStream, Outbox all ride the
   *current* streaming substrate and would inherit its open defects if written now.
2. **Wave A (post-streaming-debt):** EDA (evolve url-shortener, lowest risk) → EventSrc → CQRS
   (consumes #429/#430) → HotStream (k6 flagship) → Outbox (gap-probe, cheap, batch freely).
3. **Wave B — with the #345 facade phase (spec §13 Ph0→Ph4):** Workflow (§3.5, Ph3) then Saga
   (§3.4, Ph4), written **example-first** so their assertions are the facade acceptance gate. These
   need stream-path fence (piece 1b) + per-key serialization first; full restart-durable variants
   wait on #349.
4. Validation gate between waves: in-JVM Forge/Ember proof first, then the remote-docker 15-suite
   gate, cloud sweep last (never the primary debug surface).

---

## 7. Open decisions (options + recommendation — owner call)

**D1 — Placement. ✅ RULED (owner, 2026-07-18): monorepo, under `examples/`.**
- (a) **Monorepo** — example slices under `examples/<name>/` (matches url-shortener/ecommerce/
  pricing-engine precedent), blueprint copies under `aether/tests/blueprints/`, suite under
  `.../suites/16-arch-examples/`. ← **ruled**
- ~~(b) Separate `pragmatica-examples` repo.~~
- *Cost accepted:* a maintained dual copy (example ↔ blueprint) —
  mitigate with `generate-blueprint.sh` (precedent: `examples/url-shortener/generate-blueprint.sh`).

**D2 — Licensing. ✅ RULED (owner, 2026-07-18): `examples/**` = Apache-2.0.** Add explicit
`SPDX-License-Identifier: Apache-2.0` headers (the current no-header state becomes explicit;
applies to url-shortener and the new examples alike — confirm with owner before mass-applying
to pre-existing examples). One residual detail, not yet ruled: the license of the *generated
blueprint copies* under `aether/tests/blueprints/` (BSL territory) — options remain (a) copies
carry BSL like their tree, or (c) the examples carve-out follows the generated artifacts.
Resolve when the first example's blueprint is generated; the applicator's path glob
(`tools/license/apply-bsl.sh`) must exclude `examples/**` either way.

**D3 — Sequencing.** Recommend as §6: examples **after** the streaming-debt batch closes; Workflow +
Saga examples land **with** the #345 facade phase, example-first. (Owner may pull EDA earlier since
url-shortener already covers ~80% of it.)

---

## 8. References

**Internal — code & specs (read this session):**
- `aether/docs/specs/durable-entity-primitive-spec.md` — §6 Workflow, §7 Saga, §8.1 read consistency,
  §13 phases (facades specced, **not implemented**).
- `aether/slice-api/src/main/java/org/pragmatica/aether/slice/StreamAccess.java` — app stream SPI;
  `fetchFromCommitted` (#478 auto-resume) at :44.
- `aether/node/src/test/java/org/pragmatica/aether/node/CursorAutoResumeRestartTest.java` — the
  cursor-resume regression-sensor pattern the CQRS example black-boxes.
- `examples/url-shortener/` — precedent for layout, `@Publisher`/`@Subscriber` (`ClickEventPublisher.java`,
  `ClickEventSubscription.java`), `@Sql` + `schema/V001__create_tables.sql`, `forge.toml`, `k6/`.
- `aether/tests/integration/run-tests.sh` — suite discovery, blueprint deploy, cluster A/B model.
- `aether/tests/integration/suites/04-streaming/{CHARTER.md,suite.conf,test-stream-consumer.sh}` —
  charter + assertion precedent (and the tautology-class the new suite must avoid).
- `aether/tests/blueprints/{test-echo,test-full,test-persistence}/` — blueprint packaging precedent.
- `aether/docs/reference/feature-catalog.md` — **no outbox/change-feed entry** (the §3.6 gap).
- `docs/legal/bsl-header.txt`, `tools/license/apply-bsl.sh` — BSL boundary tooling (D2).

**Internal — issues (wording per task brief, not re-verified):** #345, #349, #478, #429, #430, #431,
#416/#417, #457, #467, #411.
