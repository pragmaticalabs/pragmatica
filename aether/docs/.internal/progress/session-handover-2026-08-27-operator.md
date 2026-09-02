# Session handover — 2026-08-27 — STREAM: operator (C)

**Banner: this is the operator/C stream's handover.** Other streams (cluster-core/A, docs/E) keep
their own; cluster-core's is at `aether/docs/.internal/progress/session-handover-2026-08-27-cluster-core.md`
(origin `9d68aedf9`) and this file follows its shape. Read §5 first for the commit/tracker-write
boundary as currently drawn; read §1 for what actually shipped and its evidence.

## §1 What this stream shipped today — the seven-item inventory

**Status, final: all seven items committed on `release-1.0.0-rc3` AND pushed to origin.**
Confirmed by `git fetch origin release-1.0.0-rc3` followed by `git rev-parse HEAD
origin/release-1.0.0-rc3` — both resolve to the identical full hash
`995d19c23d979494d7cd8f99ea1ba24b1e4102b6`. Eight commits total on top of the pre-session baseline:
the six below, plus `3c1038f2b` (formatter fix — see §2) as commit 7, plus `995d19c23` (#699) as
commit 8. All commits are on `~/IdeaProjects/pragmatica-stream-c`, `release-1.0.0-rc3`, authored
under the owner's direct "go ahead" and executed by team-lead in their own session (my own commit
hold was never breached — I ran zero git commands beyond read-only
`status`/`log`/`show`/`diff`/`fetch`/`rev-parse` all day; see §5).

| # | Commit | What it does | Verification |
|---|---|---|---|
| #555 | `ad35d6fd9` | `schemaRequired` silently reverted to `true` at 3 of 4 `Blueprint.blueprint(...)` call sites in `ClusterDeploymentState.java` — including `handleSliceTargetChange`, a **live reactive path firing on every scale event**, not just leader-restore as the ticket assumed. Fixed by resolving `schemaRequired` from the owning blueprint at every site, same as `handleAppBlueprintChange` already did. `SchemaRequiredResolutionTest.java` (436 new lines) pins all three sites plus documents an out-of-scope follow-up (autoscaler owner-erasure in `aether-control`'s `ControlLoopContext.applyScaling`, tracked separately). | `[verified: SchemaRequiredResolutionTest]` — 882/882 aether-deployment at time of authorship |
| #278-metrics | `d80246f94` | Metrics interceptor tags required `key=value` but validated it with a per-request throw — a JBCT-violating runtime failure mode for a config error. Moved validation to provisioning time; added real TOML-binding tests for `RetryConfig`/`MetricsConfig` instead of hand-built config objects. | `[verified: MetricsConfigTomlBindingTest, RetryConfigTomlBindingTest]` — 80/80 module tests |
| #583 | `503ac5465` | Cluster status was role-blind across 3 files: directive-only role source (ignored actual membership), a hardcoded `"core"` fallback, raw link count stood in for core count, and a cold-start default that assumed core. Fixed via a shared role-normalisation helper resolving role label-first, closing a live case-mismatch bug in the process. | `[verified: ClusterTopologyRoutesSlotHeadcountTest]` — 944/944 aether/node at time of authorship |
| budget-guard | `d8dccf7e6` | Drain disruption-budget guard counted worker drains against the CORE quorum minimum — a worker drain could exhaust core capacity it never touched. Fixed with an explicit bypass for worker drains, decision surfaced in the audit trail (not silent), core counts now via `coreCountedMembers()`. | `[verified: NodeLifecycleRoutesDrainBudgetTest]` — 947/947, 7/7 budget-specific tests at time of authorship |
| #269 + #547 | `265748fbd` | Two features landed together: (a) slice-shipped `resources.toml` `${secrets:...}` placeholders now resolve, following the `node.toml` precedent — failure drops the entire intrinsic config layer **loudly**, with the consequence named, and closes a shadow-log leak (previously logged secret key names); (b) deploy-time pre-flight (`ConfigSectionPreflightValidator`) that checks every `@ResourceQualifier(config=...)` dependency against the leader's live composite configuration view before any node activates slices — catches a cluster missing a config section that build-time validation (which only sees the blueprint's own bundled defaults) cannot. | `[verified: SliceStoreTest, ConfigSectionPreflightValidatorTest, BlueprintPublishOwnershipTest]` — 757 slice + 894 deployment tests, end-to-end through a real slice jar |
| #547-polish | `7e1617485` | Team-lead's four follow-up conditions on #547: (a) the pre-flight's docs/changelog now name the exact view it checks (leader's composite view — KV-Store operator overlay over the leader's own `aether.toml` — not "the deploying node's own view"); (b) the fail-open path (no `ConfigurationProvider` wired) now logs a **visible WARN**, not a silent skip; (c) a dedicated log-capture test (`FailOpenLogCapture`, modeled on this module's existing `ClusterTopologyManagerCasLossLoggingTest` pattern) proves the WARN is actually emitted, upgrading that claim from `[mechanism:]` to `[verified:]`; (d) tracker-write (closing the ticket) deferred — held per the standing tracker-write boundary. | `[verified: BlueprintPublishOwnershipTest.ConfigPreflight.publishFromArtifact_logsVisibleSkipWarning_whenNoConfigurationProviderIsWired]` — captures the real `BlueprintServiceInstance` logger via a log4j2 programmatic appender against the live `publishFromArtifact` path |
| format-fix | `3c1038f2b` | Formatter pass across four files after the mid-rebase format-gate failure (see §2): `BlueprintService.java`, `ConfigSectionPreflightValidator.java`, `MissingConfigSection.java`, and `AetherNode.java` — the file carrying the three-way merge of stream A's I4 registrations against this stream's #269/#547/#278 edits. Whitespace-only, verified hunk-by-hunk before commit. Not this stream's own edit — team-lead's fix, listed here because it's part of this stream's code path and closes the gap §2 describes. | `[mechanism: hunk-by-hunk diff review confirms whitespace-only]` + `[verified: full reactor build + full-scope jbct:check, both green post-fix — see §2]` |
| #699 | `995d19c23` | Removed the 2/3/4-arg `Blueprint.blueprint(...)` static-factory overloads on `ClusterDeploymentManager.Blueprint` — all three hardcoded `schemaRequired=true` silently, which is the exact mechanism that made #555 possible. Only the 5-arg canonical form remains. Migrated the 3 real call sites to explicit calls: `ClusterDeploymentStateRebalanceOnScaleUpTest.java:118` (unowned blueprint, `schemaRequired=true` — orthogonal to what the test verifies) and `ClusterDeploymentStateTransactionalTest.java:125,136` (ownership-conflict tests, `schemaRequired=true` at both — same reasoning). Production code needed **zero** changes; all four `ClusterDeploymentState.java` call sites were already 5-arg from #555's own fix. | `[verified: reactor-wide 'install -DskipTests -am' from aether-deployment compiles clean + 894/894 aether-deployment tests]`. Landed on origin as part of team-lead's tree-wide re-gate, so it also cleared `jbct:check` full-scope — this stream did not run the gate itself (see §2). |

