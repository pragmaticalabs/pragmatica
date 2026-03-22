# Evolutionary Implementation Protocol

## Problem

Big features implemented as monolithic blocks accumulate hidden defects that surface late in testing, causing unpredictable fix cycles. The pattern: implement everything → test at the end → discover gaps → iterate → finally stable.

## Solution

Decompose every feature into **layers** with hard correctness gates. Each layer is independently committable, tested, and verified before the next begins. No downstream cascade from late-discovered design issues.

## Layers

### Layer 1: Types + Interfaces
**Scope:** API surface — records, sealed interfaces, enums, factory methods. No logic beyond validation.

**Gate:** Compiles. JBCT check passes. Type construction tests pass (valid/invalid inputs, serialization round-trip if `@Codec`).

**Typical size:** 0.5–1 day.

### Layer 2: Core Logic
**Scope:** Single-node behavior. Pure data structures, algorithms, state machines. No distribution, no consensus, no networking.

**Gate:** Compiles. Unit tests cover happy path, edge cases, error paths, concurrency (if applicable). Benchmarks if performance-sensitive.

**Typical size:** 1–2 days.

### Layer 3: Integration
**Scope:** Wiring into the existing system. Consensus operations, KV-Store types, CDM hooks, MessageRouter events, annotation processor changes.

**Gate:** Compiles. Integration tests in Forge (multi-node, real consensus, real KV-Store). Existing tests still pass.

**Typical size:** 1–2 days.

### Layer 4: API + CLI
**Scope:** External surface. REST endpoints, CLI commands, WebSocket subscriptions, docs.

**Gate:** Compiles. Endpoint tests (REST round-trip, error responses). CLI output verification. API docs updated. management-api.md and cli.md updated.

**Typical size:** 0.5–1 day.

### Layer 5: Hardening
**Scope:** Edge cases, failure recovery, concurrency under load, chaos scenarios. Soak test if applicable.

**Gate:** Full test suite green. No regressions. Docker scaling test if distributed. Audit logging covers all mutation paths.

**Typical size:** 1–2 days.

## Rules

1. **No layer starts until the previous layer is green.** Not "mostly working" — green. Build passes, tests pass, JBCT passes.

2. **Each layer is independently committable.** If we stop at Layer 3, the feature works without CLI exposure. If we stop at Layer 2, the data structure is available without system integration. Every commit is a valid stopping point.

3. **Tests are written WITH the layer, not after.** The test is the acceptance criteria. Writing tests after is debugging, not development.

4. **Layer size targets 1–2 days max.** If a layer takes longer, it's too big — decompose into sub-layers. A 3-day layer is two layers pretending to be one.

5. **Design issues discovered in Layer N are fixed in Layer N.** If Layer 3 integration reveals that the Layer 2 data structure needs a different API, fix the data structure before proceeding. Do not work around it.

6. **Each layer gets its own commit.** Cohesive, reviewable, independently revertable. Prefix convention: `feat: streaming — layer 1: types and interfaces`.

## Benefits

- **Predictable planning:** 5 layers × 1–2 days = 5–10 days with high confidence. No "2 weeks optimistic, 4 weeks realistic."
- **Early defect discovery:** Design flaws surface in Layer 2 unit tests, not Layer 5 integration.
- **Reduced fix cascades:** A Layer 2 fix doesn't invalidate Layer 4 code because Layer 4 doesn't exist yet.
- **Clean git history:** Each commit is a meaningful, tested increment.
- **Safe stopping points:** If priorities shift mid-feature, the committed layers are production-quality.

## Example: Streaming Feature

| Layer | Scope | Duration | Gate |
|-------|-------|----------|------|
| 1 | `StreamPublisher<T>`, `StreamSubscriber`, `@PartitionKey`, `StreamAccess<T>`, config records | 1 day | Types compile, factory tests pass |
| 2 | `OffHeapRingBuffer` — produce, consume, wrap, retention eviction | 2 days | Unit tests + benchmark, single-threaded and concurrent |
| 3 | `StreamPartitionManager`, KV types, governor-local produce/consume, annotation processor | 2 days | Forge integration test: produce 1000 events, consume all, verify order |
| 4 | REST endpoints (`/api/streams/*`), CLI commands (`aether stream`), docs | 1 day | REST round-trip, CLI output |
| 5 | Consumer groups, rebalancing, error handling (retry/skip/stall), dead-letter | 2 days | Multi-consumer Forge test, failure injection |

**Total: 8 days** with each day's output verified before the next begins.

## Example: RBAC Tier 2

| Layer | Scope | Duration | Gate |
|-------|-------|----------|------|
| 1 | `Role` enum (ADMIN/OPERATOR/VIEWER), `RoutePermission` record | 0.5 day | Types compile |
| 2 | `RoleEnforcer` logic — check API key role against route requirement | 1 day | Unit tests: all role × route combinations |
| 3 | Wire into `SecurityValidator` + `ManagementServer`, annotate all `*Routes.java` | 1.5 days | Integration test: viewer can GET, viewer cannot POST drain |
| 4 | TOML config (`role` field in `[[api_keys]]`), CLI key management, docs | 1 day | Config round-trip, CLI output |
| 5 | Audit log for access denied, edge cases (missing role defaults to VIEWER) | 0.5 day | Full test suite |

**Total: 4.5 days.**

## Anti-Patterns

- **"I'll add tests later"** — tests are the layer gate. No gate, no next layer.
- **"Let me just wire this in quickly"** — skipping from Layer 1 to Layer 3 means Layer 2 bugs become Layer 3 bugs with more context to untangle.
- **"The whole feature is one layer"** — if you can't decompose it, you don't understand it well enough. Decompose first, then implement.
- **"This layer is almost done, I'll fix that edge case in the next one"** — the edge case IS Layer N. Fix it before proceeding.
