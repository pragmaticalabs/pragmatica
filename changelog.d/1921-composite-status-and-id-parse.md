### Fixed (2026-10-05 — #1921: a composite cause and a malformed id both answered 500 on management routes)
- **`ProblemResponses.resolveStatus` now looks inside a composite cause.** `Result.all` / `Result.allOf` hand back a composite when
  several failures funnel together; it did not implement `HttpStatusAware`, so even when every member carried a client-error status
  the response was 500. It now answers the one status every member agrees on. Members that disagree, an empty composite and an
  untyped member stay 500, pinned by their own tests so the unwrap cannot silently widen.
- **One shared parse helper for caller-supplied ids**, `RequestParse.asRequest`: the domain parsers (`BlueprintId`, `Artifact`,
  `Version`, `NodeId`) fail with an untyped cause, so a malformed id answered 500. It becomes a 400 that keeps the parser's message
  (a cause that already carries a status is left alone). Applied at `GET/DELETE /blueprints/{id}`, `GET /blueprints/status/{id}`,
  `GET /slices/config/{id}`, the version of `POST /deploy`, the node id of `/config/nodes/{id}/...` and the four node-lifecycle
  routes.
- **The stream and topic routes' `namespace/name/version` addresses go through the same helper** (16 sites in `StreamApiRoutes`,
  `StreamNamespacesRoutes` and `TopicRoutes`): a malformed address is 400, not 500. `STREAM_NOT_FOUND` and `GROUP_NOT_FOUND` are
  typed 404 (`ManagementServerError.NotFound`, declared identically in #1924 so the two merge cleanly in either order). The
  registry's and the engine's own untyped not-found causes are typed at the route layer below.
- **The consumer-group coordinator's not-leader refusal is typed.** `CoordinatorError.NOT_LEADER` (the coordinator is dormant on this node) was an
  untyped cause, so group create, join and leave answered 500 for what every other leader-bound route answers as 409. A small
  `CoordinatorRefusal.typed` maps it to `ManagementServerError.NotLeader` at the four call sites. Its message is now the standard
  not-leader one, so `StreamRoutesGroupSystemStreamTest` reads that text instead of the old enum text.
- Pinned by `ManagementCompositeAndIdParseStatusTest`: with the production hunks reverted, 16 of its 19 tests answer 500 (the other
  three are the stays-500 controls).
- **Honest scope of the unwrap:** of the three funnel sites the ticket named, `ClusterAwaitQuiescedRoute` maps its composite to a
  typed `InvalidRequest` already, and `StreamApiRoutes` group delete funnels `CoordinatorError`, which is itself untyped, so no
  production route turns the unwrap from 500 to a typed status today; it fixes the class at the funnel, ahead of the next typed
  member. [unverified: `TopicRoutes` group details, whose members were not read]
- [unverified: the four `NodeLifecycleRoutes` sites, which route through the same helper but were not driven; `StatusRoutes` parses a
  node id with `.option()` and is not an error path]
- **A malformed integer path or query parameter is 400 at the funnel** (`ParameterError`, e.g. `partition=abc`, `max=abc`,
  `fromOffset=abc`): the routing layer fails with a status-less cause before any handler runs, so it answered 500. A composite of
  parameter errors is 400 too; a composite that also carries an untyped member or another status stays 500. Pinned by
  `ManagementParameterErrorStatusTest` over every integer-typed path and query parameter in the management route table.
- **Unknown stream is 404 at the stream routes**: `StreamRegistryError.General.NOT_FOUND` (`STREAMS_METADATA`, `STREAMS_EVENTS`,
  `STREAM_NAMESPACES_GET`, which only became reachable with the next entry but one), `NO_VERSIONS_REGISTERED` (`STREAMS_LATEST`) and `StreamError.StreamNotFound` (`STREAM_CONSUMERS`,
  `STREAM_PARTITION`) answered 500. `RequestParse.asNotFound` maps exactly those three causes (not `ALREADY_REGISTERED`) at the
  route layer. Pinned by `ManagementStreamNotFoundStatusTest`.
- **`TOPICS_GROUP_REBUILD` answers 400 for a malformed percent-escape** in the group segment (`billing%zz`, a trailing `%`):
  `URLDecoder.decode` throws, and the throw escaped the handler as a 500. Pinned by `ManagementTopicGroupDecodeStatusTest`.
- **Functional defect, separate from the status work: `GET /streams/{ns}/{stream}/{ver}/consumers` could not find any stream.**
  `STREAM_CONSUMERS` was registered with ONE path parameter while its path carries three, so the handler took the namespace segment
  and looked it up as a flat engine stream name ("Stream not found: com.example.app" for a stream that exists). It now parses the
  full address and asks the engine by `StreamManager.engineKey`; the response `name` is the address. Pinned by
  `StreamConsumersByAddressTest` (a known stream is reachable by its address; red against the old registration).
- **Forwarded management routes validate FORM before forwarding**: a node id that is blank, a `partition` that is not an integer,
  or a stream address that does not parse (`/streams/{ns}/{stream}/{ver}/replicas/{partition}`) used to resolve no target and ride
  the forwarder to a 503. It is now 400 and nothing is sent. A WELL-FORMED node id that names no connected node stays 503 in this
  change (a statement about the cluster, ticketed separately). Pinned in `ManagementServerForwardDispatchTest`, with both 503
  controls.
- **`GET /streams/namespaces/{address}` could not be reached over HTTP.** `STREAM_NAMESPACES_GET` declares ONE path segment while its
  handler registered three, the same arity defect as `STREAM_CONSUMERS`: the matcher delivered `{namespace=foo}` and the handler
  answered a bare 404 "Unknown request path" for every request, including for a registered stream. The segment is now the full
  `namespace:stream:version` address (`ResourceAddress#asString`); the earlier "pin" fed the handler a three-value path the router
  can never deliver. [marked guess: the single `address` segment follows the spec's reduction of this route to one segment; a
  three-segment form collides with `STREAM_GET`'s bucket.]
- **Arity guard over the whole route table**, `ManagementRouteArityGuardTest`: for every declared `ManagementRoute` the path is
  rendered by `assemble`, matched back by the real `ManagementRoute.match`, and the handler the server's own router resolves for
  that name must register the template's parameters. Red on the previous head for `STREAM_NAMESPACES_GET`, and for
  `STREAM_CONSUMERS` with its fix reverted. A trailing literal a handler leaves unregistered (`STREAM_REPLICAS_LOCAL`) is not
  counted: it still reads the right two slots, and a forge test drives it. The funnel's 400 for a `ParameterError` can hide exactly
  this class (a path-slot mismatch is a server wiring fault, not a caller error); this guard is what makes it a build failure.
- **Unknown topic or consumer group is 404 on the topic routes.** `TOPICS_GROUPS` answered 200 with an empty list for a topic that
  does not exist, and `TOPICS_GROUP_REBUILD` answered 409 ("hosts no projection") for an unknown topic or group. Existence is what
  THIS node sees: a committed stream config in its KV view, or the stream materialized in its engine; a group is known when a declared
  consumer of the topic carries it. A known group not hosted on this node stays the deliberate 409. A topic created moments ago on
  another node can read as unknown here until its config commit applies. Pinned by `ManagementTopicExistenceStatusTest`.
- `STREAM_CONSUMERS`' engine key is pinned for a `system`-namespace stream (bare name), where it differs from the address string.
- **One exhaustive `StreamError` -> status mapper on the stream read routes** (`StreamErrorStatus`; `STREAM_PARTITION`, `STREAM_READ`,
  `STREAMS_EVENTS`, `STREAM_CONSUMERS`). No engine error carried a status, so a partition the stream does not have
  (`partition=99` on a 2-partition stream) answered 500. The switch has no default branch, so a new error type or `General`
  constant fails compilation until it is classified. 400 caller error, 404 unknown, 409 conflict, 410 expired cursor [marked
  guess], 503 availability, 500 integrity; the table is in `management-api.md`. Pinned by `StreamErrorStatusTest` (a row per
  variant, checked against the sealed hierarchy, plus driven `STREAM_PARTITION` and `STREAM_READ` out-of-range). The publish routes
  are untouched.
- The arity guard now FAILS on an unregistered trailing literal unless the route is on an allow-list with its reason
  (`STREAM_REPLICAS_LOCAL` only).

