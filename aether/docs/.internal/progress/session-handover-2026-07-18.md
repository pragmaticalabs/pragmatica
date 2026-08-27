<!-- SPDX-License-Identifier: BUSL-1.1 -->
<!-- Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko -->

# Session Handover — 2026-07-18 (aether-main)

**Branch:** `release-1.0.0-rc3` @ `a4f027037` (pushed, tracking). **Candidate tag:** `3999d6567` (Release CI green after one transient-upload rerun; standing policy: re-point after every substantial batch). **Working tree:** ONE uncommitted file — `StreamFanoutConsumerTest.java` setUp edit (part of the OPEN #467 dig, see below). **Colima:** stopped. **Cloud:** PG-only unchanged.

## TL;DR

The rc3 **pre-cloud multi-cloud epic is functionally complete**: W1 (spec→request surface, two reviewed stages), W2 (per-role image), W3-as-rescoped (cluster-scoped ssh keys, 3B deleted), W4 (#439 money-leak CLOSED, negative-assertion timeout test), W5 (LocalStack contract suite, **blocking in CI**), W6 (`config_version` = the document format gate), W7 part 1 (Hetzner cloud-driver extraction), W9 (doc/catalog sweep), W10 (data-driven spot validator). Only credential-gated W7-AWS-half + W8 remain. **~32 issues resolved**, ~45 commits, all reviewed (APPROVE ×4 on the W-wave). The **owner decision queue was fully drained** (7-item walk + the DHT fork + the envelope freeze). The Editor gap-drain loop ran **four full ticks same-day** (19 filed / 18 closed, cycle <1 h). The LocalStack suite's first CI activation caught **#483** (AwsClient ELBv2 JSON-to-Query + hang-on-error) — fixed, 10/10, suite now blocking. **OPEN: the #467 StreamFanout dig — possibly a product defect** (see "Open at handover").

## Owner rulings (all recorded on the issues + `project_rc3_multicloud_ruling` memory)

1. **rc3 goal = production readiness** (stated verbatim; drives everything below).
2. **Plan**: maximize non-cloud completeness first; ONE cloud campaign at the end; carve-outs: free remote-docker 15-suite gates between batches + one Hetzner provisioning smoke after W1 (NEITHER HAS RUN YET — next session should run the remote-docker gate over the accumulated wave).
3. **Candidate tag moves after every substantial batch** ([[feedback_candidate_tag_after_each_batch]]).
4. **#478**: auto-resume IS the app-consumer cursor contract (test-first, streaming-debt batch).
5. **#467**: root-cause opens streaming-debt; release.yml keeps heavy probes.
6. **#386**: D1–D5 ALL ratified; **ENVELOPE_FORMAT_VERSION frozen at 1000 until GA** (reset from 1007 @ `2c4f4d330`; accept-set unchanged; [[feedback_envelope_frozen_1000_until_ga]]).
7. **#427**: test-first evidence check → found C1 already landed+wired; partitioned C1 closed.
8. **#420**: FULL churn-loss class in rc3; fork ruled **staged arm B** — durable fsync-per-block content-addressed tier, DHT demoted to write-through cache, C3 never built; meta = durable-tier LWW w/ KV ordering authority; durable tier IS rc3; S3 post-GA (analysis: `aether/docs/internal/dht-durability-fork-analysis.md`).
9. **#411**: option 3 (schema-version field + per-stream loud quarantine + all-fail-aborts).
10. **LocalStack**: formally in the validation ladder (unit fakes → LocalStack contract → live smoke → full e2e) AND blocking in CI.
11. **AWS billing stance**: live AWS testing DEFERRED until it is the only step forward (W8 last; "mid-rc3 creds" superseded).

## Landed this session (commit trail)

`679d9cfff` docs #371/#372 → `139d1f8c8` quick-wins #438/#408/#409 → `0256e88d2`/`4cb1d84e8` docs+harness #384/#374/#375/#460/#440 → `7fcccbb30` #458 Heavy tagging (CI PR gate causal; forge-tests job green since) → `95f090cb2` #373 → `ca4dce42f` W9-stable → `ca686b7bd`/`b9ef6c475` **W1 both stages** → `2a8a2f80e` Editor tick-2 batch #468–#472 → `e472a373f` **W2+W10** → `7f9130338` **W6+W3+W4** (#439 closed) → `e6899b5ce`/`c48ae35d3` tick-3 + cursor-truth cascade (#473–#477, #474 per-path verdict) → `fd305e599`/`6d4c6ea75`/`bef6b65fa` tick-4 (#479–#482, #444 delimiter hardening) → `2c4f4d330` envelope freeze → `38ad2dd00` **W5** → `663a75fe2` #427 probe → `ea2e1d290` fork analysis → `603134a4b`/`3999d6567` **#483 AwsClient fix** (suite 10/10 blocking) → `474602d8d` **#428 C2** → `a4f027037` ScaleUp probe fix. RFC-0016 kept as-built throughout (5 reconciliation passes; A9/A10/A11 record deliberate deviations).

## Key technical truths established (each cost real digging — do not re-litigate)

- **Provision funnel**: everything (seed + CLI + CTM heal) flows through static `ProvisionRequest.resolve` → `createFrom`; no provider overrides `provision(spec)`; the three ON_DEMAND literals are inert dead fields.
- **Stream cursor durability is PER-PATH** (#474 arc): NO automatic resume anywhere; disk CursorStore wired only for app `StreamAccess` (explicit commit+`committedOffset()` re-seek); system consumers `none()`→replay-0; `ConsumerRuntimeState` is TEST-ONLY; A6's resume-at-K leg shipped dropped (#478 carries the contract + missing restart test).
- **Entity-fence epoch gate**: consensus-KV authority + engine-local applied high-water enforcement; NOT a DHT keyspace (guarantees.md row + 1c/1d javadoc pinned).
- **Format gate**: single boundary — `parseConfigVersion` on `parse(String)`; the "second site" was dead code (#479, deleted); version gate runs BEFORE template resolution (#480).
- **DHT departure push**: bounded (10 s) + loud overrun with sampled at-risk keys; FULL-replication remains no-op with NO join backfill — that loss path is INTERIM-ACCEPTED until #420 stage-2 (documented on #420).
- **AwsClient**: ELBv2 now Query/XML; 30 s timeout on EVERY request (structural no-hang bound); **ELBv2 is LocalStack-Pro-only** (RFC §4.3 corrected); EC2 hang was `tag:instance-id` filter → NPE **inside `Promise.map` — an in-mapper throw hangs the promise forever** → core design issue **#484** (owner queue; rec: catch-to-Cause + lint).
- **#467 probes**: ScaleUp = readiness race, FIXED (gate on committed config, `a4f027037`); Release-lane green = slow runner accidentally waiting out races.

## OPEN at handover — the StreamFanout dig (#467 second half)

StreamFanoutConsumerTest fails 5/5 nested locally EVEN WITH a warm-up-publish readiness gate (4 fast-fail ~5 s + SlowConsumerNoLoss error 90 s; outer 275 s). No longer race-shaped — the publish path may NEVER become ready in the in-JVM 5-node topology, or the forward/materialization path is genuinely broken (NO cloud coverage exists for fanout ordering/completeness — a product defect here is credible). Coder-w1 was mid-dig (discriminator: poll stream-config visibility with a generous budget — never-materializes vs slow vs post-materialization forward failure), **hard stop ordered before any product change**. Latest diagnostic run's artifacts: `aether/forge/forge-tests/target/failsafe-reports/` (Jul 18 10:0x–10:1x). The uncommitted `StreamFanoutConsumerTest.java` setUp edit is the dig's work-in-progress — do not discard. **First task next session: collect the dig verdict (or re-run the discriminator) and route product-vs-probe.**

## Operational gotchas (this session)

- **Agent stream-timeout drops** (coder-harness ×2, coder-w1 ×3): resume-from-transcript with a tight state-pinned delta worked every time; belt = check tree/artifacts BEFORE resuming, and arm a PID-watch on detached maven runs so results survive the agent (`while kill -0 <pid>; do sleep 15; done` + fresh-file grep).
- **Reviewer/agent final reports frequently fail to auto-deliver** — an idle ping with no report means PING FOR THE VERDICT (worked 4/4×); crossed messages are common — verify claimed-applied edits with grep before committing.
- **forge-tests can only run via `failsafe:integration-test -Pwith-e2e -Dit.test=...`** (surefire skip; `mvn verify` forbidden repo-wide — HCLOUD trap).
- **Fixture/jar staleness**: rebuilt-fresh-before-judging saved the #467 verdict (forge-tests/blueprints sit OUTSIDE the root reactor; `-am` selects nothing).
- Release CI asset upload can `socket hang up` — `gh run rerun --failed` fixes; build itself was green.
- LocalStack suite: docker-gated (colima), `localstack:3.8` pinned; runs blocking in CI on runners.

## Next-session queue

1. **StreamFanout dig verdict** (product-suspect — highest priority; hard stop stands).
2. **Free remote-docker 15-suite gate** over the accumulated wave (plan carve-out 1 — overdue) + the Hetzner provisioning smoke (carve-out 2; needs candidate-tag jar, present).
3. Streaming-debt remainder: #478 restart test + auto-resume → #457/#429–#431 → #411 serializer (option 3).
4. **#420 stage-2 durable tier** (the big rc3 item; design sketch in the fork analysis §work-breakdown).
5. #386 D1–D5 implementation (after streaming-debt; envelope frozen — no bump).
6. Owner queue: **#484 ruling** (Promise.map catch vs total-contract) — only open decision.
7. Remaining rc3: #366, lint track (#449/#450/#454 → #451–#453/#448, #443), #446, #462 impl, #435–#437, #416/#417, #418-adjacent GameDay; W7-AWS-half + W8 when credentials (live AWS LAST per billing stance).
8. Loop: Editor tick-5 surface = stored-format wave + #483/#484 + the tick-4 fixes; MAILBOX monitor re-arm on session start (standing).

## ADDENDUM (2026-07-18, aether-main session #2) — #467 dig VERDICT: PRODUCT DEFECT

**MATERIALIZED-BUT-FORWARD-MISROUTED.** App publish path forwards to the STREAMING-group **leader**, not the partition owner: `StreamPublisherFactory.java:70` wires `DefaultStreamPublisher` to `GovernorResolver::resolver` (leader); the correct HRW `partitionOwnerResolver` (`StreamPublisherFactory.java:102-103`) is never plumbed in. Test publishes to sf-1 = leader = self; app path has NO target==self guard (mgmt path `StreamWriteRouter.forwardToOwner`, `StreamWriteRouter.java:93-104`, HAS one); `QuicClusterNetwork.dispatchPayload` (`QuicClusterNetwork.java:1520-1527`) silently drops send-to-self (peers excludes self, log.debug) → no response → `StreamForwardClient` 5 s timer (`:174`, `:271-273`) → 500 FORWARD_TIMEOUT. Config DID materialize (owner self-promoted CAUGHT_UP at watermark -1); all 44 failures 5000–5009 ms; NOT #484-shaped (timer, not hung promise). Warm-up `publishReady` gate premise wrong — structural, not lag; uncommitted setUp edit moot but kept until the fix lands.

**Fix direction (NOT implemented — hard stop honored):** plumb `partitionOwnerResolver` into `DefaultStreamPublisher` + self-guard (publish locally when target==self). **Structural tangent to decide during fix:** QUIC silent self-drop is a transport-layer trap for ANY future self-send caller — loud-fail vs loopback. Separate symptom seen in logs: `remediateStuckRouting` "cross-node acks not received" = deployment-coordination, not this root cause.

**Session #2 state:** Hetzner standing grant recorded ([[feedback_hetzner_standing_grant]]: cleanup always, 2 h cap). Cloud clean (persistent PG only). Hetzner smoke prepared (plan: `--env cloud --suites 00`, `--skip-teardown` + scoped reaps + `pg-firewall.sh close` manually — bare-reap deletes test-PG per reaper-discipline memory) but NOT launched — session restart planned to inject TARGET_HOST/AETHER_SSH_KEY/AETHER_SSH_USER (absent this session; HCLOUD_TOKEN present via profile). Remote-docker 15-suite gate still pending, unblocks on restart. Second implementing agent offered by owner; proposed partition: lint track #449/#450/#454 → #451–#453/#448, #443 (+ optionally #462) to them; streaming (#467 fix, #478, #457, #429–#431, #411), #420 stage-2, #484, cloud/W7/W8 stay here.
