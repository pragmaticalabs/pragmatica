### Removed (2026-09-14 — #577: dangling public surfaces — `@Sql("name")` and `StreamConsumerAdapter`)
- **`StreamConsumerAdapter` deleted.** `aether/aether-stream`'s public interface had zero implementations,
  zero call sites (its own file was the only reference in the repository, and no generator emits the
  symbol — `target/generated-sources` across every module: 0 hits) and no tests, while `feature-catalog.md`
  row 146 listed it under Complete as "StreamConsumerAdapter with zero-copy MemorySegment reads". The
  zero-copy distinction did not exist: `zeroCopy()` and `singleEvent()` had byte-identical bodies over
  `byte[]`. Wiring it would mean inventing a consumer surface nobody asked for; the live consumer path is
  `StreamConsumerRuntime` with `ConsumerCallback`/`BatchConsumerCallback`, untouched. Row 146 now says so.
  [mechanism: compiler + `git grep StreamConsumerAdapter` over `*.java` = 0; root `mvn clean install -DskipTests` 145/145]
- **`@Sql("orders_db")` — already documented as absent, unchanged.** `Sql` has no `value` element and is
  fixed to `config = "database"`; `configuration.md` §Database already states "`@Sql` takes no argument …
  `@Sql("name")` … will not compile" and points named datasources at `@ResourceQualifier`. The ticket's
  first item is therefore closed by that earlier docs correction; adding a `value` element would be the
  #566/#575 multi-datasource decision, not this ticket.
- **The "reported, needing confirmation" list, confirmed or refuted at the tip** (drafts for the confirmed
  ones are in the report): `AetherConfig.endpoints()` — confirmed, no production reader of `EndpointConfig`
  outside `ConfigLoader`/`AetherConfig` while `configuration.md` §Infrastructure Endpoints documents it;
  `TypedSubscriber` — refuted as dangling: a hand-usable `slice-api` type (`Topic`, `ContextualEvent`, its own
  test), the processor never needed to emit it; `StreamAccess.fetchFromCommitted` — refuted: an app-facing
  default method (guarantees.md §4 row 19), zero in-repo callers is expected; `ensureAppAddress` (on
  `StreamAccess` and `StreamPublisher`) — confirmed: no caller, only `{@link}`s claiming "resolver-level
  enforcement uses" it; `[operations.tls] cert_ttl` — already #693, `@ConfigKeyLive`-suppressed;
  `[infrastructure.networking] type` — parsed by `ClusterBootstrapConfigParser`, no runtime reader found;
  `TierAwareRetention` — confirmed by design: `KVStoreSerializer` reconstructs it as `none()` on parse
  ("matching the stream-config convention"), so it does not survive a restart; whether that is a defect
  is a streams-persistence question, not a dangling surface.
- [unverified: no runtime observation — the deleted interface was never reachable at runtime]
