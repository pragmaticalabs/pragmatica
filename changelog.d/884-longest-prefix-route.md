### Fixed (2026-09-14 — #884: a request could be authorized under one slice's policy and answered by another)

- **The policy search picked the first prefix match in hash order.**
  `HttpRoutePublisher.findLocalRoute` returned the first route whose prefix the path started with
  while iterating `publishedRoutes`, a `ConcurrentHashMap`. Two slices on one node declaring nested
  prefixes (`examples/pricing-engine`: `/api/v1/pricing` and `/api/v1/pricing/analytics`) both match
  a request under the inner one, and which won was the artifacts' hash iteration order —
  unspecified, and under #866 deciding which security policy applied.
  [mechanism: `for (routes : publishedRoutes.values()) … if (startsWith) return` — first match, no
  ordering]
- **The dispatch search was a SECOND, differently-ordered search, and fixing only the first would
  have been worse than fixing neither.** `AppHttpServer.dispatchToRoute` scanned
  `HttpRoutePublisher.allLocalRoutes()` and took the first match. That set is a `Set.copyOf` result
  — a `java.util.ImmutableCollections.SetN`, whose iteration probe sequence is derived from a
  `SALT` seeded once per JVM — so over a nested pair it picked the parent on some node starts and
  the child on others. Making the policy half deterministic on its own would have converted an
  intermittent disagreement into a systematic one: the request admitted under the child's policy
  every time, served by the parent slice on roughly half of node starts.
  [mechanism: `Set.copyOf(new HashSet<>(…))` → `SetN`, probe sequence salted per JVM]
- **Both halves now resolve through one selection**, `HttpRoutePublisherImpl.selectRoute`: longest
  matching prefix for the method, then the lexically smaller artifact coordinate. `findLocalRoute`
  (the policy search), `AppHttpServer.dispatchToRoute` (which now calls it, via
  `AppHttpServer.resolveLocalRoute`) and `findLocalRouter` (the router that serves) read the same
  `publishedRoutes` snapshot through the same total order. There is one search, so there is nothing
  left to disagree with — `AppHttpServer.findMatchingLocalRoute` is gone rather than reordered.
  [verified: `AppHttpServerRouteAgreementTest.aPathUnderTheNestedPrefix_isServedByTheSliceWhosePolicyAdmittedIt`
  — a live server, a nested pair with `api_key` parent and `public` child, and a route set ordered
  parent-first (one of the orders the production `Set.copyOf` takes); its sibling
  `aPathOutsideTheNestedPrefix_isGovernedByTheOuterRoutesStricterPolicy` is the positive control
  that the two policies differ and the difference is reachable — it answers 401]
- **The policy pick and the ROUTER that serves it now agree on identical prefixes too.**
  `findLocalRouter` matched an exact prefix by first match over `publishedRoutes`, i.e. by artifact
  hash — unrelated to the coordinate tie-break the policy half uses. Two artifacts publishing the
  same method and prefix is a publication collision the publisher still does not reject; it now
  resolves both halves to the same artifact.
  [verified: `HttpRoutePublisherRouteAgreementTest.identicalPrefixes_resolveToTheLexicallySmallerCoordinate_andTheRouterAgrees_{low,high}PublishedFirst`
  — both publication orders, through the production 4-arg `publishRoutes` →
  `publishViaSliceRouterFactory` path, asserting the returned `SliceRouter` is the same instance the
  publisher holds for the artifact the policy search named]
- **`HttpRouteDefinition` now normalizes `pathPrefix` in its constructor**, so every construction
  path stores a slash-terminated prefix and `normalizedPath.startsWith(prefix)` is a segment-boundary
  test by construction. Both production producers already reached a normalizing factory, so this
  changes no production behaviour; what it changes is that the property the selection rule depends
  on is now enforced where it is read instead of assumed from a call site elsewhere. This closes the
  second half of the #866 review G4 gap recorded in `AppHttpServer` — a local `/api/v1/pricing` no
  longer matches `/api/v1/pricing-admin/report` — and that comment has been replaced.
  [verified: `HttpRouteDefinitionPrefixTest` — canonical constructor, `Result` factory and `String`
  factory, plus `aStoredPrefix_doesNotSwallowALongerSiblingSegment` with its positive control]
- The longest-prefix rule itself is pinned as a **property**, not an example: 40 generated rounds of
  2-to-5-deep nested chains, fresh artifact coordinates each round (which is what varies
  `ConcurrentHashMap` iteration order in-process) and a shuffled publication order, querying every
  depth in the chain rather than only the leaf.
  [verified: `HttpRoutePublisherRouteAgreementTest.generatedAmbiguousTables_resolveToTheDeepestContainingPrefix_andPolicyAndRouterAlwaysAgree`]
- [unverified: no multi-JVM run] No test here varies `ImmutableCollections.SALT` — a single JVM
  cannot. It is not needed for the fix's claim: `Set.copyOf` is no longer on the resolution path at
  all, which is a structural fact about the call graph rather than a distribution to sample. The
  removal is pinned by mutation, not by a test that observes the old coin flip.
- [unverified: no cluster run] Single-JVM tests only; no multi-node deployment exercised the nested
  pair.
- Still not adjudicated: an identical (method, prefix) collision is not **refused** at publish. It
  belongs at blueprint admission, where the whole route set is visible — a node publisher sees only
  its own slices and would refuse whichever activated second, so two nodes would refuse different
  halves of one pair. Filed as a separate question, not folded in here.
  [design intent — unverified]
