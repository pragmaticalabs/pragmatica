# Getting Started Guide Corrections

Comparison of `https://pragmaticalabs.io/docs/getting-started.html` against actual codebase (release-1.0.0-rc1).

Generated: 2026-04-05

---

## 1. CLI Command — `jbct init` Syntax

**Website shows:**
```bash
jbct init my-first-slice --slice
```

**To verify:** The `--slice` flag position and exact syntax needs verification against the actual CLI. The `SliceProjectInitializer` exists and generates the project, but the CLI entry point may use a different flag format. Check `InitCommand.java` for exact syntax.

**Alternative command shown:**
```bash
jbct init my-first-slice -g org.mycompany -a my-first-slice --slice
```

This appears correct based on `SliceProjectInitializer` accepting groupId and artifactId parameters.

---

## 2. Generated Directory Structure — Missing and Extra Files

**Website shows:**
```
my-first-slice/
├── pom.xml
├── jbct.toml
├── forge.toml
├── aether.toml
├── run-forge.sh
├── start-postgres.sh
├── deploy-forge.sh
├── schema/
│   └── init.sql
└── src/
    ├── main/java/.../helloworld/HelloWorld.java
    └── test/java/.../helloworld/HelloWorldTest.java
```

**Actual generated files** (from `SliceProjectInitializer`):
```
my-first-slice/
├── pom.xml
├── jbct.toml                    ← matches
├── forge.toml                   ← matches
├── aether.toml                  ← matches
├── README.md                    ← MISSING from website
├── run-forge.sh                 ← matches
├── start-postgres.sh            ← matches
├── stop-postgres.sh             ← MISSING from website
├── deploy-forge.sh              ← matches
├── deploy-test.sh               ← MISSING from website
├── deploy-prod.sh               ← MISSING from website
├── generate-blueprint.sh        ← MISSING from website
├── schema/
│   └── init.sql                 ← matches
└── src/
    ├── main/java/...
    │   ├── {SliceName}.java     ← website says "HelloWorld.java"
    │   └── routes.toml          ← MISSING from website
    └── test/java/...
        └── {SliceName}Test.java
```

**Issues:**
- Missing: `stop-postgres.sh`, `deploy-test.sh`, `deploy-prod.sh`, `generate-blueprint.sh`, `README.md`, `routes.toml`
- Website says `helloworld/HelloWorld.java` — the actual package name is derived from groupId/artifactId, not "helloworld"
- `routes.toml` is a critical file that's not mentioned

---

## 3. Dashboard URL — Wrong Port

**Website says:** "Dashboard accessible at `http://localhost:8080`"

**Actual:** Dashboard port is **8888** (per `forge.toml`: `dashboard_port = 8888`). Port 8080 is the passive load balancer port. The app HTTP port is 8070.

Correct URLs:
- Dashboard: `http://localhost:8888`
- App HTTP: `http://localhost:8070` (nodes: 8070+)
- Load Balancer: `http://localhost:8080`
- Management: `http://localhost:5150`

---

## 4. Test curl Command — Wrong URL

**Website shows:**
```bash
curl -X POST http://localhost:8080/my-first-slice/process \
  -H "Content-Type: application/json" \
  -d '{"value": "hello"}'
```

**Issues:**
1. **Port 8080** is the LB port, not the direct app port. Should be 8070 (direct) or 8080 (via LB) — but should be explicit about which
2. **URL path** `/my-first-slice/process` doesn't match Aether routing. Routes are defined in `routes.toml` with a `prefix` and method mappings. The actual URL depends on what `routes.toml` contains. For example:
   ```toml
   prefix = "/api/v1/hello"
   [routes]
   process = "POST /"
   ```
   Would produce: `http://localhost:8070/api/v1/hello/`
3. **Missing `-s` flag** — `curl -s` is conventional for scripted usage

Correct example (assuming generated routes.toml):
```bash
curl -s -X POST http://localhost:8070/api/v1/my-first-slice/ \
  -H "Content-Type: application/json" \
  -d '{"value": "hello"}' | jq
```

---

## 5. Verify.ensure Usage — Incorrect Overload

**Website shows:**
```java
return Verify.ensure(value, Verify.Is::present, 
                     ValidationError.emptyValue())
             .map(Request::new);
```

**Issue:** The third parameter is `ValidationError.emptyValue()` which returns a `ValidationError` (a `Cause`). But `Verify.ensure` with 3 params expects either:
- `ensure(T value, Predicate<T> predicate, Cause cause)` — correct if `emptyValue()` returns a `Cause`
- `ensure(T value, Predicate<T> predicate, Fn1<Cause, T> causeProvider)` — for value-dependent errors

The call looks correct IF `ValidationError.emptyValue()` returns a `Cause`. But `Verify.Is::present` is the wrong predicate — `Verify.Is.present` is for `Option` values (checks if present), not for `String` values (checks if not blank). For String validation, use `Verify.Is::notBlank`.