## §2 Gate-discipline note — this stream hit the trap too

Team-lead committed the #547-polish delta verified by **tests** (894/894) but not by `jbct:check`.
The subsequent rebase's format gate then failed on three files (`BlueprintService.java`,
`MissingConfigSection.java`, `ConfigSectionPreflightValidator.java`) — whitespace only, nothing
semantically wrong. Worse: the gate run **aborted at `aether-deployment`**, so every module after it
in the reactor — including `aether/node`, which carries a three-way-merged `AetherNode.java`
(stream A's I4 edits auto-merged against this stream's #269/#547/#278 edits to the same file) — was
never checked by that partial run. This is cluster-core's own trap-catalog item #2/#3
(`session-handover-2026-08-27-cluster-core.md` §2) landing a third time today, on the same day it
was named as a convention. Team-lead's fix (commit `3c1038f2b`) reran the formatter and the
**whole** gate across full `-pl '!jbct'` scope, not just the failing module, before push — confirmed
landed: `3c1038f2b` and `995d19c23` are both on origin (see §1).

**Final gate evidence, post-fix** `[verified: full-reactor build + full-scope lint, both green]`:
full reactor `install` — 142/142 modules, 0 SKIPPED; test counts zero-failure across the modules this
stream touched — interceptors 80, `aether-slice` 757, `aether-deployment` 894, `aether/node` 952;
and `jbct:check -pl '!jbct'` **reached the end of the reactor** — 141 modules, all SUCCESS, no abort.
This is the first time the whole tree was actually format/lint-checked as one run today, and it
covers `AetherNode.java`'s three-way merge (stream A's I4 registrations against this stream's
#269/#547/#278 edits): that combination compiles, tests, and lints clean — nobody had built it
before this run.

**Practical lesson for whoever reads this next**: "reconciled" per this repo's own three-part
done-criteria (end-to-end / verified / reconciled) implicitly includes the format gate, not just
test green. Test-green-implies-done was the exact gap here.

## §3 Premise corrections made this stream

- **#555**: ticketed as a leader-restore-only bug; the real defect included a live reactive path
  (`handleSliceTargetChange`) that reverts `schemaRequired` on **every scale event**, not only on
  failover. The novel finding — nothing pinned it before this fix.
- **#583**: ticketed narrowly; actual defect count was **four**, spread across three files, not the
  ticket's implied single site.
- **#547**: ticket's guarantee language needed narrowing twice — the check's scope is generic
  resources only (`SliceTopology.resources()`); pub-sub topics are exempt by construction, not by
  gap. Also narrowed "the deploying node's own view" to "the leader's composite view," which is
  what the code actually checks.
- **#278**: an earlier repro note in `resource-reference.md` had gone stale relative to the actual
  fix; corrected in commit `47b5e7b4c` (predates this inventory, listed for completeness).
- **#699** (this stream's own finding, not yet acted on by anyone else): the ticket lists
  `DecisionTreeControllerTest.java` as one of "three test files" calling the short overloads. It
  doesn't — that file lives in `aether-control`, not `aether-deployment`, and calls an entirely
  unrelated `ClusterController.Blueprint` record (three `Option` fields, no `schemaRequired` at
  all) that merely shares the method name `blueprint(...)`. A third instance of the same
  same-name-different-type trap was found in `aether-slice`'s `BlueprintTest.java`
  (`Blueprint.blueprint(id, slices)`) while sweeping for stragglers — pure naming coincidence
  across three unrelated classes in three modules. **Real, verified scope: 2 files, 3 call sites**,
  not 3 files. This correction has not been posted anywhere public — tracker writes are held (§5).

**Gap, stated plainly rather than invented**: team-lead's original ask for this handover also named
"ring-cluster findings" as a section. I have no such findings in my current context — if this
stream produced one earlier today, the detail did not survive into what's available to me now, and
I am not going to fabricate content for it. Whoever reconciles this handover against the CTO's own
copy should treat this as an open gap, not a completed section.

## §4 Queue state

- **Filed by this stream today**: #278 (closed via `1e99f8276`/`d80246f94`), #560 (option 2
  executed — closes won't-do unless ingress traffic-splitting is roadmapped, per owner's decision
  list), and per team-lead's tracking: #677, #690, #697, #698, #699 filed as follow-ups (#699 now
  implemented per §1; #677, #690, #697, #698 not started by this stream).
