# Suite 05-security Charter

**Test-ID convention:** `TC-05-SECURITY-NNN` — zero-padded 3-digit index, stable across reorganisations, allocated in `run_test` order.

**Charter purpose:** Anchor every test to a security contract: TLS-rotation semantics, API-key principal injection (identity surfacing), and route-level RBAC enforcement.

---

## Contracts under test

| ID | Contract | Spec citation |
|---|---|---|
| C1 | `/api/certificates` reports `tlsEnabled=true` when TLS is configured; `renewalStatus=NOT_CONFIGURED` for the dev/insecure mode is honoured as a clean skip. | `aether/docs/specs/rbac-spec.md §7` (TLS); `aether/docs/reference/management-api.md` (certificates) |
| C2 | A certificate rotation completed under sustained traffic keeps the strict-2xx success rate within budget on an authenticated TLS-bearing route. | `rbac-spec.md §7.3` (Rotation); `unified-deploy-spec.md` (zero-downtime invariant) |
| C3 | Authenticated callers see their own identity surfaced by the server (`GET /api/whoami` echoes the API key's principal). | `rbac-spec.md §3` (Principal injection); `aether/docs/reference/management-api.md` (whoami) |
| C4 | Distinct API keys produce distinct identity views (admin ≠ viewer on the identity field). | `rbac-spec.md §3` |
| C5 | Auth-required app/management routes reject unauthenticated requests with HTTP 401 (not 200, not 500). | `rbac-spec.md §4` (Route protection) |
| C6 | 401 responses include `WWW-Authenticate` per HTTP standards. | `rbac-spec.md §4.2` |
| C7 | Public health probes (`/health/live`, `/health/ready`) are reachable without authentication. | `test-readiness-contract.md §2`; `rbac-spec.md §4.1` (public allow-list) |
| C8 | Role-based authorization: `viewer` can read but not mutate; `operator` can scale; `admin` can deploy. Invalid keys yield 403. | `rbac-spec.md §5` (Role matrix) |
| C9 | Cluster remains healthy at full membership across all security probes. | `test-readiness-contract.md §1.1` |

---

## Test-to-contract map

| TC ID | Test function | File:line | Contract(s) | Severity | Notes |
|---|---|---|---|---|---|
| TC-05-SECURITY-001 | `test_cluster_ready` | `test-cert-rotation.sh:16` | C9 | smoke | `wait_for_cluster_ready 60` + log_pass. |
| TC-05-SECURITY-002 | `test_tls_active` | `test-cert-rotation.sh:31` | C1 | core | RC1-blocker #3 CLOSED in 13df96427 — now asserts `tlsEnabled=true` in `/api/certificates` (replaces previous "config non-empty" tautology). |
| TC-05-SECURITY-003 | `test_rotation_under_load` | `test-cert-rotation.sh:85` | C2 | core | RC1-blocker #4 CLOSED in 13df96427 — clean skip on `NOT_CONFIGURED`; load drives an authenticated TLS-bearing route (no longer `/health/live`). |
| TC-05-SECURITY-004 | `test_cluster_healthy_after_rotation` | `test-cert-rotation.sh:133` | C9 | regression-net | `sleep 5; assert_cluster_healthy`. |
| TC-05-SECURITY-005 | `test_all_nodes_present` | `test-cert-rotation.sh:138` | C9 | regression-net | `assert_ge cluster_member_count NODE_COUNT`. |
| TC-05-SECURITY-006 | `test_cluster_ready` | `test-principal-injection.sh:11` | C9 | smoke | — |
| TC-05-SECURITY-007 | `test_admin_identity_in_response` | `test-principal-injection.sh:20` | C3 | core | RC1-blocker #5 CLOSED in 50af7bcde — uses `/api/whoami` and asserts admin principal in the response body. |
| TC-05-SECURITY-008 | `test_different_keys_different_identity` | `test-principal-injection.sh:47` | C3, C4 | core | RC1-blocker #6 CLOSED in 50af7bcde — admin vs viewer responses compared; identity field MUST differ. |
| TC-05-SECURITY-009 | `test_app_endpoint_principal` | `test-principal-injection.sh:70` | C5 | core | RC1-blocker #7 CLOSED in 50af7bcde — unauthenticated request to auth-required path now strict-asserts HTTP 401 (not "any positive code"). |
| TC-05-SECURITY-010 | `test_unauthenticated_response_format` | `test-principal-injection.sh:85` | C5, C6 | core | RC1-blocker #8 CLOSED in 50af7bcde — strict-asserts status 401 AND `WWW-Authenticate` header presence (warn-then-pass demotion removed). |
| TC-05-SECURITY-011 | `test_health_public_no_auth` | `test-route-security.sh:9` | C7 | core | Strict 200 on `/health/live` unauth; `assert_cluster_healthy`. |
| TC-05-SECURITY-012 | `test_status_requires_auth` | `test-route-security.sh:15` | C5 | core | Strict 401 on unauthenticated `/api/nodes/status`. |
| TC-05-SECURITY-013 | `test_status_with_auth` | `test-route-security.sh:22` | C5, C8 | core | Strict 200 with admin key. |
| TC-05-SECURITY-014 | `test_status_invalid_key` | `test-route-security.sh:27` | C5, C8 | core | Strict 403 with invalid key. |
| TC-05-SECURITY-015 | `test_viewer_can_read` | `test-route-security.sh:34` | C8 | core | Strict 200 on read routes with viewer key. |
| TC-05-SECURITY-016 | `test_viewer_cannot_mutate` | `test-route-security.sh:45` | C8 | core | Strict 403 on `POST /api/scale` with viewer key. |
| TC-05-SECURITY-017 | `test_admin_can_deploy` | `test-route-security.sh:57` | C8 | core | Status NOT in {401,403} — admits 5xx as "auth passed". Audit §1.7 NARROW (RC2). |
| TC-05-SECURITY-018 | `test_operator_can_scale` | `test-route-security.sh:73` | C8 | core | Same NARROW pattern as 017 (RC2). |

**Total tests:** 18.

---

## Suite-level invariants

- **Pre-conditions:** Cluster B destructive, 5 nodes; `AETHER_INSECURE_DEV_MODE` is unset for this suite when TLS rotation is exercised (otherwise `NOT_CONFIGURED` clean-skip kicks in). Blueprint `test-echo` pre-pushed. Three RBAC keys (admin, operator, viewer) provisioned by harness.
- **Side effects:** May trigger a cert rotation via `POST /api/config` if TLS is configured. Drives short bursts of load against authenticated routes. Does not deploy slices, scale, or kill nodes.
- **Cleanup discipline:** Cert rotation is irreversible; the rotated cert remains in place. Destructive cluster (compose-b, `restart: "no"`) is recycled between runs.
- **Demotion-free:** This suite was the most concentrated source of RC1-blockers in audit 2026-05-21 (6 of 18 tests); post-fix (commits 13df96427, 50af7bcde) all 6 are now strict.

---

## Known limitations

| TC ID | Limitation | Tracking |
|---|---|---|
| TC-05-SECURITY-017 | "Admin can deploy" accepts any status outside {401, 403} — a 5xx server-side bug would pass | Audit §1.7 NARROW (RC2) |
| TC-05-SECURITY-018 | Same pattern as 017 | Audit §1.7 NARROW (RC2) |
| TC-05-SECURITY-002 | `NOT_CONFIGURED` clean-skip path still represents weaker coverage in dev-mode runs | Operationally acceptable; RC2 follow-up to gate behind explicit `TLS_REQUIRED=true` env |

No RC1-open findings remain in this suite.

---

## Charter changelog

| Date | Author | Change |
|---|---|---|
| 2026-05-21 | charter-author | Initial charter from audit 2026-05-21; reflects RC1-blockers #3–#8 closed in commits 13df96427 / 50af7bcde (and supporting API changes in e5e941832 + 38c8b5349) |
