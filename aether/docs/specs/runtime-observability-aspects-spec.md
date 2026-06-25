# Runtime-Switchable System Observability Aspects — Design Specification

**Version:** 0.1
**Status:** Draft
**Date:** 2026-06-24
**Author:** design-stream
**Issue:** #277 (runtime aspect switching not wired)
**Related:** #278/#279/#280 (interceptor *bugs* — separate; this spec does not touch them), #304 (trace waterfall — consumes this seam)
**Realizes:** RFC-0010 (unified invocation observability — runtime reconfiguration). **Leaves unchanged:** RFC-0008 (compile-time aspect/interceptor framework).

> ⏸️ **2026-06-25 — design in revision (implementation postponed).** Review resolved granularity to **per-injection-point** (§7.2), superseding the per-slice-instance mechanism implied by §6.1. The realized mechanism is an **`AspectFactory`** in factory param-0 that mints + auto-registers one holder per injection point via codegen — **no envelope bump** (GA format = 1000). Implementation is paused pending a deeper codegen design pass (always-generate-wrapper + the per-method weave). **Authoritative current state, decisions, and open questions:** [`aether/docs/internal/progress/277-observability-design-state-2026-06-25.md`](../internal/progress/277-observability-design-state-2026-06-25.md). PR #356 (per-slice foundation) is on hold.

---

## Table of Contents

