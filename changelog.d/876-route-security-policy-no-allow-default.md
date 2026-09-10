### Fixed (2026-09-10 — #876: `RouteSecurityPolicy.canAccess` defaulted to ALLOW — a security interface that failed open when an implementor forgot to override)

- **`RouteSecurityPolicy.canAccess` no longer has a default implementation.** It used to be
  `default ... { return Access.ALLOW; }`, so an implementor that omitted the method permitted every
  request — silently, and invisibly to the compiler, on the one method where the policy hierarchy
  states its own contract. Of the three fixes the ticket offered, **option (b)** was taken: removing
  the default makes "forgot to decide" a **compile error**. Flipping it to `DENY` (option (a)) leaves
  the silent-inheritance mechanism intact and merely changes what it silently decides; deleting the
  method (option (c)) drops the contract rather than fixing it.
- **It was not hypothetical.** Removing the default surfaced a real unoverriding implementor:
  `SecurityPolicy.unused()` in `aether/http-handler-api` declared no `canAccess` and therefore
  answered ALLOW — inside the single policy member whose other arms were each deliberately chosen to
  fail closed, and whose javadoc said so. It now returns `Access.DENY`, and that arm is a compile
  requirement rather than something to remember.
- **Public routes state the grant instead of inheriting it.** `RouteSecurityPolicy.permitAll()`
  replaces the five `new RouteSecurityPolicy() {}` sites in `Route` — identical behavior, named at
  the call site. Same reasoning that named `SecurityValidator.permitAllValidator` rather than
  `noOp` (#573): "no-op" reads as harmless, and an unconditional authorization grant is the opposite.
- [verified:
  `integrations/http-routing/src/test/java/org/pragmatica/http/routing/security/RouteSecurityPolicyDefaultTest.java`
  — 4 tests pinning that `canAccess` is abstract, that the interface declares no default methods at
  all, and (as a positive control for the reflection instrument) that `PermitAll` reports a concrete
  one. `SecurityValueObjectsTest#canAccess_unused_denies`,
  `#canAccess_unused_deniesEvenAnAdminApiKey` and `#canAccess_unspecified_denies` complete the
  `canAccess` matrix over the sealed policy sum.]
- [unverified: the compile-time property itself cannot be asserted by a runtime test. The reflection
  tripwire asserts the interface shape that *makes* it a compile error, which is what a
  "restore the convenient default" edit years from now would flip.]
- **BREAKING CHANGE for external implementors of `RouteSecurityPolicy`.** This interface ships in a
  released artifact (`org.pragmatica-lite:http-routing`), and the `v1.0.0-rc3` tag carries the
  `default ... { return Access.ALLOW; }` verbatim. Removing a default method is source-incompatible:
  **any implementor outside this repository that did not override `canAccess` will now fail to
  compile.** That is the intended forcing function rather than an accident — the point of the fix is
  that omitting the method can no longer pass silently — but it is a compile break and is announced
  here rather than left to be discovered.
  **Migration:** a route that is genuinely public replaces the inherited default with the explicit
  `RouteSecurityPolicy.permitAll()`, or implements `canAccess` and decides. There is no behavioral
  change for an implementor that already overrode it.
- **Scope, stated because it is easy to over-read:** `canAccess` still has no caller in main code —
  aether's request path switches on the policy type in `SecurityValidator` instead — so this changes
  no request outcome today. The hazard fixed is that the method looked like a backstop while
  defaulting open. `Route.security()`'s own default remains permit-all for a route that declared no
  security; that is a separate decision and is unchanged.
