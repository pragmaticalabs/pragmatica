# Session Handover — 2026-07-08 (design sprint complete · resilience principles · autoscaler ticket set)

> Continues `session-handover-2026-07-03-262-full-green.md`. That doc closed the #262/#410
> merge train. This session ran the **pre-GA design sprint** end-to-end, adopted the
> **resilience & operability principles**, and traced the **slice-autoscaler pipeline** into an
> rc2 ticket set. Everything is committed AND pushed; no uncommitted work. Session memory
> `project_pre_ga_design_sprint.md` mirrors this doc (plus internal-only notes) — read both.

## Repo state
- Branch `release-1.0.0-rc2`, synced + pushed (remote advanced past `bbfbd0259`; our last commits:
  design-sprint docs + `resilience-operability-principles.md`). Working tree clean except two
  pre-existing untracked design-stream handovers (2026-06-27b, 06-30 — other stream's, leave).
- `archive/s20-observability-2026-06-27` branch: 6 stranded commits from an old local release
  branch; mostly superseded, but `a6baf30fd` (~560-line S20 observability: in-flight-provisions
  Management-API field + harness diagnostic capture) is UNMERGED salvage-candidate work. Triage someday.
- Remote infra: cluster A (5/5) + `forge-postgres` up on `$TARGET_HOST`; cluster B torn down.
  Known env quirks: long background runs intermittently killed (~50%/attempt, 4–13 min — env, not
  product); `run-replonly.sh` against a churned (mid-auto-heal) cluster invalidates
  ownership-stability assertions (data assertions stay valid); `/home/aether/node/target/aether-node.jar`
  on the host is a STALE red herring — deploys push `~/aether-node.jar` → `aether-node:local`
  (verify container-jar md5 vs local).

## Completed this arc (all pushed)

### 1. Pre-GA design sprint — 8 artifacts, all decision-complete
Ranked by design-leverage, executed one-by-one (full detail: sprint memory + each spec's changelog):
1. `durable-pubsub-spec.md` v0.2 (#386, absorbs #264) — two-tier topics; adversarial review fixed 9 findings.
2. `durable-entity-primitive-spec.md` v0.3.0 (#345) — S1 signals-in-v1 (workflow-only §6.6; saga WAIT_SIGNAL v2), S2 TTLs 7d/30d, S4 C=Unit, S5 per-call ReadConsistency §8.1 (both mechanisms v1; lease gated on clock-skew chaos suite). + `hierarchical-storage-spec.md` v1.1 **DD-8** (#349): LocalDiskTier = authoritative ack-gating tier; S3+demotion/GC same cycle; encryption into StorageInstance.
3. `placement-aware-stream-hydration-spec.md` v0.2 (#265) — all 5 questions closed (3 by #410 work).
4. `streaming-spec.md` §10.6 (#411) — coverage-union multi-survivor promotion catch-up + PROMOTION_GAP; validation gate for relaxing pubsub §3.
5. `management-api-versioning-spec.md` (#300) — /api/v1 single-site, hard cutover, trie deleted, 181-route migration, **full stream-surface merge** (flat engine deleted, `system` namespace, identity-first §3.3, stream-namespaces folded in; only Q1 fallback-audit remains). + `migration-statement-manifest-spec.md` (#255, closes #408/#409 design) + `http-media-types-spec.md` (#339 — feature ALREADY landed `130d5c1ee`; spec codifies as-landed).
- **Hand-off prompt delivered to aether-main** (implementation order: #300 → #349 → #345 stack → #386+#265 → #411 → #255+#412). aether-main was already implementing #277 (increments 1–3).
- NOT-design list (already spec'd / diagnosis / verification — don't redo): #241, #277, #230, #336, #365/#367/#376.

### 2. Resilience & operability principles (from Allspaw/GameDay/Vogels/Google-CRE/VOID review)
`aether/docs/architecture/resilience-operability-principles.md` **P1–P7**: recovery-first w/
durability-floor exception · per-scenario recovery budgets, **NO aggregate MTTR anywhere** ·
automation-collaboration invariant ("what does the operator see when this loop is wrong?" —
review lens for #334, DD-8 GC, #350–355) · near-miss telemetry retained/trendable ·
user-perspective black-box SLIs · failure-behavior-in-docs · operator GameDay.
Tickets: **#416** (SLI catalog + built-in black-box probe; kills the #303 defect class),
**#417** (operator GameDay + optional `aether gameday`; book capstone — deliverable, sequenced
after #418), **#418** (Failure Almanac — per-failure-mode catalog: observables/mechanism/budget/
degradation/data-at-risk; maintenance rule: new scenario or near-miss event without an Almanac
row = incomplete), **#419** (contribute analyses to the VOID once public writeups exist).
Cross-link comments on #371/#372/#365/#303/#355. **#418's quality bar is adoption-critical —
see the sprint memory for the internal-only positioning note before writing any public text
about these principles.**

### 3. Autoscaler pipeline trace → rc2 ticket set
Pipeline is **structurally complete** (per-artifact+method #277 collection · leader-only
DecisionTree + CompositeLoadFactor + TTM · SliceTargetKey→CDM→NDM · KV-persisted cooldowns) but
decisions run on **cluster-global aggregates**: `PerSliceMetrics` ships empty `List.of()`;
call-rate rule mis-attributes (any hot method scales the artifact under evaluation,
DecisionTreeController ~:147-185); silent cap-at-cluster-size; no maxInstances; thresholds
unmeasured. Filed (all **rc2**): **#422** mis-attribution bug · **#423** per-slice attribution
(TTM flag-gated off until validated) · **#424** blueprint max-instances/thresholds ·
**#425** SCALE_CAPPED event + per-slice decision snapshot (P3/P4). #369 comment = measure-before-
tune gate. Slice→node capacity link is deliberately ABSENT (over-provisioning death-spiral
guard); #425's event is its honest surface. Order: 423 (fixes 422) → 424/425 → 369 → tune.
Also filed earlier: **#412** (form-urlencoded passes media-type compile check, body JSON-parsed).

## Where the next session naturally starts
1. **aether-main coordination**: they hold the sprint hand-off (order starts with #300). Watch
   for spec questions; the design stream holds full decision context. If they push #300, our
   harness/CLI knowledge of old paths becomes stale — the 181-route migration touches everything.
2. **Design-stream implementation candidates** (clone implements now): #422+#423 (we hold the
   trace, file:line map in ticket bodies + sprint memory), or #418 Failure Almanac authoring
   (sources enumerated in the ticket; pure docs, design-stream-natural).
3. **Deferred/parked**: S19 self-drain flake (separate ticket-worthy); `archive/s20-observability`
   salvage; #339 issue closure housekeeping (aether-main's tracker realm, flagged in hand-off).
4. Before new work: `git pull --rebase origin release-1.0.0-rc2` (aether-main commits frequently).

## Conventions that held this arc (keep them)
- Specs: adversarial/consistency-lens pass before "done"; owner decisions recorded with rationale
  + date; validation gates attached to risky mechanisms; guarantee+mechanism per operation.
- Delegation: investigations → aether-investigator/Explore (file:line-cited), code → jbct-coder,
  builds → build-runner, git chores → chore-runner; single-line commit messages, no trailers.
- Public tracker gets plain deliverable framing only — positioning/meta stays in session memory.
