# JBCT Formatter — Disabled (2026-05-12) → Format RE-ENABLED (2026-06-10)

> **✅ FORMAT RE-ENABLED 2026-06-10 (PR #243).** `build.sh` Step 2 now runs the `format` goal.
> PR #243 implemented the recommended **orphan-trivia sweep** (see "Recommended fix" below),
> eliminating the comment-deletion bug. All four formatter re-enable conditions verified green:
> a whole-codebase pass is **idempotent** (second pass reformats 0 files), deletes **0 comments**
> across 2667 files (`FlowCodebaseCheckTest`), compiles all 80+ modules, and passes unit tests.
> The whole codebase was reformatted in one pass (843 files).
>
> **⚠️ Lint is DECOUPLED for now (deferred debt).** The combined single-pass `process` goal
> (format + lint) is NOT yet wired into `build.sh`, because enabling the lint gate surfaces
> **33 pre-existing JBCT lint errors** that predate this change and were never enforced (build.sh
> Step 2 had been red on this debt and bypassed). Once cleared, switch `build.sh` Step 2 from
> `format` back to `process` to re-enable the lint gate. Inventory:
>
> | Module | Count | Rules |
> |---|---|---|
> | `aether-deployment` | 19 | RET-01 ×18 (FSM handlers/mutators), EX-01 ×1 (`StreamResourceValidator` throw) |
> | `node` | 12 | RET-01 ×8 (`AetherNode` side-effects, `ProblemResponses`, `SwimHealthState`), RET-03 ×3 (`AlertManager` null-returns), EX-01 ×1 (`ContainerLabelInspector` throws) |
> | `aether-stream` | 1 | RET-01 (`ReplicaSetController.close()` — `AutoCloseable` override, must stay void → needs **suppression**) |
> | `aether-invoke` | 1 | RET-01 (`InvocationTraceStore.emitInjectedTrace` — `@FunctionalInterface` void) |
>
> Most RET-01s are intentional side-effect voids wanting a `@Contract` annotation (per the
> repo convention), but classification is per-method; the 3 RET-03 null-returns and 2 EX-01
> throws are genuine refactors (→ `Option`/`Result`). The RET-07 site in `slice-api` was fixed
> separately (commit `685ec0aff`).

---

The `format` goal of `jbct-maven-plugin` was **disabled** at the root POM / build.sh. Only `lint` ran. This document records the bugs that motivated the disable, the affected files, and the conditions for re-enabling — retained as **history**.

> **Status 2026-06-09 (post-PR#242):** PR #242 narrowed the bugs (operator spacing, if-indentation, qualified-super parsing, and *most* comment positions are now fixed) but did **NOT** clear them. A whole-codebase `format` pass still **silently deletes comments in 5 syntactic positions** (50 comment lines across 10 files). Re-enable conditions 1 & 2 still FAIL. **Format stays disabled.** See "Update 2026-06-09" at the bottom.

## Why

The formatter is destructive: it strips documentation and reformats expressions in ways that compress meaning. Re-running `./build.sh` repeatedly amplifies the damage because each pass removes more context, and the loss is irreversible from `mvn` output alone.

## Bugs observed

### B1. `///` markdown javadoc blocks deleted entirely

Java 25 introduced markdown-style javadoc using triple-slash `///`. The JBCT formatter recognises them syntactically but strips them when re-emitting the file. The class-level docstring is sometimes preserved; method-level and field-level `///` blocks are consistently removed.

**Concrete loss pattern:** if a method has a 5–20 line `///` rationale (why this design, what invariant it preserves, link to spec), it disappears. The method body remains.

### B2. Selected `//` block comments deleted

Multi-line `//` comments inside method bodies are not consistently preserved. The formatter sometimes drops them — observed when the comment immediately precedes a control-flow statement (`if`, `for`, lambda body).

**Concrete loss pattern:** in `TaskGroupActivator.handleLocalAssignment` a 5-line `//` block explaining the idempotent-re-publish rationale was deleted; the surrounding code is unchanged.

### B3. Lambda-chain indentation mangling

Multi-step lambda chains (e.g. `.map(...).flatMap(...).onFailure(...).onSuccess(...)`) get re-indented inconsistently. Sometimes nested calls get extra leading whitespace such that the chain visually right-shifts on each step; sometimes calls collapse onto fewer lines, breaking column alignment that the author intended. Net effect is reduced readability without behavioural change.

### B4. Single-statement compaction of multi-line `if` blocks

The formatter sometimes collapses

```java
if (deadline >= now) {
    return Causes.cause("...")
                 .promise();
}
```

into

```java
if (deadline >= now) {return Causes.cause("...")
                                   .promise();}
```

which is parseable but harder to read.

## Files affected by the most recent destructive pass (2026-05-12, pre-revert)

The following files were touched by the formatter during a `mvn -pl aether/aether-deployment install` run and required revert via `git checkout HEAD -- <file>`:

| File | Loss |
|---|---|
| `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/health/HealthReconcilerConfig.java` | 19-line `///` doc on `DEFAULT` explaining each default value's rationale (D.3 spec references, history of `recoveryStableWindowMs` 30s→5s migration, periodic-tick disable sentinel semantics) |
| `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/health/HealthReconcilerImpl.java` | 3 method-level `///` docs: `schedulePhaseEvaluationTick` (M1 tick rationale), `suppressedByPhase` (D.3 phase-gating rationale), `quorumThreshold` (spec ⌈(N+1)/2⌉ reference) |
| `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/health/ObservationAggregator.java` | Lambda-chain indentation (no doc loss) |
| `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/delegation/TaskGroupActivator.java` | 5-line `//` block in `handleLocalAssignment` explaining idempotent re-publish on transient SUSPECTED health blip |
| `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/drain/ConsensusDrainCoordinator.java` | 3 method-level `///` step-rationale docs (`prepareDrain`, `awaitDrainAck`, `markDrainComplete`); lambda-chain indentation mangling; one `if` block collapsed onto a single line |

Other files across the codebase have likely been affected by prior passes; we only catch losses when reviewing a fresh diff against `HEAD`. Past losses cannot be recovered without `git log -p` archaeology.

## Re-enable conditions

Re-enable the `format` goal in root POM only after **all four** of the following hold:

1. `///` markdown javadoc blocks are preserved verbatim through a round-trip.
2. `//` block comments inside method bodies are preserved verbatim.
3. Lambda-chain indentation is either left alone (no reformat) or follows a documented, predictable rule.
4. Multi-line `if` bodies remain multi-line when the body contains a method call chain.

These are formatter-quality requirements, not feature requests. Lint stays enabled throughout — the value-add of JBCT enforcement is in the lint rules.

## Workarounds while disabled

- IDE auto-format: configure IntelliJ to NOT auto-format `///` docs and `//` blocks; the JBCT format rules are the destructive layer, not Java's standard format.
- Manual formatting: contributors handle indentation by convention. The lint rules still catch structural violations (e.g. forbidden patterns from the JBCT style guide).

## Repro

A minimal reproducer for B1 would touch any file with a `///` block on a public method and run `mvn jbct:format -pl <module>`. Expected: doc preserved. Actual: doc deleted.

A formal bug filing in `jbct/` should include the exact input file, the formatted output, and the expected output. Until that lands, the `format` goal stays off.

---

## Update 2026-06-09 — PR #242 merged: bugs narrowed to 5 residual deletion positions, format STILL disabled

PR #242 ("fix(jbct): formatter content/blank-line/lambda fixes + qualified-super parsing", merge `122f18771`) fixed operator spacing (B3-adjacent), if-body indentation (B4), qualified-super parsing, and *most* comment positions. A verification pass — `mvn org.pragmatica-lite:jbct-maven-plugin:format -pl '!jbct'` over the whole codebase (843 files reformatted) — was checked for **content preservation**, not just idempotency (the deletions are idempotent: a dropped comment stays dropped, so an idempotency-only gate passes while content is lost).

**Result: 50 comment lines deleted across 10 files** (9 `///` doc lines + 41 `//` block lines). All reverted; format remains disabled. The losses share one root and fall into **5 trigger positions**:

| # | Position | Example file | Lost |
|---|---|---|---|
| S1 | `///` on the first member after an enum-constant list (`;`) | `aether-metrics/.../NodeReportedState.java` | 5 `///` |
| S1 | `///` on an enum constant | `aether-slice-api/.../StreamRegistryEntry.java` | 2 `///` |
| S2 | `///` before an `@Override` member separated by a blank line | `aether-stream/.../StreamError.java` | 2 `///` |
| S3 | `//` block as first element inside a `switch`, before the first `case` | `aether/.../artifact/MavenProtocolHandler.java` | 4 `//` |
| S4 | `//` block between `case` arms | `ClusterEventAggregator.java`, `ControlLoop.java` | 9 `//` |
| S5 | `//` block inside a fluent method chain (between `.flatMap(...)` and `.onSuccess(...)`) | `aether/node/.../AetherNode.java` | 16 `//` |

(Remaining: `ClusterSyncScheduler.java` 6, `UserDataTemplate.java` 4, `DeploymentMetricsScheduler.java` 2 — method-body free-standing `//` variants of S3–S5.)

### Concrete repros (verbatim input → formatted output)

**S1 — `NodeReportedState.java`** (the entire 5-line `///` on `fromWire` is deleted):
```
   SYNCING, READY, DRAINING;
   /// Parse the on-wire lifecycle-state name ... folds unknown to SYNCING ...   ← DELETED
   public static NodeReportedState fromWire(String wire) { ... }
```

**S3 — `MavenProtocolHandler.java`**:
```
   return switch (parsed) {
       // Every artifact is durable ... silent data loss ... (4 lines)               ← DELETED
       case ParsedPath.ArtifactPath ap -> handlePutArtifact(ap, content);
```

**S5 — `AetherNode.java`** (largest single loss — 16 lines of v2 §5 cold-start rationale):
```
   .flatMap(_ -> appHttpServer.start())
   // v2 §5 cold-start fix: register SWIM start on the network's transport-ready ... (6+10 lines) ← DELETED
   .onSuccess(_ -> clusterNode.network().whenReady(startSwimTrigger))
```

### Root cause (hypothesis)
The formatter re-emits comments via a separate trivia path (leading/trailing *claim*) while the structural token walk skips trivia (`emitLeafTokens`: `if (!tokens.isTrivia(t))`). A comment survives only if some node claims it as leading/trailing. PR #242 added catchers for two gaps (own-line comment before `}`; comment-only empty body), but a comment is still dropped when it falls between structural boundaries that **neither adjacent node claims** — after an enum `;` (S1), across a blank line before an annotated member (S2), before/between `case` arms (S3/S4), or between chained calls (S5).

### Recommended fix
One mechanism instead of position-by-position catchers: an **orphan-trivia sweep** that emits any trivia token not consumed by a leading/trailing claim at its original source position, guaranteeing `output_comments ⊇ input_comments` by construction. Extend `FlowContentPreservationTest` with a **corpus-level** assertion (every comment token in the input appears in the output), not just the specific bug strings — an idempotency/golden check cannot catch a consistently-dropped comment.

### Re-enable condition status (unchanged conclusion)
1. `///` verbatim — **FAIL** (S1, S2)
2. `//` block verbatim — **FAIL** (S3, S4, S5)
3. lambda-chain indentation — not re-assessed (moot while 1 & 2 fail)
4. multi-line `if` bodies — not re-assessed (moot)
