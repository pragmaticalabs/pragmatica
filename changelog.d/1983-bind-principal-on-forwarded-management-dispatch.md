### Fixed (2026-10-07 — #1983: a forwarded management request ran on the owner without the validated SecurityContext)
- **A management request that a node forwards to its task-group owner was re-validated there, but the validated
  `SecurityContext` was discarded and the route ran unbound.** Every reader of `SecurityContextHolder` saw ANONYMOUS.
  The node that takes the client call binds it (`handleRequest`); `dispatchManagementForwardWithinBudget` now binds the
  context it just validated around the router and legacy-route dispatch, the same shape as `AppHttpServer`'s forwarded
  path (#1678).
- **Observable defect:** with management security on, an authenticated OPERATOR/ADMIN `PUT /repository/<group>/<artifact>/<version>/<file>`
  (`ARTIFACT_PUT`, task-group DEPLOYMENT) that reached a NON-owner was answered `401` by `MavenProtocolRoutes.admitPush`
  (`hasAuthenticatedOperator` read the unbound holder). Measured on a 3-node Ember cluster at the rc4 node jar:
  `/repository/probe1983/probe/1.0.0/probe-1.0.0.pom` answered 401, 401 on the two non-owners and 400 (content) on the owner;
  with the fix no node answers 401/403. Other forwarded readers: the operator drain/shutdown audit (#1720) once it lands.
- [verified: `ManagementForwardedSecurityContextTest#forwardedOperatorPush_isAdmitted_becauseTheValidatedPrincipalIsBoundOnTheOwner`
  — 401 without the binding, 201 with it; control `#forwardedPush_byACallerBelowOperator_isStillRefused`;
  `EmberForwardedMavenPushTest` on three real nodes with security on, control `#unauthenticatedPush_isRefusedOnEveryNode`]
- [unverified: a multi-segment group (`/repository/org/example/...`, how a Maven client sends it) does not match
  `ARTIFACT_PUT`'s single-segment `groupPath` parameter, so it is not task-group routed and answered 200 on every node
  both before and after; whether that path is meant to forward is a separate question.]
