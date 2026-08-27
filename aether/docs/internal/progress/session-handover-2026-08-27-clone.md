# Session handover — 2026-08-27: DX batch shipped as nine PRs, the peer network came alive, pg validation got honest

> **Stream: `pragmatica-clone` (design/implementation stream).** Not the aether-main handover. Both
> streams write here on the shared branch — check the banner before reading one as your own state.
> aether-main's are the unsuffixed files.

One directive drove the session — *"take #604–#614 one by one and implement them"* — and a second
workstream arrived mid-flight: the **ticketing session** (posterchild repo) began routing jbct/pg
defects here, escalating from lint-FP census to a pg-codegen **build-blocker**, all resolved.
Everything shipped as PRs against `release-1.0.0-rc3`; **none are merged yet** — the merge round is
the gate for everything downstream.

## §1 Open PRs (9 + the pilot), review-ready

| PR | Closes | One line |
|---|---|---|
| #638 | pilot (spec §5.3) | QUIC typed-error pilot + STY-04 interaction fix it caught |
| #639 | #614 | Boundary naming: version-isolation vs fault boundary; pinning documented as NOT supported; leaderless+leader reconciled in overview |
| #640 | #613 | SliceClassLoader: `javax.*` parent-first iff the platform loader resolves it (probe, not list) |
| #641 | #612 | slice-processor: non-`Promise` dependency methods are a compile error (they were silently dropped OR misread as Promise) |
| #643 | #605 | Generated routes construct request records through their validating factory; failure → typed 400 (owner decision (a)) |
| #650 | #606 | Banking persists via `@PgSql` (owner call); compensation composes with `COMPENSATION_FAILED`; same bug found+fixed in ecommerce PlaceOrder; teach-headers everywhere |
| #652 | #649, #646 | pg validation scopes names by statement structure: `EXCLUDED` + self-reference in DO UPDATE, RETURNING/WHERE against the target — **unblocks the ticketing build** |
| #653 | #608 (half) | Forge debug workflow documented; all six `run-forge.sh` honor `FORGE_JVM_OPTS` |
| #656 | #647 (+#645 code half) | Framework-shape lint FPs: local records exit ORD-01 ranking and SEQ-01 chains; `Topic<Self>` marks facts; UC-02 learns fact consumers + scheduled hooks, exemption fails closed via a parameter-type gate |

Also merged earlier in-session (before this directive): the CAUSE-08 spec-reconciliation fix into
#637's branch, the Causes.java reconciliation into #636 — both since merged by aether-main, along
with #635/#636/#637/#638-era work. `Causes.java` in `../pragmatica` was rolled back to HEAD after
reconciling (owner instruction); the main stream's handover confirms their side is closed.

## §2 The pg validation arc (#649+#646) — the session's sharpest mechanism

Ticketing's build hard-failed on four `INSERT … ON CONFLICT DO UPDATE` version guards. The traced
cause was **double mis-routing, not missing-feature-meets-new-code**: a keyword-presence fallback
(`UpdateKW`+`SetKW` anywhere, no `UpdateStmt`) has ALWAYS routed upsert INSERTs through
`validateUpdate`, where `findAll("ColId").getFirst()` picked the SET target — and **peglib 0.7.3
lexes `version` as `Token VersionKW`**, so post-bump the keyword-spelled target was skipped and the
RHS's first identifier (`EXCLUDED`, or the self-reference qualifier) was reported as a missing
column. Pre-0.7.3 the same wrong path passed by accident. One keyword-spelled SET target per
statement = one error per statement, which closed the reporter's discriminator.

The #646 half converted silent skips into real validation: subquery-free `UPDATE … RETURNING` was
never validated at all (its green was a false negative); newly-real validation surfaced and fixed
three latent defects (CTE-UPDATE, schema-qualified names, kv_store's INSERT validating neither
column). The four upsert shapes are parser-corpus statements now — the #618 lesson enforced:
presence of a statement type is not coverage of its constructs.

## §3 The subtree-attribution bug family — session tally

The same defect class — a nested scope's nodes attributed to the enclosing construct — was
confirmed **six times** across three subsystems this session: ORD-01 (local record ranked as type
member), SEQ-01 (local record body summed as one chain), STY-04 (nested statics made a sum type a
"utility interface", fixed in #638), pg RETURNING (subquery core masquerading as the statement's
SELECT), pg UPDATE-target (`findAll().getFirst()`), and #655's anonymous-class residue. The audit
of every member-scan caller shipped with #656; residue filed as #655. **Any new rule or validator
that walks a subtree must decide consciously whether method-local/nested declarations are its
subjects** — `directlyEncloses` answers "which type encloses", not "is this in the member list".

