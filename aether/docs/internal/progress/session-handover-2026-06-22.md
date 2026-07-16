# Session Handover — 2026-06-22

**Branch:** `release-1.0.0-rc2` · **HEAD `d52f7b53d`** · all pushed, working tree clean. Massive session: two big data-layer epics landed (**#337** dialect-aware migration splitter, **#338** migration recovery/atomicity) plus stream-replication + #210 verified/closed and the versioning+media-type epic (#198/#339) scoped into rc2.

---

## ⚡ TL;DR
- **#333/#260/#261 (stream replication): RESOLVED.** Turned out **already fixed in-tree** (verified). Closed #260/#261; reframed #333 to a narrow write-idle residual and **fixed it** (`d2dd76021`) + shipped `GET /api/streams/replicas` observability endpoint + a cluster-B completeness test. **#333's residual is fixed → #333 is closeable.**
- **#210 (cloud SWIM detect): CLOSED.** Product was already fixed (NODE_FAILED from the ungated FSM DEAD edge); the failure was a **harness gap** — re-pointed `wait_for_node_departure` to the authoritative `/api/cluster/membership` (`fe52187d4`).
- **#337 (dialect-aware migration splitter): IMPLEMENTED** (7 commits). New module `aether/pg-tools/sql-splitter` — one pure lexer + 5 dialect descriptors (PG, MySQL/MariaDB, DB2, SQL Server, Oracle), consumed by `AetherSchemaManager` with a 2-mode TxStrategy. **PG + MySQL real-engine-proven.** Kept OPEN for its tail.
- **#338 (recovery & atomicity): IMPLEMENTED** (A+B1+B2). Atomic DDL+history (transactional dialects), internally-versioned history table, autocommit checkpoint+resume. **Live PG bug fixed.** Kept OPEN for its tail.
- **#198 + #339 (API versioning + media types): scoped into rc2** as the final epic (design-heavy, not started).

---

## ✅ Shipped + pushed this session (17 commits, `e992cc200`..`d52f7b53d`)

### Stream replication (#333/#260/#261)
| Commit | What |
|---|---|
| `d2dd76021` | **#333 residual fix** — re-verify a write-idle CAUGHT_UP non-owner replica against the HRW owner (`PartitionBackfill.redriveCandidates` + offset-quiescence). The #260/#261 fixes + the #333 umbrella were ALREADY committed (`79ead7690`/`e0303164a`/`f912a7903`); only this bootstrap-ownership-churn residual remained. |
| `20eb0ac0d` | **`GET /api/streams/replicas/{name}/{partition}`** observability endpoint (full triad) — per-node replica state (`state`/`confirmedOffset`/`isHrwOwner`/`ownerHeadOffset`). Owner-aware (not owner-forwarded; `servedByOwner`/`hrwOwner` flags). |
| `dfed0822d` | cluster-B `02-chaos/test-stream-replica-failover.sh` — owner-kill **completeness** assertion (serves ALL N offsets, not >=1) + the #333 replica-lag sensor. |

### #210 (harness gap, product already shipped)
| `fe52187d4` | `wait_for_node_departure` now asserts via the leaderless `/api/cluster/membership` (`state=="Dead"`) instead of the **deleted** per-node `/api/events` buffer; `/api/events` kept as secondary. 12-network is enabled on cloud (`suite.sh:84`). |

### #337 — dialect-aware migration splitter (7 commits)
| `6921a88e7` | **step 1** — pure `StatementSplitter` + `DialectSpec` + PG descriptor (new module `aether/pg-tools/sql-splitter`, core-only dep, BSL). |
| `b2b70b641` | **step 2** — `AetherSchemaManager` consumes the splitter for PG-family + 2-mode `TxStrategy` (transactional vs autocommit); unmapped dialects keep naive `split(";")` (no regression); `MigrationDialects` mapper. |
| `126f2de43` | **step 3** — PG differential vs a real postgres container. |
| `eb5f8466b`/`c765d3bc7` | **step 4a/4b** — MySQL/MariaDB dialect (DELIMITER/backtick/`"`-string/flat comments) + executor wiring (autocommit) + MySQL container differential. |
| `c42a18427` | **step 5a** — DB2 (`--#SET TERMINATOR`) + SQL Server (GO batch separator + semicolon-suppression + `[…]` + `N'…'`). |
| `93eb88b27` | **step 5b** — Oracle (PL/SQL block mode, `q'…'`, `/`-line terminator). |
| `1606236eb` | docs (CHANGELOG + feature-catalog #128). |

### #338 — migration recovery & history atomicity (A+B1+B2)
| `e9a617c25` | **A — atomicity** — history INSERT folded INTO the migration tx for transactional dialects (PG/DB2/SQLServer/H2/SQLite). Closes the live applied-but-unrecorded-on-crash bug. Real-PG crash-injection test. |
| `fe248a2ee` | **B1 — internally-versioned history table** (approach iii) — meta-version + ordered steps (v2 = dialect-aware ALTER adding `status` + `statements_completed`), once per cluster. Real-PG fresh/upgrade/idempotent. |
| `7971e1f34` | **B2 — autocommit checkpoint + resume** — IN_PROGRESS → per-statement `statements_completed` → SUCCESS/FAILED; resume-skip gate (composes with #118); `queryApplied` filtered to SUCCESS. Fail-at-K-then-resume proof. |
| `d52f7b53d` | docs. |

---

## 🔑 Key reframes & learnings
1. **"Tickets are hypotheses" hit THREE times.** #333/#260/#261 AND #210 were **already product-fixed** — the open issues were stale and/or the failure was a harness/observability gap against deleted code. Lesson reinforced: **verify the cited code state before implementing** (commit + current-code + tests), even when the ticket sounds like a live product bug.
2. **#337 was mis-aimed.** The ticket targeted the PEG grammar (`postgres.peg`), but the real data-integrity bug was the **runtime executor's naive `split(";")`** in `AetherSchemaManager` — a different module the PEG path never touches. Two SQL readers exist; the runtime one was the bug.
3. **Tiered validation (Decision B)** — PG + MySQL validated against real DB containers; Oracle/DB2/SQL Server are descriptor + unit + fake-connector, **real-engine differential deferred** (Oracle/DB2 images heavy/licensed/flaky). Applied to both #337 and #338. Honest, documented, not stubs.
4. **#198/#339 must be co-designed** — both edit `routes.toml`; #198 restructures `[routes]`→`[vN.routes]`. #339 (media types) is a **sub-issue of #198**, using **inline-table route entries** (`get = { route="GET /{id}", produces="text/csv" }`) which survive the flat→versioned restructure. #198 pulled **rc2→** (the schema is a one-way-door). #300 (mgmt-plane version prefix) left rc3.

---

## 🎯 NEXT-SESSION GOAL: finish rc2

**rc2 open = 5: #339, #338, #337, #333, #198.** Recommended order:

1. **Close #333** — its reframed residual is fixed (`d2dd76021`); just close it.
2. **#337 tail** (keep-open items) — Oracle/DB2/SQL Server **real-engine** differential tests (add the container deps; Oracle/DB2 are the flaky ones) + lint-path **PEG** `DollarString`/`RestOfStatement` reconciliation (lint-only). Both minor/deferrable.
3. **#338 tail** — non-PG real-engine validation of the dialect-aware ALTER (B1) + autocommit resume (B2). Same tiered posture.
4. **#198 + #339 — the versioning + media-type epic** (the big remaining rc2 work). **Do the `Testing Strategy` design pass FIRST** (path/header matrices, cross-version/weighted-canary `produces` divergence vs `AppHttpServer` `ActiveRouting`, content-type round-trips incl. raw `byte[]`/multipart, flat-`[routes]` back-compat). One-way-door = the `routes.toml` schema shape — must land in rc2; header-mode/deprecation-automation/§13.4 vendor-media-type-versioning may phase to rc3.
5. **Acceptance: 15/15 Hetzner (container + JVM).** Needs a **fresh CI/native-x86 candidate image** built off all these commits (NOT a Mac-qemu build) before the cloud sweep. Cluster-A and cluster-B as SEPARATE `--suites`; reap by EXPLICIT VM id; `--skip-teardown` mandatory; preserve PG VM; `set -a; source /tmp/aether-test-pg.env` before bootstrap.

---

## ⚠️ Deferred tails & known limitations
- **#337 / #338 non-PG real-engine validation** — Oracle/DB2/SQL Server splitting + the ALTER + resume are unit/fake-connector/by-construction only. PG+MySQL are container-proven.
- **#337 lint-path PEG** not reconciled (runtime bypasses it; lint-only).
- **Oracle splitter best-effort edges** — `CREATE TYPE … AS OBJECT` over-captured as a PL/SQL block; a `;`-only PL/SQL block (no `/`) absorbs to EOF (matches SQL*Plus). DB2 `--#SET TERMINATOR` needs a single internal space.
- **#338 autocommit boundary-statement replay** — a crash between an autocommit statement's commit and its checkpoint update re-attempts that one statement on resume (idempotent ⇒ harmless; non-idempotent ⇒ FAILED for operator). Inherent to non-transactional migrations (Flyway-same), documented in `runAutocommit`.
- **`/api/streams/replicas` is owner-aware, not owner-forwarded** — authoritative only when the request lands on the HRW owner (`servedByOwner=true`); a computed-owner `RouteTarget` is the missing primitive for true forwarding.

---

## 🗂️ Tickets touched
- **Closed:** #260, #261, #210.
- **Closeable now:** #333 (residual fixed).
- **Open, implemented, tail remains:** #337, #338.
- **Open, scoped, not started:** #198 (now rc2; API-versioning design spec — restructures `routes.toml`), #339 (sub-issue of #198; consumes/produces media types, inline-table approach).
- **Filed earlier this session:** #339. **Left rc3:** #300 (mgmt-plane version prefix — sibling one-way-door; decide whether to unify with #198).

---

## 📌 Carry-forward discipline (unchanged)
- **NEVER** `mvn verify` with `HCLOUD_TOKEN` set (creates a real paid Hetzner server). Use `mvn -pl <module> test`; let build-runner own maven.
- **NEVER** `-Djbct.skip=true` for aether builds (POM hierarchy handles it).
- aether/** is **BSL-1.1** (SPDX header on every new file under aether/**).
- Candidate image must be **CI/native-x86**, not Mac-qemu.
- Cloud: cluster-A + cluster-B as separate `--suites`; reap by explicit VM id; `--skip-teardown` always; preserve PG VM.
