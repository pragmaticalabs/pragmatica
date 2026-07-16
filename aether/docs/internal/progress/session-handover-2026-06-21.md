# Session Handover — 2026-06-21

**Branch:** `release-1.0.0-rc2` · **HEAD `49c56202c`** · all pushed (0/0). **Cloud: CLEAN** (only the standing PG VM `aether-test-pg-e8db9b`). Working tree clean.

---

## ⚡ TL;DR
The original "remaining SWIM issue" is **fixed and cloud-proven**, plus a string of related fixes shipped (membership observability endpoint, #290 ADMIN key, scheduled-task pause routing, a ready-lag harness gate). The integration-test goal (**15/15 container**) landed **cluster-A 10/10 cloud-green** and **cluster-B product-green** — but a clean cluster-B chaos sweep in one go is **blocked by Hetzner being in a degraded patch** (capacity flickers + slow VM boots), not by our code. **Next-session GOAL: complete rc2** (5 open issues; proposed order below). Also: a new **SQL-migration-parsing robustness** ticket was filed for rc2 as **#337** (scope + considerations below — to discuss).

---

## ✅ Shipped + pushed this session
| Commit | What |
|---|---|
| `8dc4b5824` | **Self-drain fix** — quorum-loss detector re-fed on the `Member`-boundary FSM edge (`AetherNode.onTransition` → `crossesMemberBoundary` → `propagateMemberCount`). Root was a **wiring gap** (SUSPECT edge never fed the detector), NOT #94 SWIM latency. **+ `GET /api/cluster/membership`** observability endpoint (full REST→CLI→Docs triad). |
| `c4ee29ca3` | **#290** bootstrap operator key now ADMIN (was VIEWER default → 403 on its own admin ops) + populate membership `role` field. |
| `3d5e96137` | **Pause_task fix** — scheduled-task pause/resume routed to `LEADER` (was STRATEGIES owner) so the list read-after-write is consistent. |
| `acfe9089b` | **#17 harness** — `restore_cluster_baseline` READY barrier is now a non-counting advisory poll (was `wait_for` → counted `[FAIL]` on `ready_core_count` lag). |
| `49c56202c` | changelog for the above. |

**Candidate tag decision:** left at `3d5e96137` — it **already contains every product fix in the image** (self-drain, endpoint, #290, role, Pause_task routing). The 2 commits since (`acfe9089b` harness + `49c56202c` docs) are test-harness/docs only → moving the tag would just trigger a redundant image rebuild + republish + `:latest` move. Move it only if you want git-tidiness.

### The self-drain fix — proven (the headline result)
Cloud, fresh 5-node Hetzner cluster, hard-killed core-2/3/4 simultaneously: both survivors went **`strict=5→2, belowThreshold=True` in ~8-10 s** (killed nodes `Suspect`), then **self-drained** (process exited, VMs stayed up). Old behavior: `belowThreshold` stuck `False` ~30 s+, no drain, leaderless wedge / 616× artifact-not-found / 503. The `/api/cluster/membership` endpoint was the live diagnostic throughout. See memory `[[project_selfdrain_suspect_edge_fix]]`.

---

## 🎯 NEXT-SESSION GOAL: complete rc2
**6 open issues. Proposed order (approved):**

1. **Stream-replication backfill cluster — `#333` + `#260` + `#261` (ONE coherent fix).** `#260` silent replica divergence (receiver ignores `fromOffset`, no verify/repair) · `#261` backfill never fires for a fresh replica · `#333` replica false-promotes to CAUGHT_UP from a blind source, stuck behind live offset. `#333` is the umbrella; `#260/#261` are facets. **Data-integrity correctness — highest-value class.** Start by investigating it as one piece.
2. **`#210`** — cloud 12-network SWIM `NODE_LEFT`/`NODE_FAILED` not detected after `kill_node`. Membership-detection; the new `/api/cluster/membership` endpoint is now a diagnosis aid. (Note: a related spurious `SWIM_detection_time` fail appeared in this session's cluster-B run, but only *after* the cluster was externally reaped — that instance was an artifact, not #210 itself.)
3. **#337** — dialect-aware SQL-migration splitting + execution strategy (PG/MySQL/MariaDB/Oracle/DB2/SQL Server). Makes migrations *correct* across dialects. Converged design in the resolved-design section below.
4. **#338** — migration recovery & history atomicity (resume for autocommit dialects + atomic DDL/history write). Makes migrations *recoverable*; the deferred half of the #337 design. **Note:** the DDL/history non-atomicity it fixes is a *live PG bug today*, independent of multi-DB.

Finishing the current release before opening rc3's 103 issues is the discipline.

---

## 🗂️ Full ticket landscape + ordering (rc3 and beyond)
**129 open: rc2 = 6 · rc3 = 103 (24 bug / 26 enhancement / 11 tech-debt / 5 streaming / 8 post-ga) · no-milestone = 20.**

Order by **risk × blast-radius × observability-gap**, grouped into coherent epics (work by subsystem, not issue-by-issue):

| Rank | Epic | Issues |
|---|---|---|
| 1 | **rc2 close-out** | `#333`/`#260`/`#261` (stream replication) → `#210` (cloud SWIM detect) → `#337` (dialect-aware migration split+exec) → `#338` (migration recovery & atomicity) |
| 2 | **Silent prod gaps** (don't-work-in-prod, silently) | `#250` storage demotion/GC wired as **noOp** · `#298` quota/cost cap **stubbed** (← acutely relevant: it's *why* we hit Hetzner server-limits this session) · `#268` resource SPI leaks/use-after-close |
| 3 | **Reconciler / cloud foundational** | `#336` reconciler-under-load provisioning stall · `#297` orphan-cleanup incomplete (reaper Hetzner-only, firewall/floating-IP no-ops) · `#296` hardcoded core label · `#335` scale-500-after-volume-wipe (no-milestone) |
| 4 | **Resource-SPI / interceptor / annotation hardening** (big coherent epic) | `#268`–`#281` (`@Http`/`@Notify`/`@Scheduled`/cache/retry/config/secrets resolution) |
| 5 | **Dashboard / observability-UI completion** | `#291`/`#292`/`#294`/`#303`/`#304` (fake percentiles, dead alerts, read-only ops panel, trace waterfall) |
| 6 | **Tech-debt + CI flake** | `#207` pg-async CI 10-min timeout flake · `#254`/`#216`/`#214`/`#175` … |
| 7 | **Enhancements + post-GA** | the 26 enhancements + the 20 no-milestone future features (`#125` 2PC, `#123` DO/Vultr, `#119` Vault, `#82` IDE plugins, core API niceties `#2`–`#9`) |

---

## ✅ RESOLVED (2026-06-22): SQL-migration robustness → **dialect-aware**, split across [#337](https://github.com/pragmaticalabs/pragmatica/issues/337) (correctness) + [#338](https://github.com/pragmaticalabs/pragmatica/issues/338) (recoverability)

The 2026-06-21 considerations were discussed and the design converged. **Grounded reframe:** there are *two* SQL splitters — the PEG grammar (`postgres.peg`, build/**lint** tooling) with the `$$`-tag/`RestOfStatement` gaps, **and** the runtime executor `AetherSchemaManager.executeStatements:269-281`, a far cruder `split("\n")`+`split(";")` that is **untested** and is the actual data-integrity bug. **And Aether is multi-DB by design** (`DatabaseType`: PG, MySQL, MariaDB, Oracle, DB2, SQL Server, …) with a documented *engine-agnostic migration engine* — so the splitter must be **dialect-aware**, not PG-only.

**Converged design (authoritative spec now in #337):**
- **One pure lexer engine + per-dialect `DialectSpec` descriptors** — data, not code; models *zero* statement semantics (statements split + execute verbatim). Two axes: a lexical table + composable boundary primitives (dollar-quote `$tag$` / redefinable-terminator `DELIMITER`·`--#SET TERMINATOR` / batch `GO` / block-`/`).
- **5 descriptors ≈ 10 engines:** PG-family (PG+Cockroach+Yugabyte), MySQL/MariaDB, Oracle, DB2, SQL Server. **SQLite dropped** — embedded, and its trigger `BEGIN…END` is the only keyword-aware case, so excluding it keeps the engine purely lexical.
- **Dialect-aware execution folded in (the cheap core):** a 2-mode `TxStrategy` classified **per-file** (Flyway-style) — whole-file-transactional (PG/SQLServer/DB2) vs whole-file-autocommit (MySQL/Oracle, or any file containing a non-transactional stmt like `CONCURRENTLY`). ~2 methods + a tiny strategy, **zero schema change**, no new connector primitives. Closes `CONCURRENTLY`-fails-on-PG-today and MySQL/Oracle fake-rollback.
- **Dialect selection** from the connector's `DatabaseType` (no content sniffing).
- **Validation:** differential tests vs the 5 real engines (testcontainers) + fuzz; jOOQ (already in-tree) as a *secondary oracle only* (it normalizes SQL → unfit as the runtime splitter).

**Spun out to #338 (the rabbit hole — deferred, tracked):** partial-migration **resume** for autocommit dialects (per-statement checkpoint = history-schema change + skip logic) + **DDL/history-write atomicity** — already non-atomic today (`AetherSchemaManager:265,297` writes history *outside* the migration tx → applied-but-unrecorded on process death; bites PG **now**). #337 = *correct*; #338 = *recoverable*. "Correct-but-not-yet-auto-recoverable" is the agreed shippable rc2 boundary.

---

## 💡 Considerations on the SQL-parsing work — **RESOLVED 2026-06-22** (dispositions below; authoritative design now in #337/#338)

**Disposition:** #1 decouple split/analyze → **core of #337** · #2 non-tx statements → **#337** (per-stmt tags + 2-mode `TxStrategy`) · #3 server-side simple-query → **rejected** (extended-protocol single-stmt + whole-file implicit tx + no per-stmt errors) · #4 differential oracle → **#337 testing** (real engines in testcontainers; jOOQ as secondary oracle) · #5 fuzz → **#337** · #6 distributed coordination → **already solved** (leader Rabia KV lock + `aether_schema_history` checksums — do NOT rebuild) · #7 checksum stability → **#337** (verbatim split keeps checksums stable) · #8 BOM/encoding, #9 COPY-data mode, #10 psql-meta reject-policy → **#337** · #11 linter dry-run gate → **#337 follow-on** · #12 generated-migration round-trip → **#337 test**. **Resume + DDL/history atomicity → spun out to #338.**

_Original considerations (discussion record, now resolved per the dispositions above):_

1. **Decouple SPLIT from ANALYZE.** Splitting a file into statements needs only a *lexer* (track string/dollar/comment state to find top-level `;`); schema *analysis* (the PEG → `DdlAnalyzer` → `SchemaEvent`) is a separate concern. Conflating them forces the grammar to understand every statement form just to split. A robust **lexer splits**, then the parser analyzes what it understands and **passes through the rest verbatim for execution** (a function body it can't model can still be split + run). Check whether these concerns are cleanly separated today — this is probably the single biggest structural lever.

2. **Non-transactional statements (the Flyway trap).** `CREATE INDEX CONCURRENTLY`, `VACUUM`, `ALTER TYPE … ADD VALUE` (older PG), `CREATE DATABASE` **cannot run inside a transaction**. A robust migration **executor** must tag each statement "transactional?" and run those outside the wrapping `BEGIN/COMMIT`. Parsing should surface this tag. Low-probability-to-hit but high-impact when it does (silent failure or aborted migration).

3. **Whole-file simple-query alternative (design fork).** Instead of client-side splitting, send the whole file as one libpq **simple-query** — PostgreSQL's own lexer splits the `;`-separated statements server-side (100% correct, zero client parsing). **Cons:** runs as one implicit transaction (breaks `CONCURRENTLY`), per-statement error reporting is poor, and the **async pg driver (`PgAsyncSqlConnector`, extended protocol) likely can't multi-statement**. Worth an explicit decision: "client splits + executes each" (robust errors, needs the lexer) vs "server splits" (no lexer, worse errors, protocol-constrained).

4. **`libpg_query` as the correctness oracle / gold standard.** PostgreSQL's *actual* grammar, extracted. A binding would be 100% faithful — but it's a **native dep** (against the minimize-deps principle, memory `[[feedback_minimize_dependencies]]`). Don't adopt it as a runtime dep; **do** consider it (or a real PG in a testcontainer) as a **differential-test oracle**: feed it the same files, compare our statement boundaries against PG's. Catches lexer bugs we'd never enumerate.

5. **Property-based / fuzz testing the lexer.** Generate random SQL (nested dollar-tags, comments, strings, stray `;`) and assert boundary-detection invariants. JBCT-aligned (deterministic, pure `String → Result<List<Statement>>`).

6. **Distributed-migration coordination (the big tangential one).** Aether is a *distributed* runtime. *Who* runs a migration — every node, or one? If multiple nodes can apply the same schema, you need a **single-runner election + a distributed lock** (PG advisory lock, or leader-coordinated) to prevent N nodes racing the same migration, plus a `schema_version` table with checksums for idempotency/resume. The parsing improvement may *surface* this question; it's the kind of gap that bites exactly at GA. Probably its own ticket, but flag it now.

7. **Checksum stability.** Migrations are typically checksummed to detect post-apply edits. A robust parser must yield a **stable normalized form** (comment-stripping / whitespace handling must not change semantics across versions) or re-parsing flags false "modified-after-applied".

8. **Encoding / BOM.** A UTF-8 BOM at file start breaks naive parsers; handle BOM + declared encoding.

9. **`COPY … FROM STDIN` inline data.** Data after `COPY` (terminated by `\.`) can contain `;` and isn't SQL — the lexer needs a COPY-data mode. Low probability in DDL migrations, real edge case for data loads.

10. **psql meta-commands.** If anyone writes `\i`, `\copy`, `\set` (psql scripts), those aren't SQL. Decide pure-SQL-only (reject `\` with a clear error) vs a supported subset. Likely pure-SQL given the JDBC/async driver path.

11. **Migration linter synergy (observability-first).** `pg-schema/.../linter/rules/MigrationPracticeRules` already exists — a robust parser enables a **CI "validate migrations" dry-run gate** (parse + lint all migrations without executing) and new lint rules (non-transactional statement not isolated, missing `IF NOT EXISTS`, …). Surfaces migration risk before it runs.

12. **Generated migrations round-trip.** If the slice-processor ever *generates* migration SQL, the generator output must parse cleanly through the same lexer — add a round-trip test.

---

## 📌 Other state worth carrying
- **15/15 goal status:** cluster-A **10/10 cloud-green** (Pause_task fix proven). cluster-B **all product tests green** (48 passed; 05 ✓, 13 ✓), only failures were the (now-fixed) ready-lag gate + Hetzner instability. A clean one-shot cluster-B sweep awaits a **stable Hetzner window** — re-run `--suites 05,13,12,03,02` then (the #17 fix should make it clean). JVM-runtime sweep still not started.
- **Cloud run discipline (learned hard):** the full sweep with `--skip-teardown` keeps cluster-A's 5 VMs up during cluster-B churn → **Hetzner account server-limit (403)**. Run cluster-A and cluster-B as **separate `--suites` invocations**, reaping cluster-A by **explicit VM ID** (the `$IDS`-variable reaper form glitches; always `hcloud server delete <id> <id> …`) before cluster-B, preserving PG `143262505`. `--skip-teardown` is mandatory (the catch-all reaper at `run-tests.sh:645` kills the PG VM). Source `/tmp/aether-test-pg.env` with `set -a` before `aether cluster bootstrap` (the `${env:PG_*}` secrets).
- **Hetzner capacity probe:** `hcloud server create --name cap-probe --type cx33 --image ubuntu-22.04 --location fsn1 --ssh-key aether-test` then delete — fast way to tell capacity (412) from server-limit (403) before a run.
- **#334 (replacement zone-rotation):** already implemented (commit `1a7f6fad2`), confirmed working; the 13 leaked VMs from the churned run were evidence of it.