## §4 The peer network (see memory `project-cross-session-network`)

- **aether-main**: claims #509 / #345 I4–I6 / #628; no-go zones `aether/node`,
  `aether/resource/durable-entity`, `aether/ember`, forge-tests; released #604–#614 wholesale.
  Coordination held all session — zero collisions.
- **ticketing**: routes jbct/pg defects here in prose batches; I file the keepers. Its census:
  **54 suppression tokens, zero hiding real defects** — 53 were framework gaps, now all fixed or
  tracked. Its empirical retraction (SEQ-01 tokens were load-bearing, masking 4 genuine chains —
  of which only 2 were genuine, the others class-2/3 summation) reshaped #645's acceptance:
  **assert 2, not 4**, or the summation bug ships green.
- **The standing gate**: PRs merge → owner installs toolchain (`~/.m2` + `~/.jbct`) → ticketing
  runs its **three-part confirmation in one pass**: (1) four upsert shapes compile, `claimSeat`
  CLEAN (not "fewer errors"); (2) SEQ-01 before/after on the 20-site corpus — expected zero
  survivors, any survivor is a class-2/3 case; (3) CAUSE-pack census + FP audit (prescribed-shape
  enums must be silent) — **this feeds the CAUSE severity freeze** (spec §5 step 4). Note the
  dependency split, from ticketing's own handover: a #652+#656 install alone unblocks parts 1 and
  2; part 3 additionally needs **core** carrying the typed-error API (already merged to rc3 — the
  install just has to include it).

## §5 Issues filed this session

#645 (ORD-01/SEQ-01, census attached, code half shipped in #656 — stays open for the census re-run
and class-2/3), #646 (fixed via #652), #647 (fixed via #656), #648 (pg-codegen positional-binding
DX), #649 (fixed via #652), #651 (correlated subqueries can't see outer scope — needs authored
fixtures, ticketing's corpus has no natural instance), #655 (member-scan residue: ORD-01 on
anonymous-class methods — reproduced; latent `isStepInterface` suppression).

## §6 Resume — approved plan: design specs while PRs merge

Owner approved the ordering; nothing below needs a new decision to start.

1. **#604 + #607 as one design cluster** (they share the invoker/aspect seam): #604 (@Query
   methods cannot join a transaction — the aspect vocabulary is modelled and wired to nothing) and
   #607 (slice-testkit's NoOpSliceInvoker fails every slice-to-slice call; no example uses the
   kit). Spec first via `spec-writer`/`Plan`, implementation after owner review.
2. **#609 OpenTelemetry** — RFC first (`docs/rfc/`), it's a surface-area decision.
3. **#608 watch mode** — design proposal (staleness guard vs envelope, classloader swap, "one
   slice no dep changes" scoping), promised on the issue.
4. **#610** — re-check the lane with aether-main first (their handover listed it near #509's
   batch); it's a replication-correctness item (anti-entropy disabled on routing ReplicatedMaps).
5. **SEQ-01 classes 2/3** — longest-single-chain measurement; small, pinned in
   `CstChainLengthRuleTest` at today's wrong counts (a fix MUST touch those assertions).
6. After merges: ping ticketing for the three-part run; check #645 before freezing CAUSE
   severities; the pilot-migration follow-ups from #638's PR body are all closed.

## §7 Traps & method notes

- **The changelog-anchor trap fired twice more** (assert fails, commit runs anyway — python and
  git were separate statements). Fix adopted mid-session: `grep -q` the marker before `git add`,
  chained with `&&`. Use that shape.
- **Squash-merges sever ancestry** — never stack on the nine open PRs; every branch here is off
  `release-1.0.0-rc3` directly.
- **A test can be self-defeating**: the first UC-02 gate test nested the very `Request` whose
  presence satisfies the rule — empty diagnostics, caught only because the build-runner reported
  the assertion, not the intent. State the failing precondition before writing the fixture.
- **Empirical beats analytical, twice**: ticketing's SEQ-01 "vestigial" claim inverted under one
  CLI run; my ORD-01 mechanism hypothesis survived only because it was checked against the rule
  source before filing. File nothing without reading the code it accuses.
- **The 0.7.3 keyword-kind hazard has now bitten in a place the differential corpus didn't cover**
  (the pg validator's extractor). Any remaining `findAll("ColId"/"QualifiedName").getFirst()` in
  pg-tools is a candidate for the same failure — a sweep was NOT done this session; consider one
  before the next parser bump.
- **UC-02 qualifier recognition is an exclusion list** (single-file linter cannot read
  meta-annotations) — a marked judgment call; ticketing's census reports FN/FP per-site and is the
  sensor for the list's adequacy.
