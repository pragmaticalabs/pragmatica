# #277 Runtime Observability Aspects — Design State & Resume Point

**Date:** 2026-06-25
**Status:** ⏸️ **POSTPONED — deeper codegen design required before implementation continues**
**PR:** [#356](https://github.com/pragmaticalabs/pragmatica/pull/356) — open, REQUEST CHANGES, **do not merge** (per-slice foundation; superseded by the per-injection-point direction below)
**Spec:** `aether/docs/specs/runtime-observability-aspects-spec.md`
**Branch:** `feat/277-observability-aspects` (3 commits — item 1/2/3 of the per-slice PR1; reusable parts noted below)

---

## Where we stopped

PR1 shipped a **per-slice** foundation (config snapshot, aspect type, KV key/value + registry, node→slice threading, all green, 16 tests). Review (aether-main + repo owner) **rejected per-slice in favour of per-injection-point** (spec §7.2). We then converged on a mechanism (**`AspectFactory`**) and verified memory cost is negligible — but the **codegen change it implies needs a deeper design pass** (it touches the generated factory for *every* slice). Postponed there.

## Decisions locked (do not relitigate)

1. **Granularity = per-injection-point × facet** (spec §7.2), NOT per-slice. Confirmed by repo owner. The point is the incident microscope ("turn on tracing for `OrderService.placeOrder` only") and independent switchability of every method / cross-slice / resource boundary.
2. **No envelope bump. GA envelope format = 1000.** The codegen change rides under GA format 1000; no incremental bump (overrides project-invariant #3 for GA). Implication: runtime + slices must rebuild together (fine in the monorepo reactor; externally pre-compiled old slices won't load on a new runtime — accepted under "GA is 1000, rebuilt clean").
3. **Memory is a non-issue → one-holder-per-injection-point is fine.** Survey of 32 real slices: median 2 business methods/slice, cross-slice deps 0 for 29/32, resources 0–1. Per-node holders: typical ~80 (~10 KB), high ~450 (~67 KB), even 10× → sub-2 MB. Holders created once at slice *load* (no GC churn); configs are almost all the shared `OFF` singleton. **No per-slice-array optimization needed.**
4. **Mechanism = `AspectFactory`** (repo-owner's proposal). The generated factory's **param-0 changes from `Aspect<T>` (dead — always `identity()`) to an `AspectFactory`**. Generated code calls `factory.create(key)` per injection point → mints **+ auto-registers** one holder each (registration becomes a codegen side-effect; no manual register list). **Key = `artifactBase + "/" + name`**, shared with `ObservabilityDepthKey` (name = method name for slice-entry; dependency/param name for cross-slice/resource).

## Open design questions — the "deeper design" needed to resume

1. **`AspectFactory` interface + its product + the weave.** `Aspect<T>.apply(T)` is whole-instance and cannot express per-method. So `create(key)` returns *what* (likely the `ObservabilityAspect` volatile-holder), and **how is it woven per-method**? The per-method seam is the `XWrapper`'s per-method `Fn1` components (where `@Interceptor` chains already weave). Define: the `AspectFactory` type, what `create` returns, and the per-`Fn` weave shape.
2. **Always-generate-the-wrapper.** Today `XWrapper` (per-method `Fn`s) is generated **only when interceptors are present**; plain slices wrap the raw instance (`FactoryClassGenerator` :429/:586, no per-method seam). Per-method observability requires the per-method `Fn` structure on **every** slice → always-generate-wrapper. **This changes every slice's generated factory** — design + blast-radius review needed.
3. **The per-call read (PR2 rehome).** How the woven holder is read on the hot path (one volatile load + predicted branch, all-off = nop), and how existing observability (`ObservabilityInterceptor` logging/metrics/trace at `InvocationHandler` :241-242 + `SliceInvoker` :334/:565/:758) rehomes onto the seam. Watch cross-slice double-count (`InvocationContext.DEPTH`).
4. **Cross-slice + resource injection points.** Cross-slice calls = proxy records (`generateLocalProxyRecord` :216); resources = `ResourceProviderFacade` — **neither is aspect-wrapped in the factory today.** Their per-injection-point aspects need separate codegen. **Resource wrap is #268-blocked.**
5. **Key `name` for non-method points** — finalize the string for cross-slice (callee?) and resource (resource type/section?) so it stays unique and aligns with depth keys.

## Key codebase findings (verified — saves re-investigation)

- The five `aspect.apply(...)` sites in `FactoryClassGenerator` (:429,:504,:550,:586,:656) are **all the same whole-instance slice-entry wrap** across the factory's branch matrix — NOT per-injection-point.
- Factory method param-0 `Aspect<T>` declared at `FactoryClassGenerator` :212/:881; validated `Aspect.class` at `SliceFactory.verifyParameters` :121 (→ becomes `AspectFactory.class`). Sourced at `SliceFactory.invokeFactory` :143.
- `@MessageReceiver` is `@Retention(SOURCE)` / doc-only — KV dispatch is the **`KVNotificationRouter.builder(...)` chain** (`AetherNode` ~:3963), not annotation scanning. Wire the new registry there like `depthRegistry`.
- `Fn1<R,T1>` is **return-first** (`R apply(T1)`).
- Envelope: `ENVELOPE_FORMAT_VERSION=1005` @ `ManifestGenerator.java:35`; `SUPPORTED_ENVELOPE_VERSIONS` @ aether `SliceManifest.java:29`. **Per decision #2: do NOT bump.**
- Generated-output tests = substring asserts in `jbct/slice-processor/.../SliceProcessorTest.java` (no golden files); the inline `Aspect` stub there (:41-46,:284-286) must gain `AspectFactory`.
- Slice-loading threading chain (already wired in PR1): `WorkerDeploymentManager:150 → SliceStore.loadFromLocation:183 → DependencyResolver.resolveWithContext:88/95 → SliceLoadingContext → SliceFactory:143`. Node-side build point `AetherNode:862` (SliceStore.sliceStore). artifactBase = `Artifact.base().asString()`.

## What carries from PR1 (#356) vs what changes

**Carries (reuse):** `AspectObservabilityConfig` (snapshot record); `ObservabilityAspect` (volatile + `swap`); `ObservabilityConfigValue` + `KVStoreSerializer` wiring; `ObservabilityConfigRegistry` skeleton + `KVNotificationRouter` wiring + register/deregister lifecycle + the **load/put race fix**; the node→slice threading (re-typed Aspect→AspectFactory); the `SliceFactory:143` seam.

**Changes:** `ObservabilityConfigKey` → `(artifactBase, methodName)` (per-injection-point, mirror `ObservabilityDepthKey`); registry keyed by injection-point; **new** `AspectFactory` type + `FactoryClassGenerator` codegen (always-generate-wrapper + per-site `create(key)`); param-0 type Aspect→AspectFactory. **Drop:** per-slice granularity + the artifactBase-only key.

## Resume checklist

1. Deeper-design the codegen: `AspectFactory` interface, what `create` returns, the per-`Fn` weave, and the always-generate-wrapper change (write it into the spec as a new mechanism section, superseding the §6.1 instance-level pseudocode).
2. Re-key `ObservabilityConfigKey`/registry to per-injection-point.
3. Implement the codegen + param-0 swap on `feat/277-observability-aspects` (passthrough first).
4. Full reactor green (no bump); update `SliceProcessorTest` assertions.
5. Re-request review on #356.
6. PR2 = per-call read/rehome + cross-slice + resource (resource #268-blocked). PR3 = management triad. STEP-0 bench → PR2.
