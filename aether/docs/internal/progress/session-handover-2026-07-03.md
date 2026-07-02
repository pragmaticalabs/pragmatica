# Session Handover — 2026-07-03

**Branch:** `release-1.0.0-rc2` · **HEAD:** `1ccc40487` · **State:** clean, all pushed, in sync with origin.

## TL;DR

The **entire rc2 DX-ticket cluster is shipped or dispositioned** — every ticket from the user's one-by-one review round is done. What remains in rc2 is the **foundational migration/stream cluster** (#337/#338, #333), which was deliberately deprioritized and is **not started**. A read-only **scoping investigation for #337 was in-flight** when this session ended — that is the next session's first step (see below).

## Shipped this session (all committed + pushed)

| Ticket | What | Commit(s) |
|---|---|---|
| **#379** | KV `Remove` epoch/leader fence (reject witnessless/stale deletes of fenced keys) | `79e44fef6` |
| **#380** | DHTConfig consistency correction — FULL is eventually-consistent; dead `isStronglyConsistent()`→`hasQuorumOverlap()` | `0f34a084c` |
| **guarantees.md §7** | retired audit defects #378/#379/#380 | `45e8c9688`, `ed1943037` |
| **#392** | forge archive real version (manifest `Implementation-Version`) + launcher symlink-canonicalizing JRE resolve; **plus** config-selectable `@Http` backend (`[http] backend`, JDK default, Netty opt-in) | `21a8cc6eb`, `f72d9b6fa` |
| **#405** | (found en route) `ProviderBasedConfigService` guarded `parse.TimeSpan` not `io.TimeSpan` → resource configs (HttpClientConfig + 4 others) weren't TOML-bindable. Central fix. | `a514f57d1` |
| **#396** | first-class typed topics — single-source `Topic<T>` constant, `@ResourceQualifier(config="CONSTANT")` binding, compile errors on unknown-constant/type-mismatch, **envelope→1006** | `7524c95d1` |
| **#389** | umbrella: return-field↔SELECT check upgraded regex→CST (`e385cc610`); `jbct fix-slice` add-only CLI (`4c3ea657a`); verify-slice consolidation **descoped as redundant** (checks already fail `mvn compile`). Umbrella closed. | `e385cc610`, `4c3ea657a` |
| **#397** | `ValueMapping<T,P>` unified VO↔boundary descriptor — absorbed PgRepr (DB), added HTTP path/query VO-lift with typed **400**, **envelope→1007**. 1795 tests green. | `140d5f29d` |
| **#399** | `aether/slice-testkit` — spin a slice with fakes-default / Postgres-testcontainer opt-in + typed client + assertions. Verified incl. real container. | `1ccc40487` |
| specs | design specs for #397 + #399 | `983557a98` |

**Dispositioned (no code):** #393 (won't-fix — standalone `@ResourceQualifier` VO-01 exemption is cross-file, undetectable in the single-file CST rule); #403 (passive version stamp accepted; Maven Central publish is release-time).

**rc3 follow-ups filed:** **#406** (typed-topic `Topic<T>` constant name not extractable from an already-compiled dependency module — same-reactor only for now); **#407** (ValueMapping composite/multi-primitive form + facts boundary).

## Envelope format version

Now at **1007** (`ManifestGenerator.ENVELOPE_FORMAT_VERSION`). Progression this session: 1005 → 1006 (#396 typed topics) → 1007 (#397 HTTP VO-lift codegen). Runtime accept-set (`SliceManifest.SUPPORTED_ENVELOPE_VERSIONS`) widened accordingly. Any further codegen-output change must bump again.

## Remaining rc2 (NOT started — foundational, higher-risk)

- **#337** — dialect-aware SQL migration statement splitting + execution strategy (PG/MySQL/MariaDB/Oracle/DB2/SQL Server). **Prior analysis (unverified, 3 days old):** the *real* bug is the runtime `split(";")` in `AetherSchemaManager` (breaks on `;` in string literals / `$$` bodies / `BEGIN…END` / comments), NOT the PEG grammar people assume; design = one lexer + per-dialect descriptors; a 2-mode per-file TxStrategy folds in.
- **#338** — migration recovery & history atomicity: atomic DDL+history write + partial-application resume for autocommit dialects (a live PG bug). Related to #337; confirm the boundary.
- **#333** — stream backfill: write-idle partition stuck at false `CAUGHT_UP`-at-0 after bootstrap ownership churn.

## ⭐ Next session — FIRST STEP

A read-only **#337 scoping investigation** (agent `a0787db076f1748b6`) was launched at session end. Its verdict + concrete plan land at:
`/private/tmp/claude-501/-Users-sergiyyevtushenko-IdeaProjects-pragmatica/db60387f-c99d-4a28-8a30-a680d3606851/tasks/a0787db076f1748b6.output`
If that transcript is gone (new session), **re-run the #337 scoping** (the brief: verify the `AetherSchemaManager` split-logic bug with real repo migrations that break it; assess reusing the `aether/pg-tools/pg-parser` lexer for splitting vs a new dialect-aware splitter; confirm dialect scope actually targeted today; #337-vs-#338 boundary; size/risk/in-JVM validation gate). **Migration execution is high-blast-radius — scope + gate before building.**

## Working method that paid off this session (keep doing it)

- **Verify-before-building repeatedly caught mislabeled tickets.** #389's `@Query`-column check was already fully implemented in pg-codegen (the first triage searched only `jbct/slice-processor`); #393's core was already done; #389 consolidation was redundant with compile-time enforcement. Scope every "next ticket" read-only first — several turned out already-done or descope-worthy.
- **Delegate implementation to `jbct-coder` on the CURRENT branch, NOT worktree isolation.** `Agent(isolation:"worktree")` branches from `origin/main` (stale — hundreds of commits behind rc2), producing unmergeable diffs. See memory `feedback_worktree_baseref_stale_on_release_branch`. Coders leave edits uncommitted; the lead reviews + commits (keeps commit control + serializes builds).
- **Serialize Maven builds** — concurrent `mvn` on shared `target/` causes spurious `NoClassDefFoundError` (the #404 lesson). One build at a time; coders check `ps … plexus.classworlds|surefirebooter` before building.
- **Consistency-lens discipline** produced the #380 fix and the guarantees.md audit — name the per-operation guarantee + mechanism, flag overclaims.

## Environment notes

- Container runtime is **colima** (Postgres testcontainers work — verified `postgres:15-alpine` up in 1.42s this session).
- `mvn verify` fires `HetznerCloudIT` when `HCLOUD_TOKEN` is set → always `env -u HCLOUD_TOKEN` + `-DskipTests` for installs; never `verify`.
- Java 25, Maven; aether/** is jbct-linted (never `-Djbct.skip`).
