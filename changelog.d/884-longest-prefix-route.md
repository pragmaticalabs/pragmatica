### Fixed (2026-09-14 — #884: `HttpRoutePublisher.findLocalRoute` picked the first prefix match in hash order)
- **`findLocalRoute` returned the first route whose prefix the path started with while iterating
  `publishedRoutes`, a `ConcurrentHashMap`.** Two slices on one node declaring nested prefixes
  (`examples/pricing-engine`: `/api/v1/pricing` and `/api/v1/pricing/analytics`) both match a request
  under the inner one, and which won was the artifacts' hash iteration order — unspecified, and
  under #866 deciding which security policy applied. [mechanism: `for (routes : publishedRoutes.values())
  … if (startsWith) return` — first match, no ordering]
- Resolution is now **longest matching prefix wins** for the request's method; identical
  (method, prefix) pairs from different artifacts — a publication collision this method does not
  reject — tie-break on the lexically smaller artifact coordinate so the answer is at least stable
  across restarts. Nested prefixes across slices are legal; the rule is stated in
  `slice-patterns.md` ("Nested Prefixes Across Slices").
  [verified: `aether/aether-invoke/src/test/java/org/pragmatica/aether/http/HttpRoutePublisherNestedPrefixTest.java`
  — two artifacts published through the real 3-arg `publishRoutes` (each via a class loader whose
  `ServiceLoader` lookup names one factory), in BOTH publication orders, plus an instrument check
  that the outer artifact hashes ahead of the inner in a fresh `ConcurrentHashMap` — so a first-match
  scan cannot pass by luck — and a control that a path outside the inner prefix still resolves to the
  outer route]
- Not changed: `findLocalRouter` (exact-prefix lookup) and the cluster-wide route table; the
  publish-time collision guard is out of scope (#1103 spacer-in-wrong-slot and #1102 are the routing
  neighbours; #764 the adjacent 404). [design intent — unverified]