1. [Overview & Goals](#1-overview--goals)
2. [The reframe — the fork is false](#2-the-reframe)
3. [Current State (verified)](#3-current-state-verified)
4. [Architecture — two layers](#4-architecture--two-layers)
5. [The always-on aspect seam](#5-the-always-on-aspect-seam)
6. [The push-on-event switching mechanism](#6-the-push-on-event-switching-mechanism)
7. [Facets & granularity](#7-facets--granularity)
8. [Reconciliation with the RFCs](#8-reconciliation-with-the-rfcs)
9. [Configuration Model](#9-configuration-model)
10. [Error Model](#10-error-model)
11. [Implementation Plan](#11-implementation-plan)
12. [Reconciliation to Existing Code](#12-reconciliation-to-existing-code)
13. [Open Questions](#13-open-questions)
14. [References](#14-references)

---

## 1. Overview & Goals

### 1.1 Purpose

Make **system-level observability** (logging / metrics / spans / tracing) **runtime-switchable per
injection point without a slice redeploy and without re-provisioning** — while leaving user-requested
interceptors (cache/retry/circuit-breaker/rate-limit) exactly as they are. The mechanism: every slice
and every resource is **always** wrapped in a system observability aspect; each aspect instance holds a
`volatile` immutable config snapshot; KV-update events swap the snapshot; the per-call path reads only
local instance state.

### 1.2 Scope

- **In scope:** the switching *mechanism* — the always-on aspect seam over slices and resources, the
  push-on-event snapshot swap, per-injection-point × facet granularity, the management/KV surface, and
  **rehoming the existing observability logic** (depth-aware logging, invocation metrics, trace capture)
  onto the seam so it becomes switchable and **covers resource calls** (which emit nothing today).
- **Out of scope:** user interceptors (untouched — §4.1); building spans / trace-tree assembly / the
  waterfall (those are separate facet build-outs that *consume* this seam — §7.4, #304); the interceptor
  bugs #278/#279/#280.

### 1.3 Goals

- **G-1 — Always-on seam.** Slice and every resource are unconditionally wrapped in a system
  observability aspect.
- **G-2 — No re-provision.** A runtime change never rebuilds or re-provisions a slice/resource; it swaps
  the aspect's local snapshot.
- **G-3 — No per-call lookup.** The hot path reads only the aspect instance's own `volatile` field — no
  registry/map access per call.
- **G-4 — Per-injection-point × facet.** Each of logging/metrics/spans/tracing is independently
  toggleable (+ depth) for a given slice-method or resource-call.
- **G-5 — Interceptors untouched.** User aspects stay frozen at construction (RFC-0008); this spec is
  orthogonal to them.
- **G-6 — Near-zero when off.** All-off cost = one `volatile` load + one predicted branch; no allocation,
  no shared access.

### 1.4 Non-Goals

- Re-provisioning / slice-rebuild as the switch mechanism — **explicitly rejected** by design (§6).
- Making *user interceptors* runtime-switchable — they are compile-time by RFC-0008 design.
- Full span/trace-tree assembly and the dashboard waterfall (#304) — this spec provides the seam they
  plug into, not the implementations.

### 1.5 Design principles

- **Push, don't pull.** Config changes are pushed into local instance state on KV events; readers never
  look anything up.
- **Always-on seam, swappable payload.** The seam exists everywhere unconditionally so the switch is
  "swap what the wrapper does," never "add a wrapper."
- **Two orthogonal layers.** System observability (outer, switchable) vs user interceptors (inner,
  frozen) never entangle.
- **Reuse the proven pattern.** The KV→registry→push path already exists four times
  (`LogLevelRegistry`, `ObservabilityDepthRegistry`, `AlertManager`, `DynamicConfigManager`).

---

## 2. The reframe

#277's title implies a single fork — *"build runtime aspect switching OR declare aspects deploy-time-
static."* That is a **false binary**, because two RFCs govern two different things:

- **RFC-0008 (aspect/interceptor framework)** makes user aspects **compile-time/deploy-time static by
  design** (it avoids runtime reflection/proxies). "Deploy-time-static" is its *native* reading — and per
  the design decision in this spec, **user interceptors stay exactly that.**
- **RFC-0010 (unified invocation observability — the live RFC)** **explicitly commits** runtime,
  per-injection-point reconfiguration of the *observability* facet: §"Focused Subtree Control" — an
  operator raises a method's depth, observes, resets, *"No restart, no config file change, no
  redeployment."*

So the two axes are split: **user aspects = static (RFC-0008, unchanged); system observability =
runtime-switchable (RFC-0010, realized here).** "Deploy-time-static for RC1" would contradict RFC-0010
for the observability axis; this spec realizes RFC-0010 instead.

**Already shipped (don't rebuild):** dynamic **log levels** work end-to-end today (`LogLevelRegistry` +
`LogLevelKey` + `/api/logging/levels`) — the table-stakes capability. This spec generalizes that proven
pattern to the full observability seam over slices *and* resources.

---

## 3. Current State (verified)

### 3.1 The aspect seam — EXISTS, unconditional, but dead (always identity)

- `Aspect<T>` = `@FunctionalInterface T apply(T instance)` (`aether/slice-api/.../Aspect.java:8`), with
  `compose`/`andThen` (`:15-21`). It transforms a **slice instance** (wraps the object), applied **once
  at construction** — not a per-call hook.
- `Aspect.identity()` (`:11-13`) returns `instance -> instance` (a fresh lambda each call; behaviorally
  a no-op).
- **The slice is already always wrapped:** every generated `create(...)` return path applies the aspect
  — `aspect.apply(factoryCall)` (`FactoryClassGenerator.java:429`), `aspect.apply(new XWrapper(...))`
  (`:504/:550/:656`), and the no-interceptor case `aspect.apply(instance)` (`:586`). The factory
  signature *requires* `Aspect<T>` as param 0 (`:212`; validated `SliceFactory.java:121-127`).
- **But the source is hardcoded to identity:** `SliceFactory.invokeFactory` builds
  `new Object[]{Aspect.identity(), creationContext}` (`SliceFactory.java:143`) — the *sole* runtime path
  (`createSlice`→`invokeFactory`, `:33,:59,:135`). No non-identity aspect is ever constructed in main
  code.

### 3.2 Resources — NOT aspect-wrapped (the gap)

Resources are provisioned via `ctx.resources().provide(Type, "section")`
(`FactoryClassGenerator.java:364,665,1205-1226`) and used **raw** — no `aspect.apply` anywhere on a
resource. `SliceCreationContext` exposes `invoker()` / `resources()` / `config()` / `sliceId()`
(`SliceCreationContext.java:15-22`). Consequence: **resource-call boundaries emit no observability**
(confirmed in the RFC-0010 audit).

### 3.3 User interceptors — frozen at construction (stay as-is)

`@ResourceQualifier` interceptors are collected and folded inside-out into `final` fields of an immutable
generated `XWrapper` record at slice creation (`FactoryClassGenerator.java:594-657`, provisioned via
`ctx.resources().provide(...)` `:665`). **This spec does not change this.**

### 3.4 Observability today — ad-hoc, slice-only, not via the seam

Observability runs through a **separate** path, not the aspect seam: `ObservabilityInterceptor` wired at
`InvocationHandler.java:241` (metrics) / `:242` (tracer+logging) and `SliceInvoker.java:334,565,758`
(cross-slice); depth carried in `InvocationContext` `ScopedValue<Integer> DEPTH`. Facet status (audited):
**logging** PARTIAL (good, depth+rid-correlated; runtime levels shipped), **metrics** PARTIAL
(`InvocationMetricsCollector`, in-memory `AtomicLong`, not Micrometer; resilience interceptors emit
none), **spans** MISSING (no `Span`/`spanId`/tree), **tracing** STUB (flat `InvocationTraceStore`
capture, no waterfall, no `/api/traces/config`, principal/originNode lost cross-node).

### 3.5 The proven KV→push pattern — EXISTS ×4

`LogLevelRegistry` (`AetherNode.java:1317`, `/api/logging/levels`), `ObservabilityDepthRegistry`
(`:86-97`, `ObservabilityDepthKey` `AetherKey.java:357-391`, `/api/observability/depth`),
`AlertManager`, `DynamicConfigManager` (`onConfigPut:82-88` → `provider.put`). Each: a `@MessageReceiver`
on a KV key pushes new state into a local registry — **exactly the write-side mechanism this spec needs.**

---

## 4. Architecture — two layers

### 4.1 The two layers (orthogonal)

| Layer | What | Lifetime | Switchable | This spec |
|---|---|---|---|---|
| **User aspects** | interceptors: cache / retry / circuit-breaker / rate-limit | compile/deploy-time, frozen in `XWrapper` (RFC-0008) | No | **untouched** |
| **System observability aspect** | logging / metrics / spans / tracing | runtime, per-instance snapshot | **Yes** | **defined here** |

> **Decision.** Keep user interceptors exactly as they are (frozen at construction) and treat the
> *system observability aspect* as a separate, always-on, runtime-switchable layer.
>
> **Why.** They have different owners and lifecycles: interceptors are *requested by the slice author*
> via annotations (a compile-time contract, RFC-0008), whereas observability is a *system/operator*
> concern that must change during a live incident (RFC-0010). Conflating them would either force
> redeploys for observability (RFC-0010 violation) or make user-declared behavior mutable by operators
> (surprising, and an RFC-0008 violation). Separation keeps each correct.
>
> **Rejected alternative.** *Unify interceptors into the switchable aspect* — would make user-requested
> behavior operator-mutable and require moving the frozen chain behind the runtime holder; rejected by
> design directive. *Make observability another frozen interceptor* — then it can't change without a
> redeploy, contradicting RFC-0010.

### 4.2 Composition

```
slice:     observabilityAspect( XWrapper( userInterceptors( method ) ) )    // outer = system, inner = user
resource:  observabilityAspect( resource )
```

The system aspect is the **outer** layer — it observes the entire invocation, including time spent in
user interceptors and resource calls — and the inner frozen chain is unaffected.

### 4.3 Injection points

Every point the aspect wraps is an addressable **injection point** (the key for runtime config): a
slice method (slice-entry + cross-slice), and a resource call (DB/HTTP/SMTP/etc.). RFC-0010's depth
boundaries (slice entry, cross-slice, resource call) map 1:1 onto these.

---

## 5. The always-on aspect seam

> **Decision.** (a) **Slice:** replace the hardcoded `Aspect.identity()` (`SliceFactory.java:143`) with
> the **system observability aspect** for that injection point. (b) **Resource:** make
> `ResourceProviderFacade.provide(...)` always return `aspect.apply(resource)` with the system aspect
> for that resource. The generated `aspect.apply(...)` calls are unchanged.
>
> **Why.** The slice seam already exists unconditionally (§3.1) — only its source is wrong (dead
> identity). Sourcing the real aspect there lights it up with zero generator change. Wrapping at the
> `provide()` facade is the single place every resource flows through, so it makes "resource always
> wrapped" true by construction (uniform, no per-call-site codegen) **and closes the resource-call
> observability gap** (§3.2) in one move.
>
> **Rejected alternative.** *Wrap resources at each generated call site* — needs a resource-typed aspect
> threaded into codegen at every `provide(...)`; brittle and scattered vs one facade change. *Keep
> observability at `InvocationHandler` and only add resources* — leaves two observability paths (slice
> via handler, resource via seam), defeating the uniform seam and the per-injection-point switch model;
> instead **rehome** the handler's logic onto the seam (§7.3).

**Interaction with #268.** The resource-provisioning overloads have a lifecycle bug (#268 — context
overload bypasses cache/refcount/close). The aspect wrap must sit **outside** the cached resource handle
and must not defeat refcounting or `close()` — i.e. wrap the *returned* handle, keyed to the same
lifecycle, and deregister the aspect when the resource is released. Sequence the #268 fix first or
co-design the wrap so it composes with the unified lifecycle.

---

## 6. The push-on-event switching mechanism

> **Decision.** Each system-aspect instance holds a **single `volatile` reference to an immutable config
> snapshot** (its own local state). A KV-update event (`@MessageReceiver` on the observability config
> key) finds the affected instances via a **write-side registry** and **swaps the snapshot reference**.
> The per-call path reads only that `volatile` field — **no registry/map lookup per call, and no
> re-provision.**
>
> **Why.**
> - **No re-provision** (hard constraint): swapping a field on a live instance changes behavior on the
>   next call without rebuilding the slice/resource.
> - **No per-call lookup:** a shared registry read per call (even O(1)) is a shared-structure access
>   (hash + possible cache-line contention) on the hot path. A local `volatile` load is a single ordered
>   read — strictly cheaper, and uncontended.
> - **`volatile` is required, not optional:** the KV-update event runs on a *different* thread than the
>   request-serving (virtual) threads; without a memory barrier, a plain-field update may never become
>   visible to callers. A `volatile` load on read + `volatile` store on swap give the necessary
>   happens-before.
> - **Immutable snapshot for atomicity:** the config is multiple fields (per-facet on/off + depth);
>   holding them in one immutable record swapped via one `volatile` ref means a call sees the complete
>   old or complete new snapshot, never a torn mix. (The lock-free "arc-swap / semi-static" pattern.)
>
> **Rejected alternative.** *Per-call registry/holder lookup* — a shared-map access on every invocation;
> the overhead the design directive explicitly rules out. *Re-provision/slice-rebuild on change* — the
> cheapest *off-state* (literal zero by absence) but rebuilds the object and drops in-flight context;
> rejected by directive. *Plain (non-volatile) fields* — a visibility bug: updates may not reach
> running threads.

### 6.1 Hot-path cost

```
T method(...) {                                 // generated wrapper / resource proxy
    var cfg = this.observabilityConfig;         // ONE volatile load (no lookup, no alloc)
    if (cfg.allOff()) return delegate(...);     // predicted branch → straight through
    // else: emit per active facet at cfg.depth, then delegate
}
```
**All-off cost: one volatile load + one predicted branch.** Negligible against any real slice/resource
work (a DB query, an HTTP call).

### 6.2 Lifecycle

> **Decision.** A system-aspect instance **registers** itself (by injection-point key) in the write-side
> registry when its slice/resource is provisioned, and **deregisters** when the slice/resource is
> released.
>
> **Why.** The registry must reach exactly the live instances on a KV change, and must **not pin dead
> instances** after unload (the #268 leak pattern in another guise). Tying aspect lifecycle to the
> wrapped slice/resource keeps the registry bounded and correct.
>
> **Rejected alternative.** *Global static aspect per key* — breaks per-instance state when multiple
> instances of a slice/resource exist, and never releases. *No registry (pure broadcast)* — every
> instance would filter every event; fine functionally but needlessly chatty.

### 6.3 Update flow

```
operator → PUT /api/observability/{facet}  (or depth)   [management route]
   → commit ObservabilityConfigKey in cluster KV
   → @MessageReceiver onObservabilityConfigPut  (every node)            [write-side, rare]
   → registry.lookup(injectionPointKey) → for each live aspect instance:
        instance.observabilityConfig = newImmutableSnapshot            [volatile store]
   → next invocation reads the new snapshot                            [no rebuild]
```

---

## 7. Facets & granularity

### 7.1 The config snapshot

```java
record ObservabilityConfig(boolean logging, boolean metrics, boolean spans, boolean tracing, int depth) {
    static final ObservabilityConfig OFF = new ObservabilityConfig(false, false, false, false, 0);
    boolean allOff() { return !(logging || metrics || spans || tracing); }   // hot-path fast check
}
// per aspect instance:
private volatile ObservabilityConfig observabilityConfig = ObservabilityConfig.OFF;
```

### 7.2 Granularity

> **Decision.** Switch granularity is **per-injection-point × facet**: each of logging/metrics/spans/
> tracing (+ depth) is independently toggleable for a given slice-method or resource-call.
>
> **Why.** This is the RFC-0010 model (per-method depth control) extended to the facet dimension, and it
> is what an operator actually needs during an incident ("turn on tracing for `OrderService.placeOrder`
> only"). The aspect instance is already per-injection-point, so per-facet flags ride in its snapshot at
> no extra structural cost.
>
> **Rejected alternative.** *Per-slice (coarse)* — can't isolate one hot method; floods. *Global* —
> useless as an incident microscope. *Finer than per-method* — no addressable unit below the injection
> point.

### 7.3 Rehoming existing observability

The working observability logic — depth-aware logging (`ObservabilityInterceptor.logAtDepth`),
`InvocationMetricsCollector`, trace capture (`InvocationTraceStore`) — is **reused as the body of the
system aspect**, relocated from `InvocationHandler`/`SliceInvoker` onto the always-on seam, and extended
to resource calls. The existing `ObservabilityDepthRegistry`/`ObservabilityDepthKey` +
`/api/observability/depth` become the depth dimension of the new config; `LogLevelRegistry` continues to
own the slf4j threshold (orthogonal, keep). No observability logic is thrown away — it is rehomed and
made switchable.

### 7.4 Facet build-out status (what this spec realizes vs leaves)

| Facet | Today | This spec |
|---|---|---|
| Logging | works (slice only) | rehome to seam + **resources** + per-IP toggle |
| Metrics | partial (in-memory) | rehome + **resources** + per-IP toggle; (Micrometer wiring + resilience-interceptor metrics = separate, ties #280) |
| Spans | **missing** | provide the **seam**; span creation/tree itself is a separate build-out (#304 / RFC-0010) |
| Tracing | stub (flat) | per-IP capture toggle on the seam; tree-assembly/waterfall + `/api/traces/config` separate (#304) |

---

## 8. Reconciliation with the RFCs

- **RFC-0008 (aspect framework)** — **unchanged.** User interceptors remain compile-time, frozen (§4.1).
  This spec adds a *system* aspect that is explicitly outside RFC-0008's user-aspect model.
- **RFC-0010 (unified invocation observability)** — **realized.** This builds the "no restart, no
  redeployment" per-injection-point reconfiguration RFC-0010 commits, generalized across slices *and*
  resources, and unifies the currently-ad-hoc observability path onto the aspect seam.
- **RFC-0009 (request tracing)** — superseded by RFC-0010; the `TraceCollector`/sampling notions live
  under the tracing facet (build-out separate, §7.4).
- **RFC-0010's central premise** ("one model → three views") is *not* realized today (metrics and tracing
  are disjoint sinks). This spec doesn't force that unification, but the single seam is the natural place
  to converge them later.

---

## 9. Configuration Model

```toml
[observability]
# deploy-time defaults; runtime overrides arrive via KV + management route
default = "off"                 # off | logging | metrics | spans | tracing | all
default_depth = 1
```

- **KV key:** `ObservabilityConfigKey` (per injection point), mirroring `ObservabilityDepthKey`
  (`AetherKey.java:357-391`).
- **Management routes (REST→CLI→Docs triad, CLAUDE.md invariant #1):** extend
  `/api/observability/...` — `GET` current config per injection point, `PUT` per-facet/depth, `DELETE`
  reset. CLI `aether observability set <injection-point> --tracing on --depth 3`; docs in
  `reference/management-api.md` + `cli.md`.
- Reuses `ObservabilityRoutes` (`:148-170`) + the `ObservabilityDepthRegistry` storage pattern.

---

## 10. Error Model

| Surface | `Cause` variants |
|---|---|
| Config parse / route | `UnknownInjectionPoint(key)`, `UnknownFacet(name)`, `InvalidDepth(n)` |
| Registry | `AspectNotRegistered(key)` (update for a key with no live instance — log + no-op, not an error) |

Invalid runtime config **fails the management call**, never the running aspect (an aspect with a bad
push keeps its last-good snapshot — fail-safe, observability must never break the business path).

---

## 11. Implementation Plan

| Phase | Scope | Anchors |
|---|---|---|
| 0 — aspect type | define the system `ObservabilityAspect` + `ObservabilityConfig` snapshot + `volatile` field + `allOff()` fast path | `Aspect.java` |
| 1 — slice source | replace hardcoded `Aspect.identity()` with the per-injection-point system aspect | `SliceFactory.java:143` |
| 2 — resource wrap | `ResourceProviderFacade.provide(...)` returns `aspect.apply(resource)`; compose with #268 lifecycle | `ResourceProviderFacade`, `SpiResourceProvider` |
| 3 — write-side registry | `ObservabilityConfigKey` + `@MessageReceiver` + registry; register/deregister on slice/resource lifecycle | mirror `LogLevelRegistry` (`AetherNode.java:1317`) |
| 4 — rehome logic | move `ObservabilityInterceptor` logging/metrics/trace-capture into the aspect body; extend to resources | `InvocationHandler.java:241-242`, `SliceInvoker.java:334,565,758` |
| 5 — management surface | `/api/observability/{facet}` triad (REST+CLI+docs) | `ObservabilityRoutes.java:148-170` |
| 6 — verify | toggle logging/metrics/tracing on one slice-method and one resource-call at runtime → effect without redeploy; all-off micro-benchmark = baseline; lifecycle (no registry leak on unload) | `aether/tests/integration` |

**Acceptance (from #277):** changing metrics/logging/tracing on a method (and now a resource) takes
effect without redeploy; zero-cost-when-all-off preserved (one volatile load + branch); user
interceptors unchanged.

---

## 12. Reconciliation to Existing Code

| Capability | Current | Target | Tag | Anchor |
|---|---|---|---|---|
| Slice aspect seam | unconditional but sourced as dead `identity()` | source the system aspect per injection point | **WRONG-source** | `SliceFactory.java:143`; `FactoryClassGenerator.java:429,504,550,586,656` |
| Resource aspect wrap | none (raw handles) | wrap every `provide()` | **MISSING** | `FactoryClassGenerator.java:364,665,1205-1226`; `ResourceProviderFacade` |
| User interceptors | frozen `final` chain in `XWrapper` | **unchanged** | **KEEP** | `FactoryClassGenerator.java:594-657` |
| Observability path | ad-hoc at `InvocationHandler`/`SliceInvoker`, slice-only | rehome onto the seam + resources | **REHOME** | `InvocationHandler.java:241-242`; `SliceInvoker.java:334,565,758` |
| Runtime switch mechanism | none for aspects (only slf4j level) | per-instance `volatile` snapshot + push-on-event | **MISSING** | — |
| KV→push registry | proven ×4 | add `ObservabilityConfigKey` + registry | **DONE (reuse)** | `LogLevelRegistry` (`AetherNode.java:1317`); `ObservabilityDepthRegistry:86-97` |
| Depth control | exists (tunes log verbosity only) | becomes the depth dimension of the snapshot | **EXTEND** | `ObservabilityDepthKey` (`AetherKey.java:357-391`); `ObservabilityRoutes.java:148-170` |
| Resource-call observability | emits nothing | covered by the resource aspect wrap | **MISSING→FIX** | §3.2 |

---

## 13. Open Questions

1. **#268 ordering.** Should the resource-aspect wrap land *after* the #268 provide()/lifecycle fix, or
   be co-designed? (Recommend after — the wrap must compose with the unified cache/refcount/close path.)
2. **Cross-slice double-observation.** A cross-slice call passes through the caller's aspect *and* the
   callee's aspect; confirm the depth model (`InvocationContext.DEPTH`) dedups/links so one logical call
   isn't double-counted.
3. **Resource aspect typing.** `Aspect<T>` is typed; resources are heterogeneous. Confirm the wrap uses a
   single `Aspect<Object>`/raw observability decorator at the facade vs a per-type aspect.
4. **Spans build-out owner.** This spec provides the seam; which ticket owns actual span creation/tree
   assembly (RFC-0010 deferred items / #304)?
5. **Default state.** Ship default = `off` (zero overhead, opt-in per incident) vs `logging` at depth 1
   (some baseline)? Recommend `off` for cost; operators opt in.

---

## 14. References

- **Dynamic log levels (table-stakes):** Spring Boot Actuator `/loggers` — https://docs.spring.io/spring-boot/reference/actuator/loggers.html
- **Runtime tracing/sampling:** Jaeger remote sampling — https://www.jaegertracing.io/docs/latest/sampling/ · OTel sampling — https://opentelemetry.io/docs/concepts/sampling/
- **Hot-swappable filter chains:** Envoy xDS dynamic configuration — https://www.envoyproxy.io/docs/envoy/latest/intro/arch_overview/operations/dynamic_configuration
- **Zero-cost-when-off:** JDK Flight Recorder — https://docs.oracle.com/javacomponents/jmc-5-4/jfr-runtime-guide/about.htm · eBPF (Pixie) — https://docs.px.dev/about-pixie/pixie-ebpf/
- **Watch→atomic-swap (push-on-event):** etcd watch + `ArcSwap`-style snapshot — https://etcd.io/docs/latest/learning/api/ · semi-static / branch-cost analysis — https://arxiv.org/pdf/2308.14185
- **Internal:** RFC-0008 (aspect framework), RFC-0010 (unified invocation observability), RFC-0009 (request tracing, superseded).

---

*Companion to issue #277. Realizes RFC-0010's runtime observability reconfiguration; leaves RFC-0008
user aspects unchanged. Orthogonal to interceptor bugs #278/#279/#280.*
