# Session Handover — 2026-07-19 (design stream / aether-clone)

> Covers the 2026-07-18 → 07-19 continuous session: the two-agent work split went live
> (MAILBOX.md protocol), and the entire JBCT lint-track queue from aether-main's delegation
> was executed end-to-end. Ten tickets closed, linter 41 → 64 rules + a new `jbct-derive`
> module, the aether corpus burned down to clean at ERROR for six severities. #443 phase B
> was IN FLIGHT at handover time. Complements aether-main's own 2026-07-19 handover
> (silent-drop arc, #491). Memory files `project-shared-m2-cross-stream-hazard` +
> `feedback-corpus-validate-new-lint-rules` hold the durable lessons.

## Work split (operative)

- **Partition**: this stream owns the JBCT lint track (jbct/ only); aether-main owns
  streaming/cloud/consensus. Owner overrides granted per-case (aether/** burn-downs).
- **MAILBOX.md** (repo root, committed): append-only inter-stream signal log, newest on
  top. Protocol: pull before work blocks; announce every jbct `mvn install` to shared
  `~/.m2` BEFORE running it (RELEASE version 1.0.0-rc3 → last install silently wins for
  the other stream); file claims before touching aether/**; release claims when done.
- aether-main responded in-protocol throughout (BND-01 AWS fix on request, #489 ack,
  claims never collided).

## The queue, as executed (all on release-1.0.0-rc3, pushed)

1. **#449 CLOSED** — score mapping rebucketed to live registry (was 100% retired IDs);
   `categoryFor` → Option, warn-once, bijection invariant test. 9a0957015.
2. **#450 CLOSED** — SLICE-01 removed, **premise corrected**: slice-processor does NOT
   check cross-slice imports (only @Slice codegen); gap was real → delivered later as
   ARCH-04. 9ca2a0615.
3. **#454 CLOSED** — 41/41 fixture coverage (RuleFixtures catalog + parameterized harness),
   registry invariants (severity+fixture+category per rule), suppression coverage.
   **Found JBCT-PAT-02 was dead since inception** (ancestor-walk bug) — revived; RET-06
   had no severity entry — added. e43234d75.
4. **#486 CLOSED + #484 CLOSED** — TOT mapper-safety family (TOT-01/02/03) for the #483
   hang class; owner ruled #484 as keep-Promise-semantics + contract javadoc (c7abf6473)
   + lint enforcement. R-D honestly dropped (vacuous single-file).
5. **#489 CLOSED — the corpus saga** (see memory): first burn-down attempt revealed ~90%
   of TOT findings were RULE FPs (string-blanking, Supplier/AtomicRef .get(), name-collision
   body scans) → rules fixed structurally, churn reverted, real fixes kept (Prompt,
   CloudProviderSupport, gcp/hetzner firstIp). RET-06 hardened then burned down 143/143
   (69 real Option/Verify totalizations + 55 justified suppressions, 2 parallel coders,
   3466+ tests green). **RET-06/TOT-01/TOT-02 at ERROR, corpus clean.** Also found:
   sweep-script stdin bug (mvn in while-read eats the POM list — `< /dev/null`; BOTH
   early "67-module" sweeps were actually ~39).
6. **#451 CLOSED** — easy-tier batch: BND-01 (ERROR after 3-site disposition — 2 fixed by
   aether-main, SliceStore CompletableFuture fixed here incl. the computeIfAbsent-reentrancy
   insight), STY-09, NAM-03/04/05, MUT-01, RET-08 (**null-compare arm DROPPED**: 90% of
   179 corpus hits were correct JDK-boundary checks — flow-aware successor → #455),
   SEAL-02. e50e712d6.
7. **#452 CLOSED** — layering engine: `[lint.layers]` TOML + convention defaults,
   ARCH-01 (ERROR, third-party gated by root-group), ARCH-02/03 (WARNING), ARCH-04
   (ERROR — the #450 gap closed), MIX-01 migrated behavior-pinned. Corpus: 1 ARCH-02
   keyword-collision (WorkerBootstrap → #493). c5f176510. Note: aether corpus is mostly
   layer-UNCLASSIFIED (no book-layout keywords) — ARCH enforcement there activates only
   via [lint.layers] config, owner adoption call.
8. **#453 CLOSED** — FileTypeClassifier + UC-02/ORD-01/INJ-01/VAL-01/STAGE-01/SIDE-01(INFO).
   **Corpus gate caught UC-02 at 100% FP** (annotation-brace header truncation hiding
   `sealed` — same bug fixed in #451 DeclSupport — + execute-only over-capture) → classifier
   fixed pre-ship. Residual: ORD-01 25 real (→#493), SIDE-01 67 INFO calibration. 1489bb71f.
9. **#448 OPEN, phase 1 landed** — MethodShapeClassifier (spine walker + decision table,
   6 shapes) + SHAPE-01/02 census at INFO. Census: MIXED=0, UNCLASSIFIED=5336
   (multi-statement/local-then-return reach limit) → **SHAPE-02 default-disabled**
   (census-on-demand). Phase 2 = reach extension + <5% gate + PAT-02/ZONE-03/NEST-01
   absorption; seams documented. 6debfd989.
10. **#443 CLOSED — both phases done** — `jbct-derive` module (Apache-2.0, jbct-core
    only). Phase A: schema-v0.1 sheet model, entry gate (7 book-vocabulary codes),
    `jbct check-sheet`, SPEC.md synced from `../coding-technology/book-arch-meta/NEXT-STEP-SPEC.md`.
    Phase B: full pipeline (prune→press→resolve→verify→emit), `jbct derive` (exit 0/1/2/3),
    markdown+JSON. All four published runs reproduce recorded moves (exact-set golden
    assertions). **Review caught a real engine bug pre-merge**: first cut missed the
    SPEC §4 narrowest-scope split → wrong topology on 2/4 runs, mislabeled as "judgment";
    adversarial re-derivation ruled it mechanics → fixed (F20/F24 secondary-path split +
    the four split prices + TOPOLOGY_SHAPE emission), re-reviewed for over-split. The one
    real divergence (CH incorporate→BER) is an honest schema-v0.1 gap, relabeled.
    88 tests. 0cc32eaa3 (A) + 8c79e235e (B). **Artifacts repo had NO machine sheets** —
    four runs transcribed from prose (reviewer-verified faithful); upstreaming +
    schema-v0.2 enrichment (mandate strike under-encoding, status-transition reshape,
    thin-tier `contained` marker) flagged for follow-up.

**QUEUE COMPLETE** — the entire lint-track delegation from the work split is done.

## Protocol that emerged (now standard for this track)

**Corpus-validation-first**: implement → review (all findings fixed, non-blocking included)
→ MAILBOX install notice → install → 67-module sweep → sample-audit any heavy count →
fix-rule-first if FP-driven → severity verdict (design severity only when corpus-clean or
dispositioned; provisional WARNING/INFO otherwise) → commit → issue close-out with honest
numbers. It caught: 1 dead rule, 2 rules at ~90-100% FP, 1 spam-volume problem — all
BEFORE shipping at final severity.

## Open items / next session

- **Queue from the work split is COMPLETE.** No open cross-stream dependencies from this side.
- **#493 debt** (all WARNING): SEAL-02 ~50, RET-08 null-arg ~90-120, ORD-01 25, STY-09 4,
  MUT-01 2, ARCH-02 WorkerBootstrap 1 (aether disposition), NAM-05 plugin test-source support.
- **#448 phases 2-3**; **#455** hard tier (incl. RET-08 flow-aware successor).
- **Owner rulings pending**: Q4 per-data-class scoping (on #443); transcription upstreaming;
  Style score-category idea (#449 comment); [lint.layers] aether adoption.
- **Book-repo items**: ORD-01 use-case member order (ticket vs book table conflict),
  NAM-05 naming schema normalization (basic-patterns chapter).
- Registry at 64 rules; jbct-lint suite ~596+38 derive tests; all invariant-guarded.
- ~/.m2 holds current artifacts (SHAPE-02 disabled; all final severities).
