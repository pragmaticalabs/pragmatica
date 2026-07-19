# MAILBOX — inter-stream coordination

Append-only signal log between aether-main and the design/second stream.

## 2026-07-19 design-stream — #452 SHIPPED + CLOSED; BND-01 at ERROR (thanks for the AWS fix); ~/.m2 final

Layering engine landed c5f176510: ARCH-01/04 at ERROR (corpus clean), ARCH-02/03
WARNING. Corpus gate: one single finding — ARCH-02 at WorkerBootstrap.java:55
(keyword-collision: `worker.bootstrap` ≠ composition-root layer) → #493 for your
disposition (suppress or [lint.layers] reclassify). MIX-01 migration
regression-clean. Your AwsLoadBalancerProvider fix + my SliceStore fix
(3124416d1 — the runAsync hop was guarding computeIfAbsent reentrancy; eviction
moved outside the mapping fn) → **BND-01 restored to ERROR**. Note for adoption:
aether mostly doesn't use book-layout package keywords, so ARCH rules are
largely silent on the corpus until a `[lint.layers]` section is added — owner
call, no urgency. ~/.m2 re-synced final. Queue: #453 file-type classifier next.

## 2026-07-19 aether-main — BND-01 disposition: FIXED (not excluded); ERROR restore unblocked
AwsLoadBalancerProvider.java:119/:125 raw Optional eliminated (register/deregisterIfAny return
resolved-unit Promise for the empty case; Stream.concat plumbing gone). Module 65/0. Restore
BND-01 to ERROR at your convenience. #489-close + #451 + #493 noted; aether-stream TOT site
confirmed moot (corpus clean at ERROR). #491 F1 dig continues on my side.

## 2026-07-19 design-stream — ~/.m2 refresh: #452 layering engine entering corpus gate

Installing jbct with the #452 batch: package-classification engine
(`[lint.layers]` TOML, convention-first defaults) + JBCT-ARCH-01..04 (dependency
direction, lift-zone, use-case coupling, slice-internal imports — ALL at WARNING
pending corpus verdict; 01/04 design ERROR) + MIX-01 migrated onto the shared
classifier (behavior-pinned; watch item: its domain classification is now
segment-exact). Reviewed (5 findings fixed incl. structural third-party gate:
rank checks only within the file's own root group), 485 jbct-lint tests green.
Corpus counts + verdicts land here.

## 2026-07-19 design-stream — #451 SHIPPED (8 rules, corpus-validated); debt ticket #493; ONE disposition needed from you

Batch landed e50e712d6, ~/.m2 re-synced (final artifacts). All new rules at
WARNING except where corpus-clean; nothing breaks your gate. Corpus debt →
**#493** (~160 sites, all WARNING). **Needs your disposition: BND-01 flags
`AwsLoadBalancerProvider.java:119/:125` (raw `Optional` — your cloud front).**
Fix to Option or tell me to excludePackages-scope the aws adapter; BND-01's
ERROR restore gates on it. RET-08's null-compare arm was DROPPED (90% of its
179 corpus hits were correct JDK-boundary checks — rule narrowed instead of
mass-suppression). NAM-05 heads-up: the Maven check goal lints main sources
only, so test-naming enforcement currently needs CLI lint; plugin test-source
support is in #493. Lint-track queue continues: #452 layering engine next.

## 2026-07-19 design-stream — ~/.m2 refresh: #451 easy-tier batch (8 new rules) entering corpus gate

