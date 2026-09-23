### Fixed (2026-09-23 — #1462: `SliceVersionLifecycleTest` asked a node that was never going to hold the registry)

- **A forge test asserted a cluster-wide claim against an endpoint that only ever promised a
  node-local one.** `GET /versions` is declared `LOCAL` (`ManagementRoute:114`) and projects only the
  queried node's `HttpRoutePublisher.versionRegistries()`, so a node hosting no versioned slice
  answers `{}` — its documented contract, not a failure. The blueprint deploys `instances = 1`, so
  exactly one of three nodes ever holds that registry, and the test picked its node with
  `status().nodes().getFirst()` over a `ConcurrentHashMap` — hash order, neither readiness-filtered
  nor sorted, and unrelated to the node whose readiness its own gate had established. It passed only
  when the two selections coincided. **No production behaviour changed; none was wrong.**
  `[mechanism: sliceRouters is per-node with a single writer, HttpRoutePublisher:289, and VERSIONS is
  declared LOCAL so it is never forwarded]`
- The test now polls **every** management port until one names the deployed artifact, bounded by the
  same `WAIT_TIMEOUT`/`POLL_INTERVAL` as the class's other gates. The gate is keyed on the artifact
  coordinate — deliberately nothing the test asserts — so it establishes *which* node hosts the slice
  without being able to confirm the assertions about what that node reports. A timeout renders every
  port's body, so the next reader sees the responses rather than an empty string.
  `[verified: aether/forge/forge-tests/src/test/java/org/pragmatica/aether/forge/SliceVersionLifecycleTest.java]`
- The class's two header tests were never exposed: the app-HTTP route **is** forwarded
  (`AppHttpServer`'s `HttpForwarder`), so any ready node serves them correctly. The asymmetry between
  a forwarded app route and a `LOCAL` management route is the whole defect.
  `[mechanism: AppHttpServer.buildHttpForwarder wires HttpForwarder; ManagementRoute.VERSIONS is LOCAL]`