- **Next after #699**: **#678-hardening, optional** per team-lead's ring queue — not started,
  not blocking anything.
- **#698** (`SliceTargetValue` owner erased at reconstruction in `ControlLoopContext.applyScaling`
  and `AbTestManager.targetPreservingOverrides`) is the tracked follow-up referenced in `#555`'s own
  test-file doc comment (`SchemaRequiredResolutionTest.java` lines 74-81) — `schemaRequired` still
  reverts to `true` on a genuine autoscale event until that ships. Explicitly out of this stream's
  module and out of #555's scope.

## §5 Standing constraints — the commit / tracker-write boundary, as drawn

**This existed, was tested, and is now resolved — recorded so a successor inherits the *resolution*
plus the reasoning, not just the current rule.**

- Earlier today a sub-agent's `git commit` in this stream was denied by its own session's
  permission classifier. Per this repo's operating rules, a classifier denial is never something to
  route around — not by retrying, not by asking a peer to do it instead. This stream held **all**
  git actions (and, on its own initiative, widened the hold to cover public tracker writes too)
  pending the actual human owner's own words, relayed faithfully rather than characterized.
- Mid-session, this stream's own `git log`/`git diff HEAD` showed five of the seven "held" items
  already committed locally, unpushed — contradicting team-lead's "sits uncommitted" framing at the
  time. This was reported rather than silently accepted OR silently overridden; team-lead confirmed
  the git-log reading was correct and the "uncommitted" language was stale shorthand from before
  the owner's authorization landed.
