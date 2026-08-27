# CI Optimization Proposals

## Problem

`mvn install -B` on 133 modules times out at 30 minutes on GitHub Actions runners. Last visible test output at ~3 minutes into the build, then 28 minutes of compilation/testing with no output before timeout.

Key slow areas:
- **FlowCodebaseCheckTest** — 89 seconds (formats 1,970 files across entire monorepo)
- **FlowIdempotencyDebugTest** — 88 seconds (same, redundant with above)
- **Consensus module** — 47 seconds (Rabia protocol tests)
- **Aether modules** (59 modules) — bulk of time is compilation + annotation processing + code generation

## Proposals

### 1. Increase timeout (immediate fix)

Change `timeout-minutes: 30` to `timeout-minutes: 45` in `.github/workflows/ci.yml`.

**Pros:** Fixes the immediate problem.
**Cons:** Doesn't address the underlying slowness. Will break again as more code is added.

### 2. Enable parallel Maven build

Add `-T 1C` (one thread per CPU core) to the Maven command:

```yaml
- name: Build and test all modules
  run: mvn install -B -T 1C
  timeout-minutes: 30
```

GitHub Actions runners have 2-4 cores. This could cut build time significantly since many modules are independent.

**Pros:** Significant speedup with minimal risk.
**Cons:** Some modules may have implicit ordering dependencies not declared in POMs. Need to verify thread-safety of annotation processors.

### 3. Remove or reduce FlowCodebaseCheckTest from CI

The flow formatter codebase check (89s) and idempotency debug test (88s) are **regression tests that scan the entire monorepo**. They're valuable locally but waste CI time.

Options:
- Mark them `@Tag("slow")` and exclude with `-Dgroups='!slow'` in CI
- Move to a separate scheduled nightly job
- Reduce scope: check only changed files in CI, full scan nightly

### 4. Skip examples in CI

The 23 example modules compile but don't contribute tests. Skip them:

```yaml
run: mvn install -B -pl '!examples/ecommerce,!examples/url-shortener,...'
```

Or add a `skip-examples` profile.

### 5. Split into parallel CI jobs

```yaml
jobs:
  core-and-integrations:
    run: mvn install -B -pl core,integrations -am
    
  jbct-tools:
    run: mvn install -B -pl jbct -am
    
  aether:
    needs: [core-and-integrations, jbct-tools]
    run: mvn install -B -pl aether -am
```

**Pros:** Maximum parallelism, each job under 15 minutes.
**Cons:** Complex workflow, artifact passing between jobs, cache management.

### 6. Create .mvn/maven.config

```
-T 1C
--no-transfer-progress
```

This applies to ALL Maven builds (CI and local), reducing output noise and enabling parallelism by default.

## Recommended Approach

**Immediate (today):**
1. Increase timeout to 45 minutes
2. Add `-T 1C` parallel build

**Short-term (this week):**
3. Tag slow formatter tests, exclude from CI
4. Skip examples compilation in CI

**Medium-term:**
5. Split into parallel jobs if build continues growing

## Local Build Improvement

The user also reported local builds getting slower. `.mvn/maven.config` with `-T 1C` helps everywhere. For local development, encourage:

```bash
mvn test -pl <module> -am     # Test only the module you're working on
mvn install -DskipTests       # Fast install when you need artifacts
```