**Also:** `Verify.Is::present` — the `Is` inner class method `present` checks `Option.isPresent()`. For a String parameter, the correct predicate is `Verify.Is::notBlank` (non-empty, non-whitespace).

Correct:
```java
return Verify.ensure(value, Verify.Is::notBlank, 
                     new ValidationError.EmptyValue())
             .map(Request::new);
```

---

## 6. Code Example — ValidationError Pattern

**Website shows:**
```java
sealed interface ValidationError extends Cause {
    record EmptyValue() implements ValidationError {
        @Override
        public String message() {
            return "Value cannot be empty";
        }
    }

    static ValidationError emptyValue() {
        return new EmptyValue();
    }
}
```

**Issue:** The JBCT pattern for fixed-message errors recommends using an enum, not a record + static factory:

```java
sealed interface ValidationError extends Cause {
    enum General implements ValidationError {
        EMPTY_VALUE("Value cannot be empty");
        private final String msg;
        General(String msg) { this.msg = msg; }
        @Override public String message() { return msg; }
    }
}
```

However, the record pattern IS valid JBCT (records are for errors that carry context data). Since `EmptyValue` carries no data, the enum pattern is more idiomatic. This is a style suggestion, not a correctness issue.

The static factory `emptyValue()` returning a `ValidationError` instead of `EmptyValue` is fine.

---

## 7. Pipeline Stage Description — Verify Accuracy

**Website claims `mvn clean verify` does:**
1. Compile with slice annotation processor
2. Annotation processing generates factory, manifest, route bindings
3. Unit tests
4. JBCT formatting/linting checks
5. Slice packaging (API and implementation JARs)
6. Blueprint generation (`target/blueprint.toml`)
7. Slice verification

**Issues:**
- Step 4 (JBCT linting): Only runs if `jbct.skip` is NOT set to true. The generated pom.xml from `jbct init` should have JBCT plugin enabled.
- Step 5 (Slice packaging): Verify the Maven plugin goals (`package-slices`) are in the generated pom.xml
- Step 6 (Blueprint generation): Verify `target/blueprint.toml` is the correct output location. The `generate-blueprint` goal produces this.
- The order of steps 4 and 5 may differ from actual Maven phase ordering.

---

## 8. Missing Content

### routes.toml
The getting started guide never shows or explains `routes.toml`, which is a critical generated file. The reader doesn't know:
- Where HTTP routes come from
- How to map methods to HTTP verbs/paths
- How errors map to HTTP status codes

Should include:
```toml
prefix = "/api/v1/hello"

[routes]
process = "POST /"

[errors]
default = 500
HTTP_400 = ["*EmptyValue*"]
```

### aether.toml
Never shown. Even for a simple hello-world without database, the existence of this file and its role should be explained.

### Forge Details
The "Step 4: Run Forge" section is very brief. Should mention:
- What a 5-node cluster means (consensus, leader election)
- Dashboard URL (8888, not 8080)
- App HTTP port (8070)
- How to stop Forge (Ctrl+C)

### deploy-forge.sh
"Step 5: Deploy" uses `./deploy-forge.sh` but doesn't explain what it does (uploads blueprint to running Forge cluster).

### Testing Your Slice
No section on writing tests. The generated `{SliceName}Test.java` exists but isn't explained.

---

## 9. Version Consistency

**Website says:** "JBCT CLI 1.0.0-rc1+"

Verify this matches the actual published JBCT CLI version. If rc1 is the current release, this is correct.

---

## 10. Claim: "No additional repository configuration needed"

**Website says:** "All JBCT and Aether artifacts are published to Maven Central."

**To verify:** Are `pragmatica-lite` and `aether` artifacts actually on Maven Central? If they're on GitHub Packages or a private repo, the generated pom.xml would need repository configuration.

---

## Summary of Actions

| Priority | Issue | Action |
|----------|-------|--------|
| **Critical** | Dashboard URL wrong (8080 → 8888) | Fix port number |
| **Critical** | curl URL wrong | Fix port and path pattern to match routes.toml |
| **Critical** | `Verify.Is::present` wrong for String | Change to `Verify.Is::notBlank` |
| **High** | Generated directory incomplete | Add missing files: stop-postgres.sh, routes.toml, README.md, deploy-test/prod.sh, generate-blueprint.sh |
| **High** | routes.toml never explained | Add section showing routes.toml format |
| **High** | Missing Forge details | Add correct ports, dashboard URL, stop instructions |
| **Medium** | ValidationError style | Consider enum pattern per JBCT convention |
| **Medium** | Pipeline stages | Verify ordering matches actual Maven phases |
| **Low** | Maven Central availability | Verify artifacts are published |
