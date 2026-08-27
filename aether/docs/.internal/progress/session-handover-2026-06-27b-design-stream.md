# Design-Stream Session Handover — 2026-06-27b (aether-main coordination + verified @PgSql/slice-processor contract Q&A)

> Companion to `session-handover-2026-06-27-design-stream.md` (the S20 recovery session). This
> session did **no code changes** and **no validation runs**. It (1) posted a design-stream status
> brief to aether-main via GitHub, and (2) produced a **fully source-verified answer set** to 7
> `@PgSql`/slice-processor contract questions for the documentation team ("carved in stone" knowledge).

## ⚡ TL;DR
- **S20 validation state UNCHANGED** — still PENDING (issue #362). Nothing was run this session. The
  prior handover (`...2026-06-27-design-stream.md`) remains the authoritative pickup for S20.
- **PR #358 (#241 slice 2) is now MERGED** by aether-main (was OPEN in earlier handovers). Memory
  `project_design_stream_now_implements.md` updated. **PR #359 (slice 3) still OPEN** awaiting review.
- **GitHub coordination posted:** status brief on **issue #362** (S20 fix root cause + validation plan
  + blockers) and a **review nudge on PR #359** (slice-3 summary + #336/S20 integration-proof caveat).
- **★ Main deliverable: verified @PgSql / slice-processor contract Q&A** (7 questions). Method: 4
  parallel investigators returning `file:line` evidence + golden-fixture/test citations, then I
  personally spot-checked the two counterintuitive load-bearing claims (Q1, Q7) against source. Two
  findings overturn the asker's assumptions. See §3.

## 1. aether-main coordination (this session)
- Verified PR/issue state with `gh` before posting; corrected stale brief (#358 was already merged).
- **Issue #362** comment (`...#issuecomment-4821643051`): S20 root cause (restart_all_nodes can't
  restore running quorum after full self-drain), the committed `lib/cluster.sh` fix, 2-step validation
  plan (remote suite 02 free/authoritative; cloud `02s` deadlock-break), and blockers (cloud torn down,
  PG VM reaped, bootstrap `--timeout` 300→600, reaper footgun).
- **PR #359** comment (`...#issuecomment-4821643125`): slice-3 loop summary + explicit "wired+unit-proven,
  NOT integration-proven, gated on #336/S20" caveat. No nudge on #358 (merged).
- Coordination model unchanged: I own features on `feat/*` off `release-1.0.0-rc2`, push to GitHub
  `origin`; **aether-main reviews + merges — I NEVER self-merge.**

## 2. ⚠️ Sync drift to clear before next #241 work
`#358` merged upstream since the last handover. Before resuming #241 / any branch work:
```bash
git fetch upstream && git rebase upstream/release-1.0.0-rc2   # (or pull --rebase)
```
Not done this session (no code work). Do it first next session so the local branch tracks the merge.

## 3. ★ Verified @PgSql / slice-processor contract (documentation knowledge)
Source-verified against `aether/pg-tools/{pg-codegen,pg-parser,pg-schema}`, `aether/resource/{api,db-async}`,
`jbct/slice-processor`, + golden generated fixtures under `examples/{pg-showcase,pricing-engine,url-shortener-v2}/target/generated-sources`.
Full ASCII text was copied to clipboard this session (12.3 KB); **NOT yet persisted to the repo** — see §4.

**Two findings that OVERTURN the asker's assumptions (both spot-checked in source by me):**
- **Q1 — scalar `Promise<Option<UUID>>` from a bare `RETURNING id` is a latent runtime BUG.** Scalar
  Option IS a supported shape, but `FactoryGenerator.inferScalarColumnName` scans only `SELECT`/`AS`,
  has NO `RETURNING` awareness; with no top-level SELECT it falls through to the literal `"count"`
  (`FactoryGenerator.java:293-295`) → `row.getObject("count", UUID.class)` → column-not-found at runtime.
  **Safe idiom: a single-component record** (`record ClaimedId(UUID id)` → `Promise<Option<ClaimedId>>`),
  whose column name comes from the component (`id`→`"id"`). Candidate codegen-gap issue (§4).
