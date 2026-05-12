# JBCT Formatter — Disabled (2026-05-12)

The `format` goal of `jbct-maven-plugin` is **disabled** at the root POM (`pluginManagement` execution). Only `lint` runs. This document records the bugs that motivated the disable, the affected files, and the conditions for re-enabling.

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
