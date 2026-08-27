<!-- SPDX-License-Identifier: BUSL-1.1 -->
<!-- Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko -->

# Session Handover — 2026-07-27 (aether-main, continues the 2026-07-24 arc)

**Branch:** `release-1.0.0-rc3`. **HEAD:** `55ac83f75` + this handover. **Candidate tag** `v1.0.0-rc3-candidate` last re-pointed at `55ac83f75` (Release + CI green) — **re-point after this handover commit**. Working tree clean apart from this doc. **PULL FIRST** (design-stream merges into this branch).

## TL;DR

1. **The getting-started cold path is fixed end-to-end and live-validated.** #510–#515 landed, the tutorial (`aether/docs/getting-started.md`) was written from an executed dry-run and re-walked post-fix, and **leg 4 ran for real on Hetzner** — install → scaffold → forge → cloud → served `{"greeting":"Hello, World!"}`.
2. **Eighteen issues closed this arc; eighteen filed.** Everything closed carries live or mutation-verified evidence in its closing comment — read those before re-investigating anything.
3. **Declarative stream consumers now actually work** (#488 + #535), proven on real hardware at default replication. This was the deepest defect of the arc: TWO stacked silent failures, where the outer hid the inner and the issue text named the wrong layer.
4. **rc3 code work is complete.** Remaining rc3 items are triage decisions, not implementation — see §5.

## 1. What landed (in commit order)

| Commits | Content |
|---|---|
| `9a9d8cfbd`…`3e47ea860` | #510 installer release-rank + `--version` passthrough; #512 cross-generation launcher hygiene; #511/#513/#515 scaffold + two-stage drift gate; #514 bootstrap-config reference |
| `996aaa54a` | getting-started tutorial (legs 1–4 executed) |
| `47388d9d8`, `0a72aaed7`, `8c378c9a7` | #520 dev-switch unification; #521 destroy strands VMs; docs corrected post-fix |
| `3e0f896c5`…`6c22fec0b` | #529 codec resolution; #526 slice-codec binding; #528 rotate-key; #530 KV parse symmetry; #518 schema migration; #525 dead routes |
| `13c8b55fc`, `926969429`, `55ac83f75` | #535 consumer placement + forwarded reads |

**Closed:** #488, #510, #511, #512, #513, #514, #515, #518, #520, #521, #522, #523, #525, #526, #528, #529, #530, #535.

## 2. THE lesson from this arc — read this before writing another test

**`./build.sh` stayed GREEN with the declarative-consumer feature completely disabled.** Compilation and lint cannot detect a silently inert feature. Every major defect here survived for exactly that reason, and each was found only by building a fixture and RUNNING it:

- #488: registrations were never written — a config binding required a `stream-name` key **no `resources.toml` in the repo has ever carried**, and the failure was swallowed by `.option().onPresent(...)`. The issue's own diagnosis ("dangling KV write, no delivery loop") named the wrong layer.
- #526: the entire stream corpus is framework-typed (16 blueprints, all `String`), so the app-typed path — which threw on every publish — was never exercised. A SHIPPED example (`notification-hub`) could not run.
- #530: nine key types serialized but could not be parsed back; nothing noticed because nothing read them.
- #535: forge ran the slice on every node, so the owner∩host intersection was never empty. Real hardware with default placement delivered ZERO.

**Corollary that cost real time:** three separate measurement bugs in MY OWN probes nearly produced wrong conclusions — summing `received` counts across ports when instances < nodes (counts one queue repeatedly), grepping `ownerNodeId` when the field is `ownerNode` (reported "forwarded 0/5" for a working forwarding path), and a test asserting `whenNoOwnerHostsTheSlice` while placing the owner inside the candidate set. **Verify the probe before trusting the measurement.**

## 3. Gotchas ledger (new this arc)

- ⚠️ **`ENVELOPE_FORMAT_VERSION` is FROZEN at 1000 until GA** (owner ruling 2026-07-18, #386) — this **supersedes CLAUDE.md invariant 3**. Bumping emits a stamp outside `SUPPORTED_ENVELOPE_VERSIONS={1000..1007}` and **breaks slice loading for every new build**. I instructed a bump; the agent refused and was right. Memory updated.
- **Worker mode is a MODE of a node, not a module.** There is no `aether/worker` directory, but `AetherNode.activateWorkerMode` is live with `WorkerConfig`/`WorkerConfigLoader`. I inferred removal from a directory listing and nearly deleted a live subsystem's operator surface.
- **`@Contract` on an interface method does NOT propagate to the implementation** — both need it, or JBCT-RET-01 fires.
- **Summing per-node HTTP counts is invalid when instances < nodes** — ports proxy across instances. Use distinct-payload coverage.
- **`-am` pulls Docker modules**; `-pl X -am test` dies in sql-splitter without Docker. Split install from test.
- **Two agent lanes died mid-stream on API stalls.** Both recovered fully via resume-with-state-report-first (`does it compile?` before any new code). No work was lost either time. Use that protocol.
- **Agent messages cross constantly.** Roughly a third of reports arrived after my reply to them, and several were lost entirely — request the report explicitly rather than assuming silence means idle.
- **Live-run measurement:** the fixture's routes are per-stream (`/publish-spread`, `/received-spread`), not one shared pair.

## 4. Open issues filed this arc

**rc3:** #539 (destructive `cluster migrate` prompts, then POSTs to a nonexistent handler), #542 (**failed schema migration does not block slice activation**).

**rc4:** #517 (self-address falls back to localhost), #519 (structural guard: every operator-facing surface traces to a live consumer), #524 (mgmt publish hardwires partition 0 — now DIVERGES from app publishes after #507), #527 (artifact listing needs an index), #538 (`KVStoreSerializer` TOML surface: ~1,535 lines with **no production caller**), #543 (schema undo unreachable — REST undo triggers a FORWARD migration), #545 (two artifacts sharing a consumer group collide), #547 (**no deploy-time check that a cluster defines required env config** — the manifest already records every `configSection`, so a pre-flight is cheap).

## 5. Next session — owner decisions first, then work

**Decisions parked (owner):**
1. **#542** — the naive fix wedges every slice cluster-wide, because `areSchemasReady` scans ALL schema records rather than the slice's own. Needs a scoping decision, not a patch. Highest-value rc3 item.
2. **#538** — wire a consumer (an export/backup CLI) or delete ~1,535 lines. Not deciding keeps the drift alive.
3. **rc3 milestone triage** — 39 open, but most are rc4-shaped riders (durable-entity epic #345, storage chain #248–#264, cloud hardening #296–#306). Real rc3 distance is much shorter than the count suggests.

**Ready to implement, no decision needed:** #517, #519, #524, #543, #545, #547 (#547's gap 1 — `topicNameFallback` silently synthesising config — is a one-liner worth taking regardless).

**Verification debt:** `@Cache(DISTRIBUTED)` / `@Idempotency` are proven to RECEIVE the slice codec (#526) but no live case was executed; a fixture is warranted before claiming those families work.

## 6. Standing state

- **Hetzner:** standing grant, always scoped-reap cleanup, hard 2h cap, never touch `test-pg`. Three clusters run this arc (~€0.30 total), all destroyed, account verified clean each time.
- **Book lane:** part4/part5 written 2026-07-26 in `../coding-technology` (working tree, unpushed, owner reviews prose). Removable banners `BANNER:` for #488/#507/#520/#521/#434 — **#488, #520 and #521 are now FIXED, so those three banners are stale and should come down.**
- **Tutorial:** `aether/docs/getting-started.md` is live-validated through leg 4; its `#522` caveat is now stale (fixed + verified) and should be dropped on the next docs pass.
