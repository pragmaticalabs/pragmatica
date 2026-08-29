# Envelope Format Versioning

## What is the Envelope?

The **envelope** is the generated code structure that the slice-processor produces for each `@Slice` interface:

- **Factory class** (`{SliceName}Factory`) — the entry point for slice instantiation
- **Adapter record** — implements the slice interface, delegates to user code
- **Proxy records** — one per dependency, provides type-safe inter-slice communication
- **Manifest properties** — metadata written to `META-INF/slice/{SliceName}.manifest`

The envelope defines the contract between build-time code generation and runtime slice loading.

## What is `envelope.version`?

A simple integer that identifies the **format version** of the generated envelope code.

- Written to the Properties manifest during annotation processing
- Written to `Envelope-Version` in JAR MANIFEST.MF during packaging
- Checked by the runtime before loading a slice

**Key property:** The envelope version is decoupled from the project release version. It only changes when the generated code structure changes, not on every release.

## Current Version

`ENVELOPE_FORMAT_VERSION = 1000` (defined in `ManifestGenerator.java`)

> **FROZEN AT 1000 UNTIL GA (owner ruling 2026-07-18, recorded on #386).** Pre-GA envelope
> evolution rides **without** version bumps: the rc series is rebuild-together, and the stamp
> is a membership-checked compatibility gate (`SliceManifest.SUPPORTED_ENVELOPE_VERSIONS`),
> not a structural dispatch — no consumer branches on the value. The generator emitted
> 1001–1007 historically (structural changes recorded below for the "why", each verified at
> its time); those stamps remain in the runtime accept-set so rc2-built artifacts keep
> loading, and the emit constant is reset to 1000. **Do not bump for generated-structure
> changes until GA** — keep recording structural changes in the history table below with a
> "(no bump — GA freeze)" marker. Version bumps resume at GA under the GA versioning policy
> (#434-adjacent).

### Version History

| Version | Change |
|---------|--------|
| 1 | Initial envelope format |
| 2 | Added topic subscription manifest entries (`topic.subscription.*`) for pub-sub messaging |
| 3 | Added scheduled task manifest entries (`scheduled.task.*`) for periodic invocation |
| 4 | Added `serializableClasses()` to generated adapter record for class-ID-based serialization |
| 5 | Added publisher message classes (`publish.message.classes`) to manifest |
| 6 | Added topology data: resources (`resource.*`), HTTP routes (`route.*`), publisher topics (`publish.topic.*`) |
| 7 | Added streaming infrastructure: stream publishers, stream subscriptions, stream access, stream event codecs |
| 1000 | (re-numbered series baseline) |
| 1001 | #339: per-route `produces`/`consumes` media types — generated `RouteSource` emits the declared output `.as(...)` content type and the consumes-appropriate body binding (`.withStringBody()` / `.withByteBody()` / `.withMultipartBody()`) instead of always `.asJson()` / `.withBody(TypeToken)`. Runtime accepts `{1000, 1001}`. |
| 1002 | #198: API path-mode versioning — `[vN.routes]` blocks plus an `[api] prefix` mount each version at `{api.prefix}/v{N}/{path}` bound to method `getV{N}`, so the generated `RouteSource` emits additional `/vN/` route entries and `getV{N}` handlers. The manifest also gains version metadata (`versions.count`, `api.prefix`, `api.requireVersionHeader`, per-version `deprecated`/`sunset`/`defaultIfMissing`). Unversioned slices (flat `[routes]` + `prefix`) are byte-for-byte unchanged. Runtime accepts `{1000, 1001, 1002}`. |
| 1003 | #198 §6.4: versioned-route representation refactor — the `/v{N}/` segment is no longer baked into route paths at codegen. Generated versioned routes carry `version=N` metadata (un-versioned path) and the generated `routes()` composes the mounted path `{apiPrefix}/v{N}/{path}` at registration time, defaulting to path mode, so the same compiled slice can later be exposed in header mode. The generated `{Slice}Routes` gains a `versionRegistry()` override (`apiPrefix`, declared version set, `defaultIfMissing`, `requireVersionHeader`, per-version `deprecated`/`sunset`), and the manifest gains per-route `route.N.version`. Path-mode wire behavior is byte-for-byte identical to 1002; unversioned slices are unchanged. Runtime accepts `{1000, 1001, 1002, 1003}`. |
| 1004 | #198 §7: deploy-either-way header mode — the path-mode mount is no longer baked into the generated `routes()`. `routes()` now returns UN-mounted routes (bare path + `.versioned(N)` metadata, no `.map(mountInPathMode)`), and the generated `{Slice}Routes` gains a `create(slice, jsonMapper, RouteMountMode)` factory method. The registration consumer composes path mode (`{apiPrefix}/v{N}/{path}`, default) or header mode (bare `{apiPrefix}/{path}` + version selected from a request header) at deploy time, so the SAME compiled slice serves either mode. Path-mode wire behavior is byte-for-byte identical to 1003; unversioned slices are unchanged. Runtime accepts `{1000, 1001, 1002, 1003, 1004}`. |
| 1005 | Static-path-segment-after-param codegen fix — a route path with a static segment following a path parameter (e.g. `GET /items/{id}/image`, `GET /orders/{orderId}/items/{itemId}`) previously truncated at the first `{`, dropping every later segment (the route silently collapsed onto its prefix sibling). The generator now emits the full interleaved path: static segments after the prefix are emitted as `PathParameter.spacer("seg")` in the `.withPath(...)` chain (in path order), and the handler lambda binds spacer slots to `_`. Routes without a static-after-param are byte-for-byte identical to 1004. Runtime accepts `{1000, 1001, 1002, 1003, 1004, 1005}`. |
| 1006 | #396: first-class typed topics with a single-source `Topic<T>` constant — a publisher whose `@ResourceQualifier(config = "CONSTANT")` resolves to a `static final Topic<T>` constant is wrapped at the provide site in a `TypedPublisher` bound to that constant, and the publisher/subscription manifest blocks gain a `topicName` derived from the constant's `Topic.of("...", ...)` initializer (`publish.topic.{i}.topicName`, `reactive.{i}.topicName`), with the topic `config` written as the resolved topic name. Runtime resolves the topic address off the generated `topicName` (subscription side reads it directly; a typed publisher with no `resources.toml` section defaults to a topic named after its provisioned section). Slices using the legacy lowercase `config` section form are byte-for-byte identical to 1005. Runtime accepts `{1000, 1001, 1002, 1003, 1004, 1005, 1006}`. |
| 1007 | #397: value-object HTTP path/query binding via the unified `ValueMapping` descriptor — a value object declares one `static ValueMapping<Self, P> valueMapping()` (the boundary-neutral generalization of `PgRepr`, `T -> P` total `lower` + `P -> Result<T>` fallible `lift`), and a path/query parameter whose request-record component is such a value object now binds through it. The generator composes the framework-owned `String -> P` parser with the value object's `lift` — `PathParameter.a{P}().mapped(Vo.valueMapping().lift())` / `QueryParameter.a{P}(name).mapped(Vo.valueMapping().lift())` — so the segment is parsed to its primitive then lifted into the value object, and either failure yields a typed 400 (never a 500, never a silent raw string). Raw path/query parameters (JDK types) are byte-for-byte identical to 1006. A value object whose `P` is outside the supported HTTP primitive set (`String`, `Integer`, `Long`, `Boolean`, `Double`, `BigDecimal`, `LocalDate`, `LocalDateTime`, `UUID`) is a compile error. Runtime accepts `{1000, 1001, 1002, 1003, 1004, 1005, 1006, 1007}`. |
| 1000 (no bump — GA freeze) | #507: `@PartitionKey` wired into stream provisioning — a `StreamPublisher<T>` / `StreamAccess<T>` resource whose event type `T` declares exactly one `@PartitionKey` record component now has `.withKeyExtractor((Fn1<K, T>) T::component)` appended to its generated `ProvisioningContext`, so `StreamPublisherFactory` / `StreamAccessFactory` install the extractor and the runtime routes by a stable 64-bit hash of the key (`ReplicaPlacement.stableHash64`) instead of round-robin. The annotation had never been read by the processor, so every multi-partition stream silently lost per-key ordering while its own annotation advertised the opposite. Two or more `@PartitionKey` components on one record is now a compile error naming the type and the offending components. Event types with no `@PartitionKey`, and topic `Publisher<T>` resources (unpartitioned — `PublisherFactory` ignores the extractor), are byte-for-byte identical to the prior output. Emitted under the GA freeze, so the stamp stays 1000 and the runtime accept-set is unchanged. |
| 1000 (no bump — GA freeze) | #663: dependency-method scan walks super-interfaces — `collectProxyMethods` previously saw only methods declared directly on the dependency interface, so a slice whose dependency interface extended another interface generated a proxy record missing every inherited method (non-compiling generated code: "is not abstract and does not override abstract method", anchored in the generated factory), and an inherited non-Promise method bypassed the #612 compile gate. The scan now walks the super-interface closure breadth-first with override de-duplication (nearest declaration wins, one proxy entry per signature) and `asMemberOf` type substitution for generic supers; the proxy record for such a dependency gains `MethodHandle` components, proxy method implementations, method-handle wiring and codec entries for every inherited abstract Promise method, and inherited non-Promise methods now hit the #612 error (naming the declaring interface and the inheriting dependency). Explicit re-declarations of `java.lang.Object`'s public methods are excluded from proxy candidacy. Dependency interfaces without super-interfaces are byte-for-byte identical to the prior output. No previously-loadable artifact changes shape — the affected inputs never compiled — so the stamp stays 1000 and the runtime accept-set is unchanged. |
| 1000 (no bump — GA freeze) | #386 D5: context-carrying topic subscribers — a subscription method may now declare `Promise<Unit> handler(T event, MessageContext context)` (second parameter exactly `org.pragmatica.aether.slice.topic.MessageContext`) in addition to the existing 1-arg shape. For that shape the generated `SliceMethod` takes a `ContextualEvent`, casts its erased payload to `T` and invokes the two-argument method (`contextual -> delegate.m((T) contextual.event(), contextual.context())`), the adapter record's delegate override mirrors the declared two-argument signature, and the manifest gains the additive key `reactive.{i}.context = "message"`. The context parameter is not a payload parameter, so no request record is synthesized for it and codec/topic-payload resolution still sees the event type alone. The shape is a compile error on a topic whose `resources.toml` section does not declare `durability = "durable"` (absent section and the ephemeral default included — the processor reads `resources.toml` from the class output and fails closed when it cannot be read), and also when the handler carries method interceptors, whose payload-typed chain cannot carry the context. Subscribers declaring the 1-arg shape are byte-for-byte identical to the prior output and their manifests omit the new key, so a runtime that predates it is unaffected. Emitted under the GA freeze, so the stamp stays 1000 and the runtime accept-set is unchanged. |

> Note: a `transitionedAt: long` field was added to the `SliceNodeValue` / `NodeArtifactValue` KV atoms (Theme K). That is a KV-atom wire-format change, NOT an envelope (generated-codegen) format change, so it does not consume an `ENVELOPE_FORMAT_VERSION` number — KV atoms version independently with backward-compatible readers.

## When to Bump

Bump the envelope version when changing:

- Factory constructor signature (parameters, order)
- Generated methods in the Factory class (added, removed, renamed)
- Dependency wiring protocol (proxy record structure, how dependencies are injected)
- Resource provisioning through generated code
- Any change in `SliceProcessor` or `FactoryGenerator` output that would make old runtimes unable to call the generated code

## When NOT to Bump

Do not bump for:

- Adding new properties to the manifest file
- Changing logging or error messages in generated code
- Refactoring internal processor code that doesn't change output
- New slice lifecycle hooks (if backward compatible)
- Bug fixes in generation that don't change the structural contract

## Runtime Compatibility Check

The runtime (`SliceManifest.checkEnvelopeCompatibility()`) uses this logic:

| Envelope Version | Action |
|-----------------|--------|
| Missing | Warn, allow (backward compatibility with old JARs) |
| `"dev"` | Allow (development builds) |
| In `SUPPORTED_ENVELOPE_VERSIONS` | Allow |
| Not in supported set | Reject with error |

## Post-1.0 Multi-Version Support

Before 1.0, only one envelope version is supported at a time. After 1.0 GA:

1. Add new version to `SUPPORTED_ENVELOPE_VERSIONS` set in `SliceManifest.java`
2. Keep old versions in the set for backward compatibility
3. Version-specific loading logic can branch on the envelope version value
4. Remove old versions only in major releases with migration guides

## File Locations

| File | Role |
|------|------|
| `jbct/slice-processor/.../ManifestGenerator.java` | Defines `ENVELOPE_FORMAT_VERSION`, writes to Properties manifest |
| `jbct/jbct-maven-plugin/.../PackageSlicesMojo.java` | Copies Properties manifest into per-slice JAR, writes `Envelope-Version` to MANIFEST.MF |
| `aether/slice/.../SliceManifest.java` | Reads envelope version, defines `SUPPORTED_ENVELOPE_VERSIONS`, checks compatibility |
| `aether/slice/.../dependency/DependencyResolver.java` | Chains compatibility check into slice loading |