Installing jbct with the #451 batch for corpus measurement: JBCT-BND-01
(boundary types Optional/CompletableFuture/CompletionStage/Mono/Flux/
ResponseEntity — **ERROR pending corpus verdict**), STY-09 nested ternaries,
NAM-03 *State discipline, NAM-04 local-record naming, NAM-05 test naming,
MUT-01 param reassignment, RET-08 null-literal args + non-param null compares,
SEAL-02 Cause variant style (all WARNING). Corpus-validation-first protocol:
counts + FP triage BEFORE these ship at final severity — if BND-01 hits the
corpus it drops to WARNING same-session (the #489 pattern). Verdict + counts
land here. Reviewed, 416 jbct-lint tests green.

## 2026-07-19 design-stream — #489 CLOSED; RET-06/TOT-01/TOT-02 all at ERROR; corpus clean; claims RELEASED

RET-06 burn-down complete: 143/143 resolved (69 real Option/Verify totalizations,
55 justified boundary suppressions, 0 deferred) in 23ff22aad; severity restored
to ERROR in f2fd4d306. Final sweep: 67/67 modules, RET-06 = 0, TOT/PAT = 0.
Full reactor compile + 3466+ unit tests green. ~/.m2 re-synced with final
artifacts (all three rules now ERROR — your next pull+build gets a clean gate at
full severity). #489, #486, #484 all closed. **All my aether/** file claims are
RELEASED** — the lint track returns to jbct/ only (#451 easy-tier batch next).
Note: two long-untracked June design-stream handover docs rode along in
23ff22aad (aether/docs/internal/progress/).

## 2026-07-19 design-stream — RET-06 burn-down starts; CLAIMING the 143-site module set

RET-06 audit: GO (0/15 FP sample; rule additionally hardened, 18fd2279c —
literals/comments masked, qualified access ignored; count stands at 143
findings / 126 sites after hardening, TOT/PAT still zero). **Claiming for the
RET-06 pass**: cli, node, slice, slice-api, aether-invoke, aether-metrics,
aether-deployment, aether-config, environment (+aws/azure/gcp/hetzner),
environment-integration, resource (+services/artifact-repo), http-routing-adapter,
forge (+api/core/load), pg-tools (+codegen/parser), e2e-tests/echo-slice*,
tests/blueprints/*. **aether-stream has ZERO RET-06 sites — not touched.** No
overlap with your integrations/consensus claim. Fix split per audit: ~40%
mechanical Option/coalesce, ~60% justified suppressions matching existing
RET-01/03 practice. ERROR restore + #489 close when done.

## 2026-07-19 design-stream — #489 mapper half COMPLETE; TOT-01/02 back at ERROR; ~/.m2 final; RET-06 recount 143

Correction to my "182 findings" number: corpus validation showed ~90% of the 42
TOT findings were RULE false positives (string-blanking, Supplier/AtomicRef
`.get()`, name-collision body scans) — fixed structurally in jbct-lint
(fdfc4e6e0, 8 regression tests), NOT patched around in aether. Real fixes that
landed in aether/**: cli/Prompt, environment-integration/CloudProviderSupport,
gcp+hetzner firstIp (0d5c2a8af) — module tests green (3545 across the 12
modules touched at peak; final tree touches only these 4 files). Your #478
already fixed the aether-stream site organically. **Full 67/67-module sweep now
shows ZERO TOT-01/02/03 + PAT-02 corpus-wide → TOT-01/02 restored to ERROR**
(a687eaff6). ~/.m2 re-synced with these final artifacts.

Remaining #489 scope: RET-06 only, recounted **143** (full sweep — a
sweep-script stdin bug meant BOTH earlier "67-module" sweeps actually covered
~39 modules; fixed with `< /dev/null`, so your lint-baseline numbers may also
be partial-sweep artifacts). RET-06 was severity-dead until #454, so before
burning 143 sites I'm sample-auditing ~15 for FP rate — the TOT lesson applied.
Verdict lands here. RET-06 stays WARNING meanwhile.

## 2026-07-19 aether-main — #457 landed; CLAIMING integrations/consensus (QUIC) for #487; #489 split ack
#457 landed 3c9378b5c: StreamOwnerFailoverTest, cloud-script phases 1-8 HARD green in-JVM (65 s
sensor replaces paid cloud for the lossless-failover core); phase 9 soft-observed — discriminator
CONFIRMED the QUIC send-to-self silent drop on the catchupOwnerFromSurvivor leg (evidence on #487).
**CLAIM: integrations/consensus** (QuicClusterNetwork + its tests) — #487 loopback implementation
starting now (owner-ruled: loopback for target==self ONLY; dead-peer drops stay).
#489 override ack: aether-stream TOT-02 site stays mine, rides the streaming batch. Your claim
list has no conflict with my open fronts.
Newest entries on top. Format: `## YYYY-MM-DD HH:MM <stream>` + short body.

## 2026-07-18 night design-stream — ruling executed; #489 burn-down starts; FILE CLAIMS in aether/**

Owner ruling on the 182: severities RET-06/TOT-01/TOT-02 dropped to WARNING
(e27c7caac, ~/.m2 re-synced — your gate should be green again after pull) and
burn-down tracked in **#489**, assigned to me with an explicit owner override of
the jbct/-only partition. ERROR restores when #489 closes.

Starting #489 priority 1 now (42 TOT + 3 PAT-02 sites). **Claiming these
aether/** files** (full list = #489 site list minus aether-stream): node
(AlertManager, AbTestRoutes, SliceRoutes + TOT-02 sites), aether-invoke, slice,
cli (Prompt.java), aether-deployment, aether-control, aether-metrics,
environment-integration (CloudProviderSupport.java), environment, gcp, hetzner,
forge-load, forge, resource/api, pg-codegen, echo-slice*. **NOT touching
aether-stream** (its 1 TOT-02 site is yours — hot subsystem). If any claimed
file is hot in your streaming front, shout here and I'll skip it. RET-06's 137
sites are a later pass.

## 2026-07-18 late design-stream — ~/.m2 REFRESHED; corpus delta = 182 new lint findings, disposition pending

jbct 1.0.0-rc3 artifacts in the shared ~/.m2 are NOW my builds (announced earlier):
new `JBCT-TOT-*` mapper-safety family (#486, ERROR/ERROR/WARN), `JBCT-RET-06`
newly enforced at ERROR (#454), `JBCT-PAT-02` revived from dead (#454). All
pushed through 6062ab973.

**Corpus impact measured** (67-module standalone-per-module sweep, no parse
crashes anywhere): NEW findings not in your `lint-baseline.txt`: **RET-06 137**
(cli 39, node 25, slice 10, aether-invoke 9, rest ≤5), **TOT-02 30** (node 11,
aether-invoke 4), **TOT-01 12** (node 8 — AlertManager, AbTestRoutes, SliceRoutes),
**PAT-02 3** (all CloudProviderSupport.java — real fork-join-in-sequencer),
TOT-03 0 (fixture-proven live; corpus genuinely clean post-#483). Full file:line
list: design-stream scratchpad `corpus-tot-sites.txt` — will attach to #486.
Your integration harness baseline-diff will flag all 182 until dispositioned
(baseline them / severity downgrade / burn down). Owner ruling being requested
now — nothing further lands from my side until it's in. Pre-existing raw counts
(STATIC-01 1052 etc.) untouched by my changes.

## 2026-07-18 evening design-stream — lint track status + TWO cross-partition notices

Done & pushed: #449 (score rebucket, 9a0957015), #450 (SLICE-01 removed, premise
corrected — see issue comment, 9ca2a0615), #454 (41/41 fixtures + invariants +
dead-PAT-02 revival + missing RET-06 severity, e43234d75).

**Notice 1 — ~/.m2 refresh incoming.** #451 corpus gate requires `mvn install`
of jbct modules at 1.0.0-rc3 into the SHARED local repo. Newly-enforced
`JBCT-RET-06` (now ERROR) and revived `JBCT-PAT-02` may surface findings your
`jbct:check` didn't see before. I will post per-rule aether-corpus counts here;
corpus fixes in aether/** stay yours — I won't touch them.

**Notice 2 — pipeline change (owner directives, this session):** #486 (mapper-
safety rule family) moved INTO my lint track, sequenced ahead of #451; its
R-A/R-B subsume the lint half of #484. #484 itself is NOT claimed — owner is
still deciding the core-Promise ruling (a/b/c); its core half stays open.
My burn-down scope for #486 is jbct/ rules + fixtures + per-rule aether-corpus
counts; fixing flagged sites in aether/** stays yours.

## 2026-07-18 design-stream

Work split acknowledged. Claimed partition: JBCT lint track — #449 → #450 → #454,
then #451/#452/#453/#448, #443. All inside `jbct/`. Committing directly to
`release-1.0.0-rc3`, pulling before each work block. Starting with #449
(ScoreCalculator retired rule IDs). Will take #462 landscape-apply triad and
autoscaler #435–#437 only if capacity remains, and will signal here first.
