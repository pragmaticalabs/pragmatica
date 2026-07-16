# Homepage Corrections

Comparison of `https://pragmaticalabs.io/index.html` against actual codebase (release-1.0.0-rc1).

Generated: 2026-04-05

---

## 1. Line Count — Approximately Correct (Code-Only)

**Website claims:** "320,000 lines of Java"

**Actual (scc code-only, excluding comments and blanks):**

| Module | Total Lines | Code Only | Comments |
|--------|----------:|----------:|---------:|
| Aether | 535K | 187K | 318K |
| Core | 25K | 17K | 5K |
| Integrations | 92K | 64K | 13K |
| JBCT | 60K | 53K | 3K |
| **Total** | **719K** | **327K** | **340K** |

The 320K figure matches code-only lines of the full monorepo (327K currently). This was accurate when written; the AEP pg-parser addition bumped it slightly.

**Fix:** Update to "330,000 lines of Java code" or leave as-is (close enough). If total lines are preferred: "700,000+ lines of Java."

---

## 2. Test Count — Understated

**Website claims:** "500+ tests"

**Actual (depends on scope):**

| Scope | @Test methods |
|-------|-------------:|
| Aether runtime only | 2,964 |
| Core + Integrations | 2,559 |
| JBCT tools | 392 |
| Full monorepo | 5,981 |
| Shell integration scripts | +53 |

Even Aether alone has nearly 3,000 @Test methods — 6x the claimed 500+.

**Fix:** Update to "3,000+ tests" (Aether only) or "6,000+ tests" (full platform). Add "53 Docker integration test scenarios."

---

## 3. E2E Scenario Count — Wrong

**Website claims:** "28 end-to-end scenarios against real clusters"

**Actual:** The `aether/tests/integration/suites/` directory contains **53 shell-based integration test scripts** across 8 suites (smoke, streaming, deployment, cluster-mgmt, observability, network, edge-cases, delegation, database). The old JUnit E2E tests were consolidated — 0 @Test methods remain in `e2e-tests/`. Additionally, 64 Forge integration @Test methods exist.

**Fix:** Update to "53 integration test scenarios against Docker clusters + 64 Forge integration tests" or simply "100+ integration test scenarios."

---

## 4. Forge Default Nodes — Inconsistent

**Website claims:** "5-node laptop simulator"

**Actual:** The Forge guide says default is 5 nodes (`CLUSTER_SIZE` default: 5). But the ecommerce example uses `nodes = 7` in its forge.toml. The default in `EmberConfig` or `ForgeConfig` should be verified.

**Fix:** If 5 is the default, the claim is correct. The ecommerce example overrides to 7 for its specific workload. Suggest: "Multi-node laptop simulator (default 5 nodes)" to avoid implying a fixed number.

---

## 5. Performance Metrics — Source Unclear

**Website claims:**
- 8,000 requests/second throughput
- <5ms p95 latency
- 0.00% error rate

**To verify:** No evidence of these specific numbers in the codebase documentation. The `aether/docker/scaling-test/` directory has k6 soak test scripts and Grafana dashboards, but the claimed numbers aren't documented as benchmark results.

**Issues:**
- What workload? (which example, how many slices, what database)
- What hardware? (laptop, cloud instance, how many nodes)
- What cluster size?
- Is this Forge or real Docker cluster?
- 0.00% error rate is an absolute claim — any real system has some error rate under load

**Fix:** Either:
- Add context: "8,000 req/s on [workload] with [N]-node [environment] cluster on [hardware]"
- Or remove specific numbers and say "Sub-5ms p95 latency at thousands of requests per second" (less precise but defensible)

---

## 6. Soak Test Duration — Needs Verification

**Website claims:** "6.5-hour soak test"

**Actual:** The integration test README mentions "4+ hours" for soak tests (`01-stability` suite). The `soak-test.js` k6 script and `soak-verdict.sh` exist in the scaling test infrastructure. The exact 6.5-hour figure is not found in the codebase.

**Fix:** Verify the actual configured duration in `soak-test.js`. If it's configurable, state the range: "4-6+ hour soak tests."

---

## 7. Feature Count

**Website claims:** "173 documented features"

**Actual:** Feature catalog shows 173. This matches.

---

## 8. "No HTTP clients. No service discovery. No retry logic. No circuit breakers. No serialization code."

**Assessment:** Mostly accurate from the slice developer's perspective:
- **No HTTP clients** — correct, `@Http` provides a pre-configured client
- **No service discovery** — correct, the runtime handles it via KV-Store
- **No retry logic** — partially correct; interceptors handle retry, but developers can configure retry policies
- **No circuit breakers** — same as retry; available via interceptors, configured not coded
- **No serialization code** — correct, the slice processor generates codecs automatically

