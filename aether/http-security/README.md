# Shared HTTP authentication

API-key and JWT verification used by Aether and Terra. `HttpAuthenticator` accepts the shared request and route-policy types. API keys retain named principals and roles; JWT verification retains the existing JWKS cache, signature, expiry, issuer, audience, and role checks. Cluster-managed API keys remain in Aether's `SecurityValidator` adapter.

Authentication and authorization are separate: the host must resolve `Unspecified` to its configured policy and enforce `policy.canAccess(context)` after authentication. A successful authentication alone does not establish the required role. Public requests use an anonymous context. `denyUnlessPublicValidator()` is the safe empty configuration.

JWT key lookup can perform blocking HTTP I/O. Invoke authentication away from transport event-loop threads. Close the authenticator after its in-flight requests finish; JWT cleanup closes its owned JDK HTTP client. The shared interface intentionally does not expose Aether's unconditional system-authority validator.