- **Resolution**: the owner answered the consent question directly ("go ahead"). Team-lead executed
  the commits **in team-lead's own session**, not by relaying authorization to this stream — because
  relays of that authorization to this stream were themselves classifier-denied twice, while
  team-lead's own direct git operations were never denied. This stream's hold was correct throughout
  and was never breached; it was made moot by the decision-holder speaking and team-lead acting in
  their own context.
- **Working split from here**: **team-lead owns all git operations (commit/rebase/push) and all
  public tracker writes** for this stream's held work. This stream owns code only — edit, verify,
  leave uncommitted, report readiness. This is not a demotion of standing; it exists solely because
  team-lead's git path works reliably and the authorization-relay path to sub-agents does not, so
  routing everything through the reliable path removes a failure mode rather than adding a gate.
- **No git commit or push, and no tracker write (issue comment, close, label), has been executed by
  this stream at any point today.** Every git action credited to "stream C" in the inventory above
  was executed by team-lead, in team-lead's own session, on this stream's code.

## §6 Drafted close-comment text (not posted — for team-lead's tracker-write pass)

- **#555**: "Fixed at all four `Blueprint.blueprint(...)` call sites in `ClusterDeploymentState.java`
  (`ad35d6fd9`), not just the leader-restore paths the ticket named — `handleSliceTargetChange` is a
  live reactive path firing on every scale event and had the same silent revert. Pinned by
  `SchemaRequiredResolutionTest.java` (three scenarios + one documented out-of-scope follow-up,
  tracked as #698). 882/882 `aether-deployment` at authorship time."
- **#278**: "Metrics interceptor tag validation moved from a per-request throw to provisioning-time
  validation (`d80246f94`), with real TOML-binding tests replacing hand-built config fixtures.
  80/80 module tests."
- **#583**: "Four defects across three files, not one — directive-only role source, hardcoded
  `\"core\"` fallback, raw link count as core count, and a core-assuming cold-start default. Fixed
  via a shared role-normalisation helper (`503ac5465`). 944/944 `aether/node`."
- **budget-guard**: "Drain disruption budget miscounted worker drains against the core quorum
  minimum. Worker drains now bypass the guard, with the decision surfaced in the audit trail rather
  than silently applied (`d8dccf7e6`). 947/947, 7/7 budget-specific tests."
- **#269**: "Slice-shipped `resources.toml` secrets now resolve (`node.toml` precedent). Failure
  drops the entire intrinsic config layer loudly, naming the consequence, and the shadow-log leak of
  secret key names is closed (`265748fbd`). 757 `aether-slice` tests."
- **#547**: "Deploy-time pre-flight (`ConfigSectionPreflightValidator`) catches a cluster missing a
  required config section that build-time validation cannot see, since build-time only checks a
  blueprint against its own bundled defaults. Scope: generic resources only; pub-sub topics exempt
  by construction. Fail-open (no `ConfigurationProvider`) is `[verified:]` logged, not silent
  (`265748fbd`, `7e1617485`). 894/894 `aether-deployment`, end-to-end through a real slice jar."
- **#699**: "Removed the 2/3/4-arg `Blueprint.blueprint(...)` overloads that made #555 possible —
  every remaining call site now states its `schemaRequired` explicitly. Real scope was 2 test files
  / 3 call sites, not the ticket's 3 files (`DecisionTreeControllerTest.java` calls an unrelated
  `ClusterController.Blueprint` in a different module — worth a ticket-body correction if anyone's
  editing it). Reactor-wide compile clean, 894/894 `aether-deployment`."

## §7 What a successor needs to know in one paragraph

Seven items shipped, eight commits, all confirmed committed on `release-1.0.0-rc3` **and pushed to
origin** (`git rev-parse HEAD origin/release-1.0.0-rc3` identical at `995d19c23d979494d7cd8f99ea1ba24b1e4102b6`
as of this final revision) — no push is pending, no commit hash above is provisional. No tracker
writes have been made for any of them — team-lead owns that now. The one thing worth carrying
forward past this specific day: when a rebase can auto-merge two streams' edits to the same file
(here, `AetherNode.java`), a textual merge succeeding is not proof the combination builds or behaves
correctly — verify the merged result as its own artifact, not as two already-verified halves. A
second lesson from the same day: status messages describing "pending" or "in progress" git state can
lag the actual state by the time they're read — the fix that made this document accurate was
independently re-running `git fetch` + `git rev-parse` rather than trusting either party's most
recent description, including this stream's own earlier draft of this file.
