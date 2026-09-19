# Durable Pub-Sub Specification — Two-Tier Delivery Model

**Status:** Draft v0.2 (post consistency-lens review; 9 findings addressed — see Revision history)
**Issue:** #386 (supersedes the RFC-0011 deferral rows for durable delivery / DLQ / idempotency)
**Depends on:** #410 two-knob stream replication (merged), #264 durable consumer cursors (absorbed by §7), hierarchical-storage durable tier (#349) for cursor/DLQ persistence choices. **Blocked-until-#411:** durable-topic configs outside `min-sync == replicas` (§3).
**Related:** `durable-entity-primitive-spec.md` (projections consume topics; entities never replay — see §10), `streaming-spec.md` §10.5

---

## 1. Problem

Pub-sub today is a fire-and-forget RPC fan-out with an API that implies more:

- Delivery is `SliceInvoker.invoke` per subscriber group (`TopicPublisher.java:45`) — no retry (`invoke`, not `invokeWithRetry`), no persistence, no redelivery. Authoritative: `guarantees.md` §22 — *at-most-once, none (never persisted), best-effort*.
- Four drop points: (i) zero subscribers → `publish` returns **success** while delivering nothing (`TopicPublisher.java:31-33`); (ii) subscriber down / RPC failure → dropped; (iii) subscriber crash between receive and process → dropped; (iv) nothing is ever queued or persisted.
- The subscriber handler shape `Fn1<Promise<Unit>, T>` (`TypedSubscriber.java:33`) and the publisher's aggregated `Promise<Unit>` imply the runtime acts on failure. It does not: the failure only propagates to a publisher that structurally **cannot** act on it (re-publish would duplicate to already-succeeded groups; round-robin retargets retries; the publisher's continuation has usually moved on, leaving a dangling in-flight promise whose failure lands nowhere).

