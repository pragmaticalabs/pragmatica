# JBCT Suppression Audit

**Date:** 2026-04-13
**Branch:** `release-1.0.0-rc1` @ `38600d463`
**Scope:** Full codebase, all `@SuppressWarnings` with JBCT rule IDs

---

## Summary

| Category | Count | Action |
|---|---|---|
| Replace with `@Contract` | ~302 (JBCT-RET-01) | Mechanical replacement |
| Suspicious — hiding real issues | ~16 (VO-02: ~15, RET-03: ~1) | Should be fixed |
| Legitimate | ~720+ | Keep |

---

## 1. JBCT-RET-01 → @Contract (302 cases, 130 files)

All 302 `@SuppressWarnings("JBCT-RET-01")` should be `@Contract`. These are void methods where void is correct by design:

- ~80 interface delegation methods (framework contracts)
- ~60 `@MessageReceiver` callbacks (messaging framework requires void)
- ~100 private fire-and-forget helpers (state mutations, notifications)
- ~15 class-level suppressions
- ~3 test helpers

**Action:** Mechanical find-and-replace. No code logic changes.

---

## 2. JBCT-VO-02 — Suspicious (4 files, ~15 suppressions)

Records that have validating factory methods (`Result<T>`) but whose `with*()` methods bypass validation:

### TransactionConfig.java

`aether/resource/api/src/main/java/org/pragmatica/aether/resource/aspect/TransactionConfig.java`

- Factory: `transactionConfig(TransactionPropagation)` validates `propagation != null` via `Option.option().toResult()`
- `withPropagation(null)` succeeds — bypasses null check
- `withIsolation(null)` succeeds — bypasses null check
- `withTimeout()`, `asReadOnly()`, `withRollbackFor()` — these are safe (primitives/varargs)
- **Fix:** `with*()` methods should delegate through validated path, or validate their input

### ProvisionSpec.java

`aether/environment-integration/src/main/java/org/pragmatica/aether/environment/ProvisionSpec.java`

- Factory: `provisionSpec(...)` returns `Result<ProvisionSpec>` with validation
- `withImage(null)` wraps as `Option.some(null)` — should use `Option.option(null)` which produces `none()`
- Same for `withUserData(null)`, `withPlacement(null)`
- **Fix:** Use `Option.option(value)` instead of `Option.some(value)` in `with*()` methods

### Deployment.java

`aether/aether-invoke/src/main/java/org/pragmatica/aether/update/Deployment.java`

- Public factory `deployment(...)` accepts 10 raw params with zero validation
- `withRouting(null)` succeeds
- **Fix:** Add validation to factory; validate routing in `withRouting()`

### CanaryAnalysisConfig.java

`aether/aether-invoke/src/main/java/org/pragmatica/aether/update/CanaryAnalysisConfig.java`

- Static constant built with `new` bypassing validating factory
- Low risk since values are hardcoded constants
- **Fix:** Use factory method for constants

---

## 3. JBCT-RET-03 — Suspicious (1 case)

### AetherCli.java:771

`aether/cli/src/main/java/org/pragmatica/aether/cli/AetherCli.java`

- `return null;` inside picocli `Callable<Integer>` — should return `0` or `1`
- Picocli interprets null as exit code 0, but explicit is better

---

## 4. JBCT-EX-01 — All Legitimate (~133 suppressions)

Three patterns, all valid:

### Pattern A: Result.lift() boundary (setup generators, schema orchestrator)

```java
// Public API returns Result
public Result<GeneratorOutput> generate(AetherConfig config, Path outputDir) {
    return Result.lift(Generator::toIoError, () -> generateArtifacts(config, outputDir));
}

// Private methods use throws for JDK I/O convenience
@SuppressWarnings("JBCT-EX-01")
private GeneratorOutput generateArtifacts(...) throws Exception { ... }
```

Files: `DockerGenerator`, `LocalGenerator`, `KubernetesGenerator`, `SchemaOrchestratorService.readStreamBytes()`, `BlueprintService.readStreamBytes()`, `ClusterBootstrapOrchestrator.sha256()`, `EchoService.digestSha256()` (×2)

