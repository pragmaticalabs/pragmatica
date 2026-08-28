<!--
SPDX-License-Identifier: BUSL-1.1
Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
See LICENSE in the repository root for full terms.
-->

# Slice Test Kit v2 — Slice-to-Slice Invocation — Design Specification

**Issue:** [#607](https://github.com/pragmaticalabs/pragmatica/issues/607) — "slice-testkit cannot test the core programming model"
**Milestone:** v1.0.0-rc3 · **Labels:** `aether-gap`, `book-blocking`, `enhancement`, `stream-toolchain` · **Status:** DRAFT — for approval, not implementation
**Related:** #399 spec (`aether/docs/specs/issue-399-slice-test-kit-spec.md`, assumption [A6] and §7.2 defer this exact work; §3.1 sketched `withSliceDependency` but the MVP dropped it), #605, #606 (cluster note: shared root cause)

> Design proposal; changes no code. Owner decisions are marked **OPEN QUESTION**; other choices are `[ASSUMPTION]`.

## 1. Problem & Goal

`SliceTestKit.build()` wires every slice with `NoOpSliceInvoker.INSTANCE` (`aether/slice-testkit/src/main/java/org/pragmatica/aether/testkit/SliceTestKit.java:97`), whose only method returns `TestKitError.UnscriptedInteraction` (`NoOpSliceInvoker.java:16-26`). The failure is worse than "every call fails": generated factories acquire a `MethodHandle` for **every method of every slice dependency eagerly**, via `Promise.all(ctx.invoker().methodHandle(...).async(), ...)` before the impl is constructed (`examples/banking/transfer/target/generated-sources/.../TransferServiceFactory.java:123-157`, emitted by `FactoryClassGenerator.java:935-948`). A slice with one slice dependency therefore fails at `build()` — the kit cannot even construct it. Consequence: the banking tests hand-wire stubs through the plain typed factory (`TransferServiceTest.java:40-47` calls `TransferService.transferService(accounts, exchange, fraud)`, `TransferService.java:88-92`), bypassing the generated factory entirely — the wiring production actually runs is never tested (issue #606's pattern).

**Goals.** G1 — a test scripts a dependency slice per method and asserts on recorded calls (unit path). G2 — a test wires a small graph of real slices in-process and drives one through the generated factory (integration path). G3 — modes mix per dependency (stub fraud, real account). G4 — at least two examples adopt the kit; `testing-slices.md` leads with the no-cluster path.

## 2. Background — the real seam

- `SliceInvokerFacade` is one method: `<R,T> Result<MethodHandle<R,T>> methodHandle(String sliceArtifact, String methodName, TypeToken<T> requestType, TypeToken<R> responseType)` (`aether/slice-api/.../SliceInvokerFacade.java:11-16`). `MethodHandle` is `invoke(T)`, `fireAndForget(T)`, `artifactCoordinate()`, `methodName()`, default `materialize()` (`MethodHandle.java:12-20`).
- The generated factory builds one **proxy record per slice dependency** holding a handle per method; multi-param methods get **factory-method-local** request records with `arg0..argN` components (`TransferServiceFactory.java:47-121`). Tests cannot name these types.
- Production `SliceInvoker.methodHandle()` **always succeeds** — it only parses the artifact and method names; endpoint resolution happens per-invoke (`aether/aether-invoke/.../SliceInvoker.java:51-60`). Local dispatch goes through the target bridge's codec (`SliceInvokerImpl` `invokeViaBridge`, `SliceInvoker.java:776-785`).
- The runtime `…Slice()` entry returns a record implementing **both** `Slice` and the typed interface, whose `methods()` is a typed dispatch table: `SliceMethod(name, Fn1<Promise<R>,T> handler, returnType, parameterType)` with the handler already unpacking the request record (`TransferServiceFactory.java:167-190`, `SliceMethod.java:15-18`).
- Artifact strings may carry an `UNRESOLVED` version (`FactoryClassGenerator.java:936`; live in `TransferServiceFactory.java:124`).

## 3. Design — one invoker, two registration sources

New `TestSliceInvoker implements SliceInvokerFacade` replaces `NoOpSliceInvoker` in `SliceTestKit.build()`. Its `methodHandle()` **always returns success** with a lazy handle carrying `(artifactBase, methodName, requestType, responseType)`; target resolution and all failures move to `invoke()` time. This is forced by §2's eager acquisition (fail-fast at `methodHandle()` would require scripting every method of every dependency) and mirrors production laziness. Registry key: `groupId:artifactId` — version (including `UNRESOLVED`) stripped. `[ASSUMPTION]` Version-sensitive matching is a non-problem in-process; flag if multi-version graphs are ever wanted.

### 3.1 Scripted mode (unit path)

```java
SliceTestKit.forSlice(TransferServiceFactory::transferService)
    .withSliceStub("org.pragmatica.aether.example.banking:account", AccountService.class, stubAccounts)
    .withSliceStub("...:exchange", ExchangeRateService.class, ExchangeRateService.exchangeRateService())
    .withSliceStub("...:fraud", FraudDetectionService.class, approveAll)
    .build()
```

The stub is any implementation of the dependency's **own interface** (hand-rolled records in house style — the existing `StubAccountService` slots in unchanged, no mock framework, per #399 §3.3). The kit wraps it in a recording adapter and dispatches `(methodName, request)` to the interface method: match by name (`MethodName` guarantees simple names), unpack the synthesized `arg0..argN` record positionally via `RecordComponent`s; single-param passes through; `Unit` maps to no-arg. Response instances are checked assignable to the acquired `responseType` raw type — a stub returning the wrong shape fails loudly, not at a downstream cast.

**Unscripted behavior stays fail-fast** but moves and gets named: invoking a method of an artifact with no registration resolves the `Promise` with new `TestKitError.MissingSliceDependency(artifact, method, registeredArtifacts)` — message names the missing coordinate and points at `withSliceStub(...)`/`withSlice(...)`, mirroring `MissingResource` (`TestKitError.java:20-27`). `UnscriptedInteraction` (`TestKitError.java:41-46`) remains for partially-scripted stubs (§8 Q1).

**Verification:** `SliceUnderTest` (`SliceUnderTest.java:19-34`) gains `List<SliceCall> sliceCalls(String artifact)` — `SliceCall(artifact, method, request)` recorded by the invoker for stub **and** real targets, resolved from the registry the same way capture handles resolve from the resource map (`DefaultSliceUnderTest.java:30-32`).

### 3.2 Real-wiring mode (integration path)

```java
.withSlice("org.pragmatica.aether.example.banking:account", AccountServiceFactory::accountServiceSlice,
           scope -> scope.withResource(PgSqlConnector.class, "database", store)
                         .withResource(CacheMethodInterceptor.class, "cache.account.getBalance", passthrough()))
```

Each registered real slice gets its **own** `MapResourceProvider` scope (`MapResourceProvider.java:23-42` — coordinates collide across slices otherwise; account needs `database` and three cache sections, generated `AccountServiceFactory.java:82-89`) and the **shared** `TestSliceInvoker`, so dependency slices may themselves depend on stubs or other real slices, transitively. Lazy handles make build order irrelevant; construction-time cycles need no `DeferredSliceInvokerFacade` gymnastics (`DeferredSliceInvokerFacade.java:19-48` would still fail eager acquisition). Dispatch targets the generated `Slice.methods()` table: look up `SliceMethod` by name, re-pack the caller's synthesized record into the target's `parameterType` record positionally (caller and callee records are different classes by design — §2), call the typed handler. No codec, no reflection beyond record re-packing.

**Reuse weighed, kit-local recommended.** (a) Runtime `SliceInvoker` — rejected: construction needs `NodeId, ClusterNetwork, EndpointRegistry, InvocationHandler, Serializer, Deserializer, DeploymentManager` (`SliceInvoker.java:159-175`), adds `aether-invoke` to a pom that deliberately stops at `slice-api`/`resource-api` (`slice-testkit/pom.xml:29-41`), and its local path still requires bridges + codecs (`SliceInvoker.java:776-785`) — the stack #399 §5 marks "explicitly NOT reused". (b) `DependencyResolver` — rejected: resolves packaged JARs via `repository.locate` (`DependencyResolver.java:194`), wrong shape for test-classpath classes. (c) Kit-local over `Slice.methods()` — recommended: ~2 small classes, the same dispatch table production bridges use, zero production change. Trade-off stated honestly: no codec round-trip — same fidelity boundary as the existing typed-client path (#399 §8 Q6).

## 4. Adoption path

1. **`examples/banking/transfer` first** (the slice-to-slice showcase, 3 deps): scripted-mode test replacing the hand-wired `setup()` (`TransferServiceTest.java:40-47`) —

```java
try (var sut = SliceTestKit.forSlice(TransferServiceFactory::transferService)
        .withSliceStub("org.pragmatica.aether.example.banking:account", AccountService.class, accounts)
        .withSliceStub("org.pragmatica.aether.example.banking:exchange", ExchangeRateService.class,
                       ExchangeRateService.exchangeRateService())
        .withSliceStub("org.pragmatica.aether.example.banking:fraud", FraudDetectionService.class,
                       FraudDetectionService.fraudDetectionService())
        .build()) {
    var receipt = sut.client().transfer(source, destination, usd("100.00")).await(timeSpan(5).seconds()).unwrap();
    assertThat(receipt.status()).isEqualTo(TransferStatus.COMPLETED);
    assertThat(sut.sliceCalls("org.pragmatica.aether.example.banking:account"))
        .anyMatch(c -> c.method().equals("debit"));
}
```

plus one real-wiring test (`withSlice(...:account, ...)` with faked resources) whose **mutation check** breaks `AccountService.debit` and asserts the transfer test goes red.
2. **`examples/url-shortener`** converts its resource-only tests to the kit (no slice deps — proves mode-free adoption; satisfies "two examples").
3. **`aether/docs/slice-developers/testing-slices.md`** (today E2E-cluster-only from line 1) restructures: §1 kit unit path, §2 slice-graph path, §3 the existing cluster/Docker content demoted.

## 5. Non-goals

Cluster semantics: routing, failover/retry (`invokeWithRetry`), affinity, weighted deployment routing. Consensus, membership, transport, deploy FSM. Codec/serialization round-trip (deferred toggle, §8 Q4). The dead post-GA #604 transaction-aspect wiring — explicitly not coupled. Multi-version graphs.

## 6. Compatibility

`SliceInvokerFacade`, `MethodHandle`, `SliceCreationContext`, generated code: **untouched**. All additions live in `aether/slice-testkit` (BSL-1.1, test scope, dependency direction per #399 §6.2). No production change is required. **Flagged for owner (would touch production, not needed now):** the `UNRESOLVED` version fallback (`FactoryClassGenerator.java:936`) is worked around by base-coordinate keying; fixing resolution in the processor is a separate decision (envelope-version bump rules apply) — note this is the same manifest-coordinate weakness implicated in #704's dropped dependency edges.

## 7. Acceptance criteria

1. A slice whose dependency is another **real** slice is driven in-process through its **generated** factory — no cluster, no Docker (issue acceptance 1).
2. Scripted mode: unstubbed artifact/method fails at invoke with an error naming `artifact.method` and the fix; recorded calls assertable via `sliceCalls(artifact)`.
3. Mutation check: breaking the invoked slice turns the caller's test red (issue acceptance 3).
4. `banking/transfer` and `url-shortener` ship kit-based tests (issue acceptance 2); kit fixture gains a two-slice test.
5. Mixed graph (one stub + one real dependency) passes.
6. `testing-slices.md` leads with the kit; cluster path demoted.
7. Existing kit tests (`OrderIntakeSliceTest`, `SliceTestKitDiagnosticsTest`) stay green.

## 8. Open questions

1. **Partial stubs** — full-interface impls only (compiler-checked, verbose for wide interfaces) vs a `Proxy`-backed partial stub where unstubbed methods fail as `UnscriptedInteraction`? Rec: ship full-impl first, add partial helper if transfer adoption shows pain.
2. **Builder shape** — grow `SliceTestKit` (rec: one entry point, modes mix) vs a separate `SliceGraph` for real-wiring.
3. **Transfer's hand-wired tests** — replace or keep alongside as the "plain factory" teaching example?
4. **`throughCodec` fidelity toggle** — stays deferred (rec) or in scope now?

## 9. Assumptions

**[A1]** Artifact matching is version-insensitive. **[A2]** Positional record re-pack is the bridging rule; non-record mismatches fail with a named error. **[A3]** Kit-side reflection (record components, canonical constructor) is acceptable at test scope. **[A4]** `fireAndForget` dispatches like `invoke` with the result mapped to `Unit`; `materialize()` performs the registry check for early validation.