**Minor nuance:** Developers DO configure retry/circuit-breaker policies in TOML. They don't write the logic, but they choose the parameters. The claim "no retry logic" is about code, not configuration — which is accurate.

---

## 9. "One sprint to first slice in production"

**Assessment:** Aspirational. Depends on:
- Team familiarity with Java 25+ (sealed types, records, pattern matching)
- JBCT methodology learning curve
- Infrastructure setup (cloud provider, database, CI/CD)

For a team already familiar with the tech stack, creating and deploying a simple slice is genuinely fast (`jbct init` → build → deploy). "One sprint" for a real production deployment including monitoring, security, and operations is optimistic.

**Suggestion:** "First slice running in Forge in under an hour. Production deployment timeline depends on infrastructure setup."

---

## 10. Infrastructure Replacement Claims

**Website claims the system replaces:**
- Container orchestration
- Service mesh (mTLS, routing, load balancing)
- Distributed caching
- Schema migrations
- Connection pooling
- Config/discovery services
- Certificate management

**Assessment by category:**

| Claim | Reality |
|-------|---------|
| Container orchestration | Partially — handles deployment, scaling, rollback. No persistent volumes, no pod affinity beyond zone hints. |
| Service mesh | Partially — mTLS, routing, LB work. No traffic policies, no request-level auth forwarding, no observability export (in-memory only). |
| Distributed caching | DHT provides caching but no application-facing cache API (CacheService is in-memory infra slice). |
| Schema migrations | Yes — Flyway-equivalent with consensus coordination. |
| Connection pooling | Yes — HikariCP (JDBC) or native async driver. |
| Config/discovery | Yes — KV-Store config + cloud discovery providers. |
| Certificate management | Yes — self-signed + cloud CA adapters (ACM, GCP CM, Azure KV). |

**Fix:** The claims are directionally correct but should qualify "replaces" with "reduces the need for" in several cases. The external review response already addressed this.

---

## 11. Cloud Providers — Count

**Website claims:** "Four cloud providers (vendor SDK-independent)"

**Actual:** 5 directories under `aether/environment/`: aws, azure, gcp, hetzner, docker. Docker is not a cloud provider. So 4 cloud providers is correct (AWS, GCP, Azure, Hetzner).

---

## 12. Code Example — Minor Style Issue

**Website shows:**
```java
static OrderService orderService(InventoryService inventory,
                                 PricingEngine pricing) {
    return request -> inventory.check(request.items())
            .flatMap(available -> pricing.calculate(available))
            .map(priced -> OrderResult.placed(priced));
}
```

**Issues:**
- `PricingEngine` — is this an actual type in the examples? The ecommerce example uses `PricingService`, not `PricingEngine`. Minor naming inconsistency.
- `.map(priced -> OrderResult.placed(priced))` — JBCT prefers method references: `.map(OrderResult::placed)` if the factory takes a single arg.
- The lambda `available -> pricing.calculate(available)` could be `pricing::calculate` if the method signature matches.

**Fix:** Either use method references per JBCT style, or keep lambdas if the signatures don't align (parameter forwarding is acceptable).

---

## 13. "JBCT" Mention Without Explanation

The homepage mentions "JBCT methodology" and "AI-friendly patterns" but doesn't explain what JBCT is. A visitor unfamiliar with the project won't know this acronym.

**Fix:** Expand on first use: "JBCT (Java Backend Coding Technology) — a methodology for writing predictable, testable Java code optimized for human-AI collaboration."

---

## 14. "Real PostgreSQL support"

Listed alongside the performance metrics. This is a feature statement, not a metric. It's accurate — the async PostgreSQL driver is real and production-grade.

**Suggestion:** Move to the feature list rather than the metrics section.

---

## Summary of Actions

| Priority | Issue | Action |
|----------|-------|--------|
| **Low** | Line count approximately correct (320K code-only → 327K now) | Minor update to ~330K or leave as-is |
| **Critical** | Test count wrong (500+ → 3,000-6,000 depending on scope) | Update number, clarify scope |
| **Critical** | E2E count wrong (28 → 53+ integration + 64 Forge) | Update number and terminology |
| **High** | Performance metrics lack context | Add workload, hardware, cluster size context |
| **High** | Soak test duration unverified | Verify actual duration, update if needed |
| **Medium** | Infrastructure claims overstated | Qualify "replaces" with "reduces the need for" where appropriate |
| **Medium** | JBCT unexplained | Expand acronym on first use |
| **Low** | Code example style | Use method references per JBCT convention |
| **Low** | Forge node count inconsistent | Clarify "default 5 nodes" vs example 7 |
| **Low** | "Real PostgreSQL" placement | Move from metrics to features |