**Assessment:** Correct adapter pattern. Exception lifted to Result at boundary.

### Pattern B: Framework API contracts (Netty handlers)

```java
@Override @Contract @SuppressWarnings("JBCT-EX-01")
public void channelActive(ChannelHandlerContext ctx) throws Exception { ... }
```

Files: `NetworkMetricsHandler`, `Http3Server`, `NettySwimTransport`

**Assessment:** Framework requires `throws Exception` signature. Legitimate.

### Pattern C: JDBC/jOOQ adapter boundary

```java
@SuppressWarnings("JBCT-EX-01") // JDBC adapter -- exceptions lifted at Promise.lift() boundary
public final class JdbcJooqConnector implements JooqConnector { ... }
```

Files: `JdbcJooqConnector`, `JdbcSqlConnector`, `JdbcJooqConnectorFactory`, `JdbcSqlConnectorFactory`

**Assessment:** JDBC API forces checked exceptions. Class-level suppression with boundary lift. Legitimate.

---

## 5. JBCT-VO-02 — Legitimate (~140 suppressions)

### Zero-field singletons

`SecurityPolicy.java`: `Public()`, `Authenticated()`, `ApiKeyRequired()`, `BearerTokenRequired()` — no fields to validate, `new` is the only way to create cached instance.

### Copy-on-write from validated state (private helpers)

`RetryConfig.java`: `withExponentialBackoff()`, `withFixedBackoff()` — private, called from `Result.map()` lambdas. Inputs already validated upstream.

`CircuitBreakerConfig.java`: `DEFAULTS` constant — hardcoded known-good values.

### Pre-validated component composition

`Artifact.java`: `artifact(GroupId, ArtifactId, Version)` — each parameter is already a validated value object. No further validation needed.

### Infrastructure/SPI wiring

`AwsEnvironmentIntegrationFactory`, `HetznerEnvironmentIntegrationFactory`, etc. — SPI factories instantiating config records from already-parsed TOML. Legitimate.

### Config defaults

`LintConfig.DEFAULT`, `RuleCategoryMapping` — hardcoded constants. Legitimate.

---

## 6. Other Rules — All Legitimate

| Rule | Count | Pattern |
|---|---|---|
| JBCT-PAT-01 | ~156 | Raw loops: Netty handlers, class hierarchy walking, performance-critical buffer ops |
| JBCT-SEQ-01 | ~139 | Long chains: builder patterns, configuration wiring, TOML parsing |
| JBCT-UTIL-02 | ~106 | Verify.Is predicates: stable infrastructure code |
| JBCT-UTIL-01 | ~42 | Pragmatica parsing utilities: infrastructure code |
| JBCT-RET-07 | ~28 | Discarded results: side-effecting builder calls (`.onPresent(builder::xxx)`) |
| JBCT-ZONE-02 | ~29 | Zone 3 verbs: framework boundary methods |
| JBCT-RET-03 | ~35 | Return null: JDK API interop (ClassLoader.getResourceAsStream → null check → Option) |
| 28 other rules | ~73 | Various architecture patterns |

---

## Design Opportunity: File I/O Integration

The `Result.lift(() -> Files.writeString(...))` pattern appears across setup generators, config loaders, and other infrastructure. A dedicated file I/O integration could:

1. Provide `FileOps.writeString(path, content) → Result<Unit>`
2. Provide `FileOps.readString(path) → Result<String>`
3. Provide `FileOps.createDirectories(path) → Result<Path>`
4. Provide `FileOps.makeExecutable(path) → Result<Unit>`
5. Eliminate the `throws Exception` + `Result.lift()` adapter pattern for file operations

This would remove the need for JBCT-EX-01 suppressions in file I/O code paths entirely.

See separate investigation for design.

---

## Methodology

- `ndx search "JBCT-*" --file-pattern "*.java"` for discovery
- Manual review of each category with code reading
- Assessed against the question: "does this suppression hide a bug that would be caught if the rule applied?"
- Key heuristic: if a record has a factory returning `Result<T>`, any `new` bypass in public methods is suspicious
