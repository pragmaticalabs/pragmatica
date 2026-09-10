### Fixed (2026-09-10 — #908: the management plane answered anonymous callers as VIEWER during the pre-bootstrap boot window)

- **A management-API request carrying no `X-API-Key` is now REFUSED unconditionally.** It used to be
  refused only when the KV-aware validator could already *see* a credential somewhere — the node's
  own `[app-http.api-keys]`, or an API key already committed to the KV store — and otherwise handed
  back `SecurityContext.securityContext()`, the anonymous context, whose `authorizationRole` is
  `VIEWER`. Every management read route admits `VIEWER`, so during the window between node start and
  the leader registering the cluster's bootstrap admin key in KV, a node running `security_mode =
  "api-key"` with no keys of its own had neither source and served anonymous reads of management
  state. The validator was granting *because it could not check* — the same shape as #573 and #888,
  where "no credentials configured" was read as "no authentication wanted" rather than "no
  authority".
- **The operator's opt-out is untouched and is not in the validator.** `security_mode = "none"` is
  honored one level up, at `ManagementServerImpl`'s `if (securityEnabled)` gate, which never
  consults a validator at all. A request that reaches the validator has already passed a node that
  asked for a credential, so refusing it there cannot take away an openness the operator chose.
- **The `hasConfiguredCredentials()` predicate is DELETED** — its declaration on `SecurityValidator`
  and both overrides. It existed for exactly one purpose, deciding whether to fail open, and a
  callerless predicate that once meant "it is safe to skip authentication" is precisely the loaded
  backstop #876 (below) is about. A repo-wide grep confirms no other caller, main or test.
- **`KvStoreApiKeyValidator.validate` is now exhaustive over the sealed policy hierarchy, with no
  `default` arm**, mirroring `ApiKeySecurityValidator`. The `default -> validateApiKey(request)` it
  replaces routed `Unspecified` and `unused()` — the two states that mean *nobody decided* — into the
  credential check, so a caller holding any valid cluster key was served a route whose policy had
  never been resolved; they now answer `UNRESOLVED_POLICY`. `BearerTokenRequired` returned SUCCESS
  with an anonymous context and no credential inspected at all; it now answers
  `UNENFORCEABLE_POLICY`. That is the same fail-open arm #866 review G1 removed from the two sibling
  validators and left behind in this one. Adding a policy state is now a compile error here too.
- [verified: `aether/node/src/test/java/org/pragmatica/aether/api/ManagementBootWindowAuthTest.java`
  — 14 tests. They run the pipeline `ManagementServerImpl#validateManagementSecurity` runs, calling
  its own `resolvePermission` and `resolveSecurityErrorStatus` rather than re-implementing them, and
  assert the status handed to the response writer (401), not an internal predicate. Each denial
  carries three discriminators inside the same test: the route resolves to a real permission, that
  permission *would* have admitted the anonymous context (so the refusal is authentication, not
  authorization), and the cause is the specific missing-credential error rather than a generic
  denial. `#registeredBootstrapKey_isServedWithAdmin_provingTheHarnessReachesSuccess` is the
  positive control that the harness can reach a success at all.]
- [unverified: no cluster was run. The claims above are unit-level plus source-read; the boot-window
  behavior was not observed against a live node. `ManagementServerImpl`'s `securityEnabled` gate —
  the `security_mode = "none"` opt-out — is asserted by source read, not by a test, because standing
  up the Netty listener is out of reach of this suite.]
- **Severity: this is the DEFAULT deployment path, not an edge case.** The shipped TOML loader
  defaults `security_mode` to `API_KEY`, not `none` — `ConfigLoader` resolves it as
  `explicitMode.or(SecurityMode.API_KEY)` (#290, secure-by-default). So a node started from stock
  configuration has `securityEnabled() == true`, the management plane *does* consult the validator,
  and if the operator declared no `[app-http.api-keys]` the boot window described above is exactly
  what it runs. The `SecurityMode.NONE` literals in the tree belong to `AppHttpConfig`'s convenience
  factories and the in-JVM harnesses (`EmberCluster`, and `ForgeServer` via it), not to the path a
  deployed node loads.
- **Not fixed here, and genuinely out of scope:** an operator who *explicitly* sets
  `security_mode = "none"` still gets a management plane that dispatches every request, writes
  included, with no security context — `ManagementServerImpl` skips the validator entirely. That is
  the deliberate opt-out this ticket's acceptance criterion exempts by name, and narrowing an
  explicitly-requested posture is a product decision rather than a validator fix.