Meanwhile the durable substrate already exists, unused by pub-sub: a replication-backed stream log with a synchronous ack floor (#410), an at-least-once stream consumer with offset replay (`guarantees.md` row 19; the runtime polls via `readLocal`, cursors via `CursorStore`), durable cursor stores built-but-unwired (`CursorStore`, `PgCursorStore` — #264), a `DeadLetterHandler` seam in the stream consumer runtime, and `IdempotencyMethodInterceptor` on the aspect surface.

## 2. Decision summary (approved + revised per review)

| # | Decision | Choice |
|---|----------|--------|
| D1 | Architecture | **Two-tier**: topic declares durability. Durable topics = consumer groups over `aether-stream`. Ephemeral topics = today's RPC path with an **honest publisher signature** (D5). |
| D2 | Ack model (durable) | **Handler `Promise` success = ack.** Failure/timeout = redelivery (bounded, then DLQ). **Dispatch is serial per partition** (§6) — the cursor advances ack-by-ack. |
| D3 | DLQ | **Per-topic DLQ stream** (`<topic>.dlq`), **eagerly created** at topic activation, RF = source topic's. Envelope carries the failing **group**; redrive is **group-targeted**. Management triad: REST + CLI + docs. |
| D4 | Projection | **Pattern + thin facade** (§10) with a **rebuild generation** in the idempotency key. |
| D5 | Ephemeral honesty | `publish` on an ephemeral topic returns **`Unit`** (fire-and-forget made explicit at the type level); the durable publisher returns `Promise<Unit>` = durability ack. Codegen picks the publisher type from the topic's declared durability — compile-time honest. |

## 3. Topic model

A topic is declared where it is today (a `Topic<T>` constant + slice `resources.toml` section), extended with a durability class:

```toml
[[topic]]
name = "orders.completed"
durability = "durable"        # "ephemeral" (default) | "durable"
partitions = 4                 # durable only; default 1
replicas = 2                   # durable only; default 2 (see constraint)
min_sync_replicas = 2          # durable only; default = replicas
retention = "7d"               # durable only; stream retention policy
```

- `durability = "ephemeral"` (default): exactly today's mechanics, honest signature (D5). Defaulting to ephemeral keeps the zero-config path cheap and makes durability an explicit, costed choice.
- `durability = "durable"`: backed by a stream `topic:<name>` created at slice activation through the app-stream path (`StreamConfig` — the #410 machinery: committed config, reconcile-on-config-Put placement, epoch-fenced recovery). Its DLQ stream (§9) is created **in the same activation step**.
- **v1 durable-config constraint (parser-enforced):** `replicas ≥ 2` **and** `min_sync_replicas == replicas`. This is exactly the configuration whose lossless owner-kill failover is proven (streaming-spec §10.5 scoping). Configurations outside it are **rejected at parse** with a pointer to this section: `replicas = 1` provides no failover durability, `min_sync_replicas = 0` is the zero-peer-ack floor (`0==1` footgun, §10.5), and `min-sync < replicas` with `replicas > 2` can drop acked records on single-survivor promotion until #411 (multi-survivor union catch-up) lands. When #411 lands, this constraint relaxes to `2 ≤ min-sync ≤ replicas` by amending this section — not silently. **An unrecognized key at the topic's own level — most commonly a dashed spelling such as `min-sync-replicas` where the underscore form shown above is what the binder expects — is itself rejected at parse, naming the nearest correctly-spelled key when one is close enough, scoped to the section's static/file-backed keys so an environment variable, system property, or KV-overlay entry never trips it (#738), rather than silently resolving as if the knob had never been declared. On an ephemeral topic this closes a real fail-open: the mistyped knob used to stay invisible to `declaredStreamKeys()`, so `rejectInertKeys()` never fired and a likely-durable declaration was dropped with zero signal instead of the loud rejection an inert stream knob is supposed to get.**
- Partitioning: an optional publisher-supplied message key routes by hash to a partition; keyless events round-robin partitions. Per-partition order is preserved end-to-end (§6).

The `Topic<T>` compile-time layer (#396) is unchanged — it carries the type, the address, and (now) the durability class that selects the publisher shape (D5).

## 4. Per-operation guarantee table (normative)

This table is the contract; `guarantees.md` §22 is replaced by it on implementation. Each cell names the guarantee **and** the mechanism that earns it. "Durable" cells assume the §3 constraint (`replicas ≥ 2`, `min-sync == replicas`); other configs are unrepresentable in v1.

| Operation | Ephemeral | Durable |
|-----------|-----------|---------|
| `publish` returns | `Unit` — dispatch attempted to currently-registered subscriber groups (best-effort RPC; registry is KV-propagated, may lag). **Not delivery, and the type says so.** | `Promise<Unit>` — resolves when the event is **persisted at the declared floor**: owner append + `min-sync − 1` peer acks (synchronous replication barrier, #410; `min-sync ≥ 2` guaranteed by §3). **Not processing.** |
| `publish` with zero subscribers | No-op (documented). | Event persisted and retained per retention. Consumers attached later read it (log semantics). |
| Delivery to subscriber group | At-most-once per group (single `invoke`, round-robin instance; drops on RPC failure, crash, absence). | At-least-once per group: group cursor + redelivery until acked or dead-lettered (§6). |
| Ordering | None. | **Per-partition processing order within a group** — earned by strictly serial dispatch per (group × partition) (§6): event N+1 is not dispatched until N is acked or dead-lettered. No cross-partition order. |
| Duplicate exposure | None (a message is delivered 0 or 1 times). | On redelivery: crash/timeout after processing-before-ack, and cursor-commit lag after ack (§7). Bounded by the idempotency aspect where applied, within its stated limits (§8). |
| Subscriber failure | Invisible to the runtime (failure logged; no redelivery). The honest signature makes this explicit. | Bounded retries with backoff, then the event lands in `<topic>.dlq` with failure metadata + group attribution (§9). Never silently dropped. |
| Loss window | Any drop point in §1. | **Event** loss requires simultaneous loss of the owner and all `replicas − 1` sync replicas (below the §3 floor). **Processing** loss requires cursor-behind-retention — surfaced as `CURSOR_GAP` + lag alarm, never silent (§7). |

## 5. Publisher semantics — what `publish` means

Tier-typed publishers (D5), selected by codegen from the topic declaration:

- **Durable — `publish(T): Promise<Unit>`:** resolves when the event is durably appended (owner + `min-sync − 1 ≥ 1` peer acks). Publisher latency is bounded by replication latency and is **independent of subscribers** — subscriber processing is severed from the publisher's lifetime by the log. A publisher may complete and exit with consumers hours behind; nothing dangles. Failure = the append genuinely failed (e.g. `NOT_ENOUGH_REPLICAS` under the floor) and is actionable: the event is NOT in the log.
- **Ephemeral — `publish(T): Unit`:** fire-and-forget at the type level. Dispatch failures are logged/metered by the runtime; there is no delivery feedback channel because there is no delivery guarantee. Reference docs state: *do not build workflows on ephemeral delivery.*

This resolves the dangling-call defect structurally in the durable tier (no subscriber coupling) and at the type level in the ephemeral tier (nothing returned to dangle) — the ticket's fork (b), applied where it is true.

The subscriber handler shape stays `Fn1<Promise<Unit>, T>` in both tiers: in the durable tier the returned promise **is the ack** (load-bearing); in the ephemeral tier it is observed only for logging/metrics, and its javadoc says so.

## 6. Durable subscriber semantics

- **Consumer group** = the subscriber group identity **with the artifact version stripped**: `(groupId:artifactId, methodName)`. Cursor keys use this version-stable identity, so a slice upgrade **keeps its cursor** (no full-history reprocessing on deploy). The registry's current group key embeds the full versioned artifact (`artifact.asString()`) — the durable dispatch path maps it to the version-stable form; cursors orphaned by a *rename* (not upgrade) are surfaced by the §9 lag surface and cleaned by operator action.
- **Dispatch loop** (one per group × partition): fetch from the group cursor via the stream consumer (poll `readLocal` from cursor+1), invoke the handler on **any live instance** of the subscriber slice (instance-agnostic; the cursor belongs to the group, not the instance), and treat the handler's `Promise<Unit>`:
  - **success** → ack; cursor advances (§7); dispatch the next event;
  - **failure or per-attempt timeout** (default 30s; single source of truth = the slice-invoker call timeout for the attempt) → retry with exponential backoff (default: 5 attempts, 1s base, ×2, cap 60s — per-topic configurable), each attempt eligible for a different live instance;
  - **retries exhausted** → the event is appended to `<topic>.dlq` (§9), then acked in the source cursor; dispatch continues.
- **Dispatch is strictly serial per (group × partition):** event N+1 is not dispatched until N is acked or dead-lettered. This matches the existing consumer runtime's serial chain and is what earns the §4 ordering guarantee. Throughput scales by partitions, not by pipelining within a partition. (A pipelined window trades away processing order; explicitly rejected for v1.)
- **Zombie attempts:** a timed-out attempt may still be executing when its retry dispatches elsewhere. The runtime cannot cancel it; this is a designed-in concurrent-duplicate source, bounded only by idempotency (§8). Stated, not hidden.
- **Redelivery stalls only its partition**; other partitions proceed. The DLQ bound (retries exhausted) caps the stall at `Σ backoff + attempts × timeout` per event.

## 7. Cursor durability (absorbs #264)

- Ack-by-ack cursor advance is batched into **commits**: every 16 acks or 500 ms, whichever first (coherent with serial dispatch; per-topic configurable). **Crash redelivery bound = commit lag ≤ 16 events or 500 ms of acks per partition** — not an in-flight window (dispatch is serial).
- Commits go through `CursorStore` — the durable implementations that exist unwired today (`segment/CursorStore.java:30`, `pg/PgCursorStore.java:23`) become the production path; the memory-only default (`StreamConsumerRuntime.java:39-44`) remains only for Forge/tests. Default backing: cluster KV (consensus-committed); `PgCursorStore` where PG persistence is provisioned. A deployment concern, not a semantics change.
- **Cursor vs retention:** retention is the floor (Kafka stance). If a group's cursor falls behind `earliestRetainedOffset`, the gap is **surfaced, never silent**: the runtime emits a `CURSOR_GAP` event (offset range lost), **forward-resets** the cursor to the earliest retained offset — and ignores any in-flight ack below the reset point (no cursor regression). Lag metric + gap events are operator-visible (§9). Retention sizing against slowest-consumer lag is an operator responsibility, documented.
- Cursor-store unavailability: dispatch continues from the last committed cursor after recovery; the duplicate window widens to the outage length (documented); no loss.

## 8. Idempotency (opt-in) — and its honest bounds

- Every durable event carries a publisher-assigned `messageId` (KSUID) plus `(topic, partition, offset)`; `messageId` survives DLQ redrive (§9), offsets do not.
- Subscribers opt in through the existing `IdempotencyMethodInterceptor` (claim/run/finalize over a `CacheBackend`), with a `keyExtractor` reading the envelope `messageId`.
  - **Resolved divergence (2026-08-29, CTO-approved): read this as mandating the MECHANISM, not the class.** `IdempotencyMethodInterceptor` is a `MethodInterceptor` applied to intercepted slice methods, but `Projection.onEvent` is called directly from a subscriber's body — there is no interception point, and creating one would mean making the fold an intercepted method, re-forking the delivery path the placement ruling unified. The projection guard therefore reuses the claim/run/finalize/release SHAPE under its own `ProjectionClaims` seam, which adds a lease and a fencing token because a shared claim outlives the process that took it: `claimIfAbsent(key, lease)` answers `Claimed(token)` / `DONE` / `IN_PROGRESS` in one indivisible step, then `finalizeClaim(key, token)` (fold succeeded → DONE) or `releaseClaim(key, token)` (fold failed → PENDING dropped), each acting only while the stored claim still carries that token and otherwise answering `STALE` (no change, logged at WARN) — the interceptor's value-conditional `remove(key, sentinel)` carried into a shared store. A PENDING claim whose lease expired is reclaimable under a new token. It cannot be a get/put contract like `CacheBackend`: separate get and put never make the check-then-record pair atomic, whatever the atomicity of each call (#1243). The seam is declared in `resource-api` because `resource-interceptors` already depends on `resource-api`, so importing `CacheBackend` the other way would close a dependency cycle.
- **Interceptor aspect on a plain subscriber (bound unchanged):** `IdempotencyMethodInterceptor` claims in an in-process map and records outcomes through `CacheBackend` get/put, so concurrent attempts are suppressed within ONE instance only; cross-instance attempts (a zombie and its retry on another node) can both run. Evicted or lost outcome records re-admit duplicates. `[mechanism: in-process computeIfAbsent claim; store read and write are separate calls]`
- **Projection guard — bounded claim (normative wording), per operation:**
  - **Concurrent attempts on one key are suppressed to one apply** — a zombie attempt (§6) racing its retry included — among every instance whose claims backing performs `claimIfAbsent` as one indivisible step over a store they all read (a consensus-KV compare-and-set, or a transactional row). A per-process backing earns this only inside its process. The losing attempt fails retryably (`IN_PROGRESS`) instead of being acked, so it cannot be acknowledged while the holder's fold may still fail. Redelivery is bounded (§6 defaults: 5 attempts, 1s base ×2, about 15s in all), so a loser still meeting a live claim when its budget runs out is dead-lettered (§9); a redrive then finds the key DONE and is suppressed, or reclaims it once the lease has expired.
  - **A failed fold releases its claim**, so its retry applies.
  - **An attempt that outlives its lease cannot finalize or release its successor's claim** — the token no longer matches, so its settlement is `STALE` and changes nothing. This holds only if tokens are unique for the key across releases and evictions (a counter that outlives the claim record, or a globally unique value): a counter kept in the claim record restarts when a release drops the record and reissues an old token. Lease expiry is judged against one clock every instance agrees on (the store's), never a caller's wall clock. A non-positive lease is refused before claiming with a typed `NonPositiveLease` failure: a claim that expires the instant it is taken would let every racing attempt fold.
  - **A crash between fold and finalize, or a finalize that is not recorded, re-applies once the lease expires** — through whichever attempt arrives after expiry. The lease must exceed the longest fold (up to the 30s attempt timeout by default), which outlasts the default retry budget, so under defaults that re-apply comes through a DLQ redrive, not the remaining retries; the lease-versus-retry-budget ratio is the interaction to size (a shorter lease lets retries reclaim, and equally lets a slow fold be overlapped). The late fold of an attempt that outlived its lease still lands beside its successor's: the token fences the claim, not the read-model write. A non-idempotent fold is therefore **at-least-once in that window**. Exactly-once *apply* requires the store to apply the fold and the claim in ONE transaction — the named path, not built.
  - **Beyond the claim store's retention/durability**, an evicted or lost DONE claim re-admits the duplicate it recorded.
  - Never advertised as exactly-once delivery.
  - Evidence: `[mechanism: the single claimIfAbsent step decides Claimed/DONE/IN_PROGRESS; fold runs only on Claimed; finalize/release are conditional on the claim's token]` — protocol pinned in-JVM by `ProjectionTest.IdempotencyGuard` against in-memory fakes. `[unverified: no production ProjectionClaims backing exists yet, so cross-instance suppression has never run on a live multi-node path]`.

## 9. DLQ (per-topic, eagerly created) + operator surface

- **Stream `<topic>.dlq`**, created **eagerly at topic activation in the same step as the topic stream** — same committed-config path, so the #262 first-publish/config-adoption race class cannot recur here (lazy creation was rejected for exactly that reason). RF/min-sync inherited from the source topic (an event that survived replication must not die in a weaker DLQ). Retention: per-topic configurable, default 14d.
- **Envelope**: original payload + `messageId` + source `(topic, partition, offset)` + **failing group** + attempt count + last failure cause + timestamps.
- **Redrive is group-targeted:** it re-injects the entry into the failing group's dispatch path with a fresh retry budget — it does **not** re-publish to the source topic, so groups that already processed the event are untouched (no cross-group duplication by construction, not by dedup). Successful redrive marks the DLQ entry consumed via a system cursor (audit trail until retention).
- **DLQ-append failure**: the source cursor does NOT advance past an event whose DLQ append hasn't succeeded (no silent loss); the append retries with backoff. Because DLQ inherits the source's min-sync, cluster states that cause dead-letters can also stall DLQ appends — therefore a **dedicated `DLQ_STALL` alarm** (partition blocked on DLQ append > threshold, default 60s) is part of the surface below, alongside lag and gap.
- **Management triad (REST → CLI → docs), per project invariant:**
  - `GET /api/topics/{topic}/dlq` (list, paged) · `GET .../dlq/{offset}` (inspect) · `POST .../dlq/redrive` (all | offset range | by group) · `GET /api/topics/{topic}/groups` (cursor, lag, `CURSOR_GAP` events, `DLQ_STALL` state).
  - CLI: `aether topics dlq list|show|redrive`, `aether topics lag`.
  - Docs: `management-api.md` + `cli.md` sections.

## 10. Projection — pattern + thin facade

A projection is **nothing but a durable subscriber with an idempotent apply**:

```java
Projection.of(ORDERS_COMPLETED)                    // Topic<OrderCompleted>
          .into(kvStore, key(e -> e.orderId()))    // or an entity ref
          .apply((state, event) -> fold(state, event));
```

The facade wires: a durable subscription (group = projection name), the §8 claim guard keyed by **`(projectionName, generation, messageId)`**, and a read-model write.

- **Rebuild is one operator procedure of three SEQUENTIAL steps — not atomic**: bump the projection's `generation` (a persisted per-projection counter) → reset the read model (the facade requires a `reset` hook: KV-prefix clear or entity-range delete) → reset the group cursor to the earliest retained offset. The generation in the idempotency key makes prior-pass claims inert — without it, retained claims from the first pass would dedup the entire replay into a no-op (review finding 3).
  - **Partial completion is possible:** a step that fails leaves the earlier ones done. A failed cursor reset leaves the generation bumped and the model cleared with nothing replayed; the operator re-runs the rebuild. `[mechanism: the facade chains the three steps; pinned by ProjectionTest.rebuild_refusesLoudly_whenCursorResetNotWired]`
  - **In-flight folds are fenced — the window is closed (#1298):** every read-model write carries the generation its fold was keyed under (the guarded path uses its claim's generation, never a fresh read), and the store writes it only while that is still the current generation, deciding it in one indivisible step with the bump. Rebuild bumps before it resets, so a write lands either before the bump — and the reset clears it — or after it, and is refused with a retryable `StaleGeneration`; a retry runs under the new generation, where the §8 claim deduplicates it against the replay. The replay is therefore the only writer of the rebuilt model and applies each event once. Ordering decides it, not timing: no drain and no timeout, which a zombie attempt (§6) that cannot be cancelled would defeat. `[mechanism: generation-fenced ProjectionStore.write, indivisible with bumpGeneration; pinned by ProjectionTest.IdempotencyGuard.rebuild_inFlightGuardedFold_appliesExactlyOnce and ProjectionTest.rebuild_inFlightUnguardedFold_doesNotWriteIntoTheResetModel]`
- Guarantee statement: *the projection converges to include every durably-published event at-least-once applied; concurrent duplicate applies suppressed to one under §8's claim, with at-least-once re-apply in §8's crash/lease window — a naturally idempotent fold converges exactly regardless; staleness is the consumer lag, observable via §9.* Rebuild replays only what retention still holds — a rebuild older than retention is a partial rebuild and is reported as such (`CURSOR_GAP` semantics apply).

Boundary with `durable-entity-primitive-spec.md`: entities remain state-as-truth, no replay. Projections consume *topics*; they do not replay entity history. No change to the entity spec.

## 11. Failure modes (explicit)

| Failure | Behavior |
|---------|----------|
| Topic-stream owner killed | #410 semantics under the §3 constraint (`min-sync == replicas ≥ 2`): acked events survive on the sync replica set; lossless failover per streaming-spec §10.5's proven scoping. Publishers see bounded unavailability, not loss. |
| All subscriber instances down | Durable: events accumulate; cursor resumes on redeploy (within retention). Ephemeral: dropped (documented, typed). |
| Subscriber poison message | Bounded retries → DLQ (group-attributed); partition unblocks; operator redrives after fix. |
| Cursor store unavailable | At-least-once from last committed cursor after recovery; duplicate window widens (§7); no loss. |
| DLQ append fails | Retry with backoff; source cursor does not advance (no silent loss); `DLQ_STALL` alarm fires (§9). Stall is deliberate and bounded by the alarm→operator loop, not unbounded-and-silent. |
| Cursor behind retention | `CURSOR_GAP` event + lag alarm + forward-only cursor reset (§7). Loss is bounded, attributed, and visible. |
| Slice upgrade (version bump) | Group identity is version-stable (§6): cursor and DLQ attribution carry over; no reprocessing storm. |

## 12. Blast radius (implementation map)

- `aether-invoke`: `TopicPublisher` (tier switch + tier-typed publisher), `TopicSubscriptionRegistry` (**version-stable group mapping** for durable groups — not "unchanged"), new durable dispatch runtime (serial per group × partition) over the stream consumer.
- `aether-stream`: reuse; wire `CursorStore` (#264); `DeadLetterHandler` seam replaced for durable topics by the DLQ-stream append path (needs a failure-aware append API — `record` today is void/in-memory).
- `slice-processor`: topic declaration parsing (`durability` + stream knobs + §3 constraint validation) → manifest; **tier-typed publisher codegen**; envelope-version bump per `envelope-versioning.md`.
- `slice-api`: `Topic<T>` carries durability; `Publisher` split into tier-typed shapes (D5); `Subscriber`/`TypedSubscriber` unchanged, javadoc honesty pass (§5).
- `aether/node` + `cli` + docs: §9 triad incl. `DLQ_STALL`/lag/gap surfaces.
- `guarantees.md` §22 → the §4 table; RFC-0011 deferral rows retired.

## 13. Open questions

1. ~~Per-attempt handler timeout vs transport timeout~~ — resolved: single source of truth is the slice-invoker call timeout for the attempt (§6).
2. Ephemeral-tier zero-subscriber `publish`: with the `Unit` return (D5) this reduces to a documentation note — kept as today's no-op.
3. ~~Lazy DLQ creation~~ — resolved: eager creation at topic activation (§9), rejecting the #262 race class by construction.
4. Group-cursor **rebalance** when multiple instances of one group are active: v1 = single dispatcher per (group × partition) choosing any live instance per attempt; a partition-assignment protocol (Kafka-style rebalance) is explicitly out of scope for v1.
5. Version-stable group identity (§6): exact key form for pre-existing registry entries and the orphaned-cursor cleanup surface — implementation detail to settle at the #264 wiring point.
6. `Projection.reset` hook contract for entity-backed read models (range-delete semantics) — settle when the facade lands.

## Revision history

- **v0.2** — consistency-lens review (9 findings): serial-per-partition dispatch replaces the in-flight window (ordering claim now earned); durable configs constrained to the proven `min-sync == replicas ≥ 2` scoping with parser enforcement + #411 relaxation path; projection rebuild generation (idempotency-store interaction); effectively-once bounds stated (zombie attempts, store retention); version-stable group identity (upgrade reprocessing storm); group-attributed DLQ + group-targeted redrive (cross-group duplication); eager DLQ creation + `DLQ_STALL` alarm (#262 race class + failure-correlated stall); coherent commit-cadence numbers + forward-only gap reset; ephemeral honesty moved into the type system (`publish → Unit`).
- **v0.1** — initial draft (two-tier, promise-ack, per-topic DLQ, projection pattern).