- **Q7 — routes.toml `[errors]` globs match the Cause TYPE simple name, NOT enum-constant names.**
  `HTTP_409 = ["*SEAT_UNAVAILABLE*"]` will NOT route an enum constant. `ErrorTypeDiscovery.mapErrorTypes`
  matches `errorType.getSimpleName()`; `isTypeKind` admits CLASS/ENUM/INTERFACE/RECORD only — `ENUM_CONSTANT`
  is never a candidate. Generated runtime is `switch(cause){ case <Type> _ -> ... }`. Match is
  case-sensitive. **A multi-constant enum-as-Cause gives ALL its constants the same status.** Per-status
  errors require distinct types (`record SeatUnavailable implements Cause`). (Nuance: a single-constant
  enum-as-Cause matches by its own type name.)

**The other 5 (well-supported by golden fixtures/tests, less surprising):**
- **Q2** — data-modifying CTE in ONE `@Query` is fine; SQL passed verbatim (only `:name`→`$N` + record-
  expansion regexes); no statement-shape restriction; schema-validation only when a migration schema exists.
- **Q3** — `:name` binds by **method param NAME** (from APT element model; NOT `-parameters`-dependent);
  a repeated name → one `$N`, one arg (native `$N`, not JDBC `?`); `java.util.UUID` binds native to `uuid`
  (`Oid.UUID` 2950, binary codec).
- **Q4** — failure Cause is a **typed hierarchy, not a Throwable wrapper**: driver `SqlError` (constraint/
  connection — propagated UNCHANGED; unique-violation = `SqlError.ServerErrorIntegrityConstraintViolation`,
  SQLSTATE 23505) vs connector `DatabaseConnectorError` (`ResultNotFound`/`MultipleResults`/mapping only).
  **Async path does NOT normalize SqlError→DatabaseConnectorError** — switch on `SqlError` for DB faults;
  `.mapError(...)` is the domain-translation boundary.
- **Q5** — `@PgSql` factory-param is provisioned like `@Sql SqlConnector` (both `@ResourceQualifier`,
  `config="database"`), plus a `.map(<Persistence>Factory::...)` wrap because the resource type
  (`PgSqlConnector`) ≠ param type. Nested lowercase local record `implements <Slice>` is the idiomatic
  pattern (shipped in pg-showcase); only constraint is the Java rule that persistence is passed as a
  record component (no capture).
- **Q6** — record columns mapped **by name** with auto snake_case→camelCase (`customer_id`↔`customerId`),
  assembled positionally via canonical ctor; SELECT order irrelevant; compile-warning if a component's
  snake_case column is absent. Footguns: no positional fallback; unknown component type silently falls
  back to `getString`.

**Net design guidance given:** distinct record-per-error (Q7) · single-field record from RETURNING (Q1) ·
one `@Query` for the guarded CTE with repeated `:seatId` (Q2/Q3) · pattern-match `SqlError` to tell a
constraint violation from a zero-row guard (Q4).

## 4. Pending follow-ups (offered, not yet done — need go-ahead)
1. **Persist the contract Q&A to the repo** — proposed `aether/docs/reference/pgsql-contract.md` (currently
   only in clipboard; scratchpad is ephemeral and will be wiped). Recommended so the verified knowledge survives.
2. **File the Q1 codegen gap** — `inferScalarColumnName` should read `RETURNING` clauses (or the processor
   should reject scalar-return-without-inferable-column at compile time instead of emitting `"count"`).
3. **Sync** (§2) before next #241 work.

## 5. How to pick up
- **For S20 / #241 implementation:** follow `session-handover-2026-06-27-design-stream.md` §6 verbatim
  (validate remote fix `--env remote --suites 02`; bump bootstrap `--timeout`→600; fix reaper footgun;
  re-provision PG before any cloud run). Sync first (§2). Issue #362 tracks validation.
- **For the doc deliverable:** decide on §4.1 (persist contract to `docs/reference/`) and §4.2 (file Q1 issue).

*Memory updated this session: `project_design_stream_now_implements.md` (#358 OPEN→MERGED).*
