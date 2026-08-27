# Suite Audit: 00-smoke / 01-stability / 02-chaos / 03-scaling

Audit date: 2026-05-21. Branch: release-1.0.0-rc1.

## Suite 00-smoke

### test-cluster-formation.sh

#### test_nodes_formed (L9)
- **Claims:** 5 nodes form a cluster.
- **Actually checks:** `cluster_member_count` (generation `coreCount`) is strictly equal to `NODE_COUNT`.
- **Assertions:**
  - L10: `wait_for_cluster_ready 120` — generation members >= expected AND leader AND active>=expected-1.
  - L17-20: strict `-ne` → log_fail; else L21 log_pass.
- **Correctness:** SOUND — uses canonical generation count per test-readiness-contract §1/§6 (seed-node bug fixed).
- **Tooling:** CLI (`cluster_member_count` wraps `aether cluster generation`).

#### test_leader_elected (L24)
- **Claims:** A real leader is elected.
- **Actually checks:** `cluster_leader` returns non-empty AND `!= "none"` AND `!= "null"`.
- **Assertions:** L29-32 strict guard → log_fail; L33 log_pass.
- **Correctness:** SOUND — rejects the literal "none"/"null" that `assert_ne "" ""` would have accepted.
- **Tooling:** CLI (`aether_field status cluster.leaderId`).

#### test_quorum_established (L36)
- **Claims:** Quorum established.
- **Actually checks:** member_count == NODE_COUNT (duplicate of `test_nodes_formed`).
- **Assertions:** L41-44 strict equality → log_fail/log_pass.
- **Correctness:** SOUND but REDUNDANT — same predicate as test_nodes_formed; provides no additional coverage. Does NOT verify Rabia quorum (e.g. `cluster.quorate` field) which would be a stronger contract.
- **Tooling:** CLI.
- **Severity:** LOW — redundancy, not a false-positive risk. Could be upgraded to assert `cluster_quorate=="true"`.

#### test_liveness_probe (L48)
- **Claims:** Liveness probe works.
- **Actually checks:** GET `${CLUSTER_ENDPOINT}/health/live` returns exactly 200.
- **Assertions:** L49 single `assert_http_status`.
- **Correctness:** WEAK — passes against the entry-point host (or nginx gateway in cluster B) and proves only one core (or the gateway) answers. AppHttpServer intercepts `/health/live` with a synthetic 200 regardless of slice state. Doesn't iterate per-node ports.
- **Tooling:** curl-direct.
- **Severity:** LOW — green-sticker pattern: "endpoint responds with 200" alone is a tautological availability check. Not a coverage gap for smoke, but the test name overstates the depth.

#### test_all_nodes_visible (L52)
- **Claims:** All 5 nodes visible.
- **Actually checks:** `cluster_member_count -eq NODE_COUNT` (third use of the same predicate).
- **Assertions:** L57-60.
- **Correctness:** SOUND but REDUNDANT (3rd time).
- **Tooling:** CLI.
- **Severity:** LOW — duplicate.

#### test_status_endpoint (L64)
- **Claims:** Status endpoint returns data.
- **Actually checks:** `cluster_status` non-empty AND `nodeId` field non-empty.
- **Assertions:** L67 `assert_ne` empty; L70 `assert_ne` empty on nodeId.
- **Correctness:** WEAK — TAUTOLOGICAL: only asserts non-emptiness, not field shape or value. A response of `{"nodeId":"x","ok":false,"errors":[...]}` would still pass.
- **Tooling:** CLI.
- **Severity:** MEDIUM — green-sticker: "non-empty body counts as success". Cannot catch malformed status payload.

#### test_events_available (L73)
- **Claims:** Events endpoint returns data.
- **Actually checks:** `cluster_events` non-empty.
- **Assertions:** L76.
- **Correctness:** WEAK — same green-sticker as test_status_endpoint. `[]` is non-empty as a string.
- **Tooling:** CLI.
- **Severity:** MEDIUM — tautological.

#### Summary (00-smoke / test-cluster-formation.sh)
| function | correctness | tooling | severity |
|---|---|---|---|
| test_nodes_formed | SOUND | CLI | — |
| test_leader_elected | SOUND | CLI | — |
| test_quorum_established | SOUND (redundant) | CLI | LOW |
| test_liveness_probe | WEAK | curl-direct | LOW |
| test_all_nodes_visible | SOUND (redundant) | CLI | LOW |
| test_status_endpoint | WEAK | CLI | MEDIUM |
| test_events_available | WEAK | CLI | MEDIUM |

7 tests / 4 log_pass (3 explicit + 4 inside helper asserts). No warn-then-pass demotions.

---

### test-slice-deployment.sh

#### test_push_artifacts (L12)
- **Claims:** Blueprint artifacts pushed.
- **Actually checks:** `push_blueprint` returns 0 (idempotent server-side: uploaded or already-present).
- **Assertions:** L13 helper RC propagated via `set -e`; L14 log_pass unconditional after.
- **Correctness:** SOUND assuming `push_blueprint` does its own status parse (it does — separately audited as helper).
- **Tooling:** CLI (`aether artifacts push --format json`).

#### test_deploy_blueprint (L17)
- **Claims:** Deploy returns a response.
- **Actually checks:** result string is non-empty.
- **Assertions:** L20 `assert_ne "$result" ""`.
- **Correctness:** WEAK — TAUTOLOGICAL. Any output, including an error JSON `{"error":"..."}` from the CLI, passes. Does NOT validate deployment ID, slice ids, or status.
- **Tooling:** CLI.
- **Severity:** MEDIUM — green-sticker: non-empty success without content assertion.

#### test_slices_provisioned (L23)
- **Claims:** Slices reach ACTIVE.
- **Actually checks:** `wait_for_slices_active 1 120` then `slices_total_instances > 0`.
- **Assertions:** L24 wait; L27 `assert_gt`.
- **Correctness:** SOUND — verifies at least one active instance exists.
- **Tooling:** CLI.

#### test_blueprint_listed (L30)
- **Claims:** Blueprint visible in listing.
- **Actually checks:** raw `list_blueprints` text contains substring `BLUEPRINT_NAME`.
- **Assertions:** L33 `assert_contains`.
- **Correctness:** WEAK — substring grep where structured JSON parse is appropriate. Would pass if the name appeared in an error message, comment, or unrelated field.
- **Tooling:** CLI.
- **Severity:** LOW — false-positive risk small but real for short names like `test-echo`.

#### test_app_endpoint_reachable (L36)
- **Claims:** EchoSlice route is wired.
- **Actually checks:** `app_route_wired` for `/api/echo/health` within 60s — distinguishes the JSON `"No route found for "` 404 from real handler 404. Returns 0 for 2xx OR 4xx-non-route-missing.
- **Assertions:** L43-47 wait_for or log_fail; L48 log_pass.
- **Correctness:** PARTIALLY SOUND — improvement over the prior `/health` synthetic intercept; correctly rejects "no route" 404. BUT: counts 4xx-non-route-missing (e.g. 401, 403, 400) as "wired" — could mask an auth/RBAC misconfiguration that blocks the slice. The next test (test_app_request_succeeds) compensates by demanding strict 200, so the suite-level correctness holds.
- **Tooling:** curl-direct (uses raw `curl` in `app_route_wired`).
- **Severity:** LOW — in-suite next test catches the gap.

#### test_app_request_succeeds (L51)
- **Claims:** EchoSlice returns 200.
- **Actually checks:** `assert_http_status ... 200` with API key.
- **Assertions:** L55-57 strict `==200`.
- **Correctness:** SOUND — strict, no body assertion but status code is the canonical contract.
- **Tooling:** curl-direct.

#### Summary (00-smoke / test-slice-deployment.sh)
| function | correctness | tooling | severity |
|---|---|---|---|
| test_push_artifacts | SOUND | CLI | — |
| test_deploy_blueprint | WEAK | CLI | MEDIUM |
| test_slices_provisioned | SOUND | CLI | — |
| test_blueprint_listed | WEAK | CLI | LOW |
| test_app_endpoint_reachable | PARTIAL | curl-direct | LOW |
| test_app_request_succeeds | SOUND | curl-direct | — |

---

## Suite 01-stability

### test-soak-4h.sh

#### test_cluster_baseline (L45)
- **Claims:** Cluster baseline 5 nodes.
- **Actually checks:** `cluster_member_count >= NODE_COUNT` after 60s ready wait.
- **Assertions:** L46 wait; L49 `assert_ge`.
- **Correctness:** SOUND — N-floor is conservative but appropriate for soak entry.
- **Tooling:** CLI.

#### test_deploy_app (L52)
- **Claims:** App deployed and slices active.
- **Actually checks:** push + deploy + `wait_for_slices_active 1 120`.
- **Assertions:** L55 wait; L56 log_pass unconditional.
- **Correctness:** SOUND — relies on wait_for_slices_active's strict count gate.
- **Tooling:** CLI.

#### test_app_reachable (L59)
- **Claims:** Soak key seeded under app endpoint.
- **Actually checks:** PUT status is in `[200, 300)` strictly.
- **Assertions:** L68-73 strict 2xx; explicit log_fail on others.
- **Correctness:** SOUND — explicitly rejects 3xx.
- **Tooling:** curl-direct.

#### test_collect_pre_stats (L76)
- **Claims:** Pre-soak baseline.
- **Actually checks:** Iterates ports `MGMT_PORT + i`, curls `/api/nodes/status`, extracts `uptimeSeconds`. Always log_pass.
- **Assertions:** L79 log_pass unconditional.
- **Correctness:** WEAK — DIAGNOSTIC ONLY. Does not validate stats are sensible (uptime>0, RSS<X, etc.); silently swallows curl failures via `2>/dev/null` and defaults uptime to 0. Stale-fallback pattern.
- **Tooling:** curl-direct + log-grep style.
- **Severity:** LOW (intentionally diagnostic; could be upgraded to assert min uptime).

#### test_soak_load (L82)
- **Claims:** Sustained load within 1% error rate over 4h.
- **Actually checks:** Two parallel sustained loaders against app+health; final result through `assert_error_rate_below 1.0`.
- **Assertions:** L100 `assert_error_rate_below`.
- **Correctness:** PARTIAL — `start_sustained_load` (lib/load.sh) counts `200..399` as success, so a 301/302 misroute would pass. Acceptable for steady state where the app deliberately returns 200, but the threshold is checked against the WRONG denominator if redirects occur. For a soak this is unlikely to hide regressions.
- **Tooling:** curl-direct (load.sh issues raw curl).
- **Severity:** LOW — 3xx-as-success could mask a routing-layer bug, but soak target should never return 3xx by design.

#### test_collect_post_stats (L103)
- **Claims:** Post-soak diagnostics.
- **Actually checks:** Same as pre-stats; cat dump. Always log_pass.
- **Assertions:** L107 unconditional log_pass.
- **Correctness:** WEAK — diagnostic-only; no leak detection assertion (no comparison vs pre-stats). The whole "leak detection" claim in the file header is unsubstantiated by automation.
- **Tooling:** curl-direct.
- **Severity:** MEDIUM — file header promises "leak detection" but no diff/threshold assertion is made; only operator inspection of /tmp/soak_stats.txt would catch it. Green-sticker: data collected ≠ data validated.

#### test_no_node_drift (L110)
- **Claims:** No node count drift.
- **Actually checks:** `cluster_member_count >= NODE_COUNT`.
- **Assertions:** L113.
- **Correctness:** SOUND for floor but doesn't catch upward drift (e.g. CTM provisioning extra nodes). `assert_ge` instead of `assert_eq`.
- **Tooling:** CLI.
- **Severity:** LOW — bidirectional check would be stricter.

#### test_cluster_still_healthy (L116)
- **Claims:** Health=healthy after soak.
- **Actually checks:** `aether_field health status == "healthy"`.
- **Assertions:** L117.
- **Correctness:** SOUND.
- **Tooling:** CLI.

#### test_no_leader_change (L120)
- **Claims:** Leader still present after soak.
- **Actually checks:** `cluster_leader` non-empty.
- **Assertions:** L123 `assert_ne ""`.
- **Correctness:** WEAK — name claims "no leader CHANGE" but only checks "leader EXISTS". A leader churn during soak is not detected.
- **Tooling:** CLI.
- **Severity:** MEDIUM — name/check mismatch. Should record pre-soak leader and compare. Misleading.

#### Summary (01-stability / test-soak-4h.sh)
| function | correctness | tooling | severity |
|---|---|---|---|
| test_cluster_baseline | SOUND | CLI | — |
| test_deploy_app | SOUND | CLI | — |
| test_app_reachable | SOUND | curl-direct | — |
| test_collect_pre_stats | WEAK (diagnostic) | curl-direct | LOW |
| test_soak_load | PARTIAL | curl-direct | LOW |
| test_collect_post_stats | WEAK (diagnostic) | curl-direct | MEDIUM |
| test_no_node_drift | PARTIAL | CLI | LOW |
| test_cluster_still_healthy | SOUND | CLI | — |
| test_no_leader_change | WEAK | CLI | MEDIUM |

9 tests / 7 explicit log_pass + helper passes. No warn-then-pass demotion paths.

---

### test-streaming-soak.sh

#### test_stream_exists (L17)
- **Claims:** Streams enumerated.
- **Actually checks:** `stream_list` retrieved; log_info if empty.
- **Assertions:** L24 log_pass unconditional.
- **Correctness:** WEAK — TAUTOLOGICAL. Empty stream list passes. No actual existence assertion despite the name.
- **Tooling:** CLI.
- **Severity:** MEDIUM — Green-sticker: "endpoint responds" alone; demoted via `if empty -> log_info then log_pass anyway`.

#### test_sustained_publish (L27)
- **Claims:** 1h sustained publish under 2% error.
- **Actually checks:** Loop publishes JSON to `/api/streams/publish/<name>` for STREAM_DURATION; counts strict 2xx as success; `assert_error_rate_below 2.0`.
- **Assertions:** L61.
- **Correctness:** SOUND — strict 2xx in-line classification (NOT 200..399 like the sustained_load helper). Correctly handled.
- **Tooling:** curl-direct.

#### test_cluster_stable_after_stream (L64)
- **Claims:** 5 nodes after streaming soak.
- **Actually checks:** `cluster_member_count == 5` (hardcoded literal, not NODE_COUNT!).
- **Assertions:** L67 `assert_eq "$count" "5"`.
- **Correctness:** SOUND for 5-node deploys but BUGGY: ignores `NODE_COUNT` env override. Other tests in this suite respect `NODE_COUNT:-5`.
- **Tooling:** CLI.
- **Severity:** LOW — config-drift bug; misleads when NODE_COUNT!=5.

#### test_health_after_stream (L70)
- **Claims:** Cluster healthy after streaming.
- **Actually checks:** `aether_field health status == "healthy"`.
- **Assertions:** L71.
- **Correctness:** SOUND.
- **Tooling:** CLI.

#### Summary (01-stability / test-streaming-soak.sh)
| function | correctness | tooling | severity |
|---|---|---|---|
| test_stream_exists | WEAK | CLI | MEDIUM |
| test_sustained_publish | SOUND | curl-direct | — |
| test_cluster_stable_after_stream | SOUND (NODE_COUNT bug) | CLI | LOW |
| test_health_after_stream | SOUND | CLI | — |

4 tests / 4 log_pass paths. test_stream_exists is the demotion (empty list → log_info then log_pass).

---

## Suite 02-chaos

### test-kill-node.sh

#### test_initial_state (L11)
- **Claims:** 5 nodes ready and NORMAL.
- **Actually checks:** wait_for_cluster_ready 60 + wait_for_phase NORMAL 180 (soft) + wait_for_leader 60 + member_count ≥ 5.
- **Assertions:** L14 `wait_for_phase` warn-then-continue (DEMOTION); L18 `assert_ge 5`.
- **Correctness:** PARTIAL — `wait_for_phase NORMAL` is warn-then-pass: if cluster never leaves COLD_BOOT the test continues with `log_warn`, and the kill that follows can produce UnknownObserved silently (no NODE_FAILED event), which would then time out the next test. The warn is acknowledged inline.
- **Tooling:** CLI.
- **Severity:** LOW — warn-then-continue, but the downstream `wait_for_node_departure` will eventually fail hard so the silent-pass risk is small.

#### test_kill_non_leader (L21)
- **Claims:** Non-leader killed; surviving nodes observe departure.
- **Actually checks:** Picks non-leader via CLI; captures topology baseline; `kill_node`; `wait_for_node_departure 60` strict.
- **Assertions:** L28 `assert_ne`; L41-44 strict event check → log_fail; L45 log_pass.
- **Correctness:** SOUND — event-driven barrier (NODE_LEFT/NODE_FAILED via /api/events) replaces the historical `sleep 10`.
- **Tooling:** mixed (CLI for leader/pick; docker for `kill_node`; CLI for events).

#### test_leader_unchanged (L48)
- **Claims:** Leader unchanged.
- **Actually checks:** `cluster_leader` non-empty.
- **Assertions:** L51 `assert_ne ""`.
- **Correctness:** WEAK — same misnomer as `test_no_leader_change`: name claims "unchanged" but only checks "leader exists". Killing a non-leader could trigger a spurious re-election (e.g., if the killed node was an observer) and this test would not catch it.
- **Tooling:** CLI.
- **Severity:** MEDIUM — name/check mismatch.

#### test_health_with_4_nodes (L54)
- **Claims:** Health=healthy with 4 nodes.
- **Actually checks:** `aether_field health status == "healthy"`.
- **Assertions:** L57.
- **Correctness:** SOUND.
- **Tooling:** CLI.

#### test_auto_heal (L60)
- **Claims:** Auto-heal restores to exactly 5.
- **Actually checks:** `wait_for_node_count 5 180` + strict `assert_eq 5`.
- **Assertions:** L64-67 wait; L70 strict equality.
- **Correctness:** SOUND — generation-barrier + strict equality.
- **Tooling:** CLI.

#### Summary (02-chaos / test-kill-node.sh)
| function | correctness | tooling | severity |
|---|---|---|---|
| test_initial_state | PARTIAL (warn-on-phase) | CLI | LOW |
| test_kill_non_leader | SOUND | mixed | — |
| test_leader_unchanged | WEAK (name) | CLI | MEDIUM |
| test_health_with_4_nodes | SOUND | CLI | — |
| test_auto_heal | SOUND | CLI | — |

5 tests / 4 log_pass calls + helper passes. One DEMOTION (`wait_for_phase ... || log_warn`).

---

### test-kill-leader.sh

#### test_initial_state (L11)
- Same as test-kill-node.sh / Same demotion. **PARTIAL** / LOW.

#### test_kill_leader_and_reelect (L21)
- **Claims:** Leader killed; new leader elected and differs from old.
- **Actually checks:** Capture old leader; baseline; kill_node; `rotate_mgmt_entry_point` (soft `|| log_warn`); `wait_for_node_departure 90` strict; `wait_for_leader 150` strict (fail-closed); assert new leader non-empty AND != "none" AND != old leader.
- **Assertions:** L24, L42-45, L51-54, L57, L58, L59.
- **Correctness:** SOUND — fail-closed pattern correctly applied (comment at L48-50 explicitly notes the prior `|| log_warn` flake-mask was removed); 3 strict assertions pin new leader identity.
- **Tooling:** mixed.

#### test_cluster_has_quorum (L63)
- **Claims:** Quorum after leader kill.
- **Actually checks:** `cluster_member_count >= 4` (floor).
- **Assertions:** L66.
- **Correctness:** SOUND for "still has quorum" check but doesn't verify `cluster.quorate` field directly.
- **Tooling:** CLI.

#### test_health_with_4_nodes (L69)
- Same shape as kill-node L54. SOUND.

#### test_auto_heal (L75)
- Same shape as kill-node L60. SOUND.

#### Summary (02-chaos / test-kill-leader.sh)
| function | correctness | tooling | severity |
|---|---|---|---|
| test_initial_state | PARTIAL | CLI | LOW |
| test_kill_leader_and_reelect | SOUND | mixed | — |
| test_cluster_has_quorum | SOUND | CLI | — |
| test_health_with_4_nodes | SOUND | CLI | — |
| test_auto_heal | SOUND | CLI | — |

5 tests / ~6 log_pass calls. One warn-on-phase demotion + one warn-on-rotate (soft, justified).

---

### test-kill-multiple.sh

#### test_initial_state (L11)
- Same as siblings. PARTIAL / LOW.

#### test_kill_two_nodes (L21)
- **Claims:** Two staggered kills; cluster survives with quorum.
- **Actually checks:** Picks 2 non-leaders; kills sequentially with strict event-driven barriers (`wait_for_node_departure 90`); then `wait_for_node_count 5 240 || log_warn` (DEMOTION); `assert_ge count 3`.
- **Assertions:** L43-46 strict; L51-54 strict; L59-60 warn-then-continue; L63 `assert_ge 3`.
- **Correctness:** PARTIAL — final quiescence gate is warn-then-continue. If CTM never converges back to 5, the test still passes as long as the residual count is ≥3 — masking a stuck auto-heal. The next test (test_auto_heal) re-checks for strict 5 with hard fail, which limits the damage, BUT the inline demotion still allows a "kill 2 → 3 remain → no auto-heal → assert_ge 3 passes → BUT next test auto-heal fails" sequence where the suite ultimately reports failure for the right reason but THIS function logs success despite no recovery.
- **Tooling:** mixed.
- **Severity:** LOW — downstream test catches the case; cosmetic only.

#### test_quorum_maintained (L66)
- **Claims:** Healthy after 2 kills.
- **Actually checks:** `aether_field health status == "healthy"`.
- **Assertions:** L69.
- **Correctness:** SOUND but timing-sensitive — runs immediately after kills; relies on prior `wait_for_node_count` having already converged.
- **Tooling:** CLI.

#### test_leader_still_active (L72)
- **Claims:** Leader present.
- **Actually checks:** `cluster_leader` non-empty.
- **Assertions:** L75.
- **Correctness:** WEAK — name claims "still active" (no churn), check is existence only.
- **Tooling:** CLI.
- **Severity:** LOW.

#### test_auto_heal (L78)
- Same shape; SOUND.

#### Summary (02-chaos / test-kill-multiple.sh)
| function | correctness | tooling | severity |
|---|---|---|---|
| test_initial_state | PARTIAL | CLI | LOW |
| test_kill_two_nodes | PARTIAL (warn-on-quiesce) | mixed | LOW |
| test_quorum_maintained | SOUND | CLI | — |
| test_leader_still_active | WEAK | CLI | LOW |
| test_auto_heal | SOUND | CLI | — |

5 tests / 7 explicit log_pass. Demotion pattern present (warn-on-quiesce in test_kill_two_nodes).

---

### test-kill-under-load.sh

#### test_initial_state (L19)
- Same as siblings. PARTIAL / LOW.

#### test_kill_during_load (L29)
- **Claims:** Kill under load; error rate <10%.
- **Actually checks:** Start load against app slice route; `sleep 5` for load ramp (acknowledged as legit time-based ramp, not chaos timing); kill non-leader; strict `wait_for_node_departure 60` → log_fail; wait load; `assert_error_rate_below 10.0`.
- **Assertions:** L60-63 strict event; L74 error rate.
- **Correctness:** SOUND — explicit comment defends the `sleep 5` as load-ramp not chaos timing; `start_load` (separate helper) treats 200..399 as success (3xx-as-success green-sticker still applies but the test contract is "error rate", not status precision).
- **Tooling:** mixed (curl-direct for load, CLI for cluster ops, docker for kill).
- **Severity:** LOW — 3xx-as-success in helper, low impact.

#### test_cluster_survives (L77)
- **Claims:** Healthy after kill-under-load.
- **Actually checks:** `health.status == "healthy"`.
- **Assertions:** L80.
- **Correctness:** SOUND.
- **Tooling:** CLI.

#### test_auto_heal (L83)
- Same shape; SOUND.

#### Summary (02-chaos / test-kill-under-load.sh)
| function | correctness | tooling | severity |
|---|---|---|---|
| test_initial_state | PARTIAL | CLI | LOW |
| test_kill_during_load | SOUND | mixed | LOW |
| test_cluster_survives | SOUND | CLI | — |
| test_auto_heal | SOUND | CLI | — |

4 tests / 4-5 log_pass. One acknowledged sleep (load-ramp).

---

### test-joining-window-kill.sh

#### test_initial_state (L281)
- **Claims:** 5 nodes + NORMAL + label baseline captured.
- **Actually checks:** wait_for_cluster_ready 60; wait_for_phase NORMAL 180 (soft `|| log_warn`); wait_for_leader 60; assert_ge 5; capture pre-priming label snapshot; assert_ge 5 entries.
- **Assertions:** L287 (warn); L291 ge5; L298 ge5 snapshot.
- **Correctness:** PARTIAL — soft phase wait; otherwise tight.
- **Tooling:** mixed (CLI + docker for labels).
- **Severity:** LOW.

#### test_prime_replacement_via_kill (L301)
- **Claims:** Prime CTM with a kill so a replacement R is provisioned.
- **Actually checks:** Pick non-leader; kill_node; record victim.
- **Assertions:** L308, L310 (both `assert_ne ""`).
- **Correctness:** SOUND — setup step, no hard claim beyond "victim chosen + kill issued".
- **Tooling:** mixed.

#### test_catch_replacement_in_joining_window (L316)
- **Claims:** Catch replacement R while it is still JOINING.
- **Actually checks:** Label-set diff via docker to discover R within 90s; sanity ≠ priming victim; `wait_for_replacement_in_kv` (90s) ensures KV atom appears as JOINING or ON_DUTY; record kill timestamp; kill R by label.
- **Assertions:** L321-324 strict; L332 strict; L347-350 strict (no KV atom = fail); L351-358 case statement: JOINING → log_info; ON_DUTY → log_warn (acceptance widened, documented in test header as "race past JOINING → exercise (ON_DUTY, TransportUnreachable) cell").
- **Correctness:** SOUND but the ON_DUTY branch is an acceptance-widening demotion: the test header lists S01 as specifically the (JOINING, TransportUnreachable) cell, but the test still passes if it races into ON_DUTY. The author argues this still exercises the same transport-event code path, which is defensible per spec §16; the warning explicitly surfaces the case. This is a *documented* relaxation, not a hidden green-sticker.
- **Tooling:** mixed (docker label scan + CLI/KV).

#### test_decommission_within_budget (L368)
- **Claims:** R → DECOMMISSIONED in KV within 25s.
- **Actually checks:** `wait_for_kv_decommissioned 25` strict; diagnostic on miss; `assert_ge BUDGET elapsed` (i.e. elapsed ≤ BUDGET).
- **Assertions:** L377-386 strict; L390 strict.
- **Correctness:** SOUND — KV-atom assertion bypasses MembershipView projection lag; strict budget.
- **Tooling:** curl-direct (api_get against /api/nodes/lifecycle/<id>).

#### test_transport_unreachable_event_logged (L394)
- **Claims:** Smoking-gun reason logged on a survivor.
- **Actually checks:** docker logs grep across 5 survivor candidates for `reason=transport-failure|reason=swim-faulty` + R's NodeId.
- **Assertions:** L404-407 strict; L408 log_pass.
- **Correctness:** PARTIAL — broadened to accept `swim-faulty` OR `transport-failure`. This is an acceptance-widening: a swim-only outcome means the new (ungated) TransportUnreachable path did NOT win, which is what S01 was supposed to exercise. The header acknowledges this is "documented as either is a valid S01 outcome", but a strict S01 should require `transport-failure` exclusively. Hides Step 2 (TransportUnreachable emission) regressions where SWIM coincidentally closes first.
- **Tooling:** log-grep (docker logs).
- **Severity:** MEDIUM — green-sticker: alternative-acceptance widens until a real regression in the targeted code path is no longer caught. The test header argues for it explicitly, but the suite's purpose is precisely to detect transport-vs-swim path regressions.

#### test_pick_non_leader_excludes_decommissioned (L411)
- **Claims:** pick_non_leader does not return R after decommission.
- **Actually checks:** Get candidates; if any, must NOT include R; if none returned, log_warn and skip.
- **Assertions:** L418, L427-431 strict (if candidates); L433 log_warn (if none).
- **Correctness:** PARTIAL — empty-candidate path is a SKIP-via-WARN demotion. After 2 kills the cluster is plausibly mid-recovery, so empty is defensible, but the assertion is structurally `if any -> check; else nothing` — a regression that returns "no candidates" universally would not be caught here.
- **Tooling:** CLI.
- **Severity:** LOW — acknowledged in the comment.

#### Summary (02-chaos / test-joining-window-kill.sh)
| function | correctness | tooling | severity |
|---|---|---|---|
| test_initial_state | PARTIAL | mixed | LOW |
| test_prime_replacement_via_kill | SOUND | mixed | — |
| test_catch_replacement_in_joining_window | SOUND (widened) | mixed | LOW |
| test_decommission_within_budget | SOUND | curl-direct | — |
| test_transport_unreachable_event_logged | PARTIAL | log-grep | MEDIUM |
| test_pick_non_leader_excludes_decommissioned | PARTIAL (skip-via-warn) | CLI | LOW |

6 tests / 6 explicit log_pass + several log_warn. Two acceptance-widening demotions documented in test header.

---

### test-self-drain-quorum-loss.sh

#### test_initial_state (L243)
- **Claims:** 5 ON_DUTY cores, NORMAL phase.
- **Actually checks:** wait_for_cluster_ready 60; wait_for_phase NORMAL 180 (soft warn); wait_for_leader 60; `cluster_active_core_count == 5`.
- **Assertions:** L250 warn; L255 strict.
- **Correctness:** PARTIAL — soft phase warn.
- **Tooling:** CLI.

#### test_pick_victims_and_kill_three_simultaneously (L258)
- **Claims:** Three SIGKILLs in one docker daemon call; survivors identified.
- **Actually checks:** Pre-running count == 5; single remote_exec for `docker kill v1 v2 v3`; rc==0 → continue, else log_fail; compute survivors = pre_running \ victims; survivor_count == 2.
- **Assertions:** L277 strict; L299-302 strict; L312 strict.
- **Correctness:** SOUND — single SSH call ensures ~µs-spaced SIGKILLs.
- **Tooling:** mixed (docker + CLI).

#### test_survivors_self_drain_and_exit (L316)
- **Claims:** Both survivors exit within budget (45s).
- **Actually checks:** Per-survivor `wait_for_container_exit` with remaining-budget arithmetic; explicit log_fail if either fails.
- **Assertions:** L321-322 strict; L342-345 strict; L357-360 strict; L365 log_pass.
- **Correctness:** SOUND — strict per-container exit-state polling.
- **Tooling:** docker.

#### test_survivor_exit_codes_are_two (L368)
- **Claims:** Exit code is exactly 2.
- **Actually checks:** `docker inspect ... ExitCode` == 2 on each survivor.
- **Assertions:** L382, L383 strict.
- **Correctness:** SOUND — distinguishes Runtime.halt(2) from SIGKILL(137)/SIGTERM(143)/clean(0).
- **Tooling:** docker.

#### test_drain_trigger_log_signature_present (L386)
- **Claims:** SELF_DRAIN_INITIATED event observed per survivor.
- **Actually checks:** `wait_for_self_drain_event` on each survivor with `SELF_DRAIN_EVENT_TIMEOUT_S=60`; success → log_pass, timeout → log_warn (DEMOTION explicitly documented).
- **Assertions:** L410-419 warn-then-pass.
- **Correctness:** WARN-THEN-PASS DEMOTION — explicitly acknowledged ("the publish flows through Rabia; in S19 quorum is gone... the event MAY still reach the cluster"). Author argues exit-code-2 is the hard contract, so this is a soft observability check. Defensible but does mean a regression that breaks SELF_DRAIN_INITIATED entirely would pass this test silently.
- **Tooling:** CLI (via /api/events).
- **Severity:** LOW — acknowledged + alternative hard signal exists (exit code 2 from prior test).

#### test_no_kv_writes_after_drain_trigger (L422)
- **Claims:** No consensus/KV writes after drain.
- **Actually checks:** docker logs scanned for `ConsensusEngine|RabiaEngine|KvStoreCommand|NodeLifecycleKey write|applyAtomic` AFTER `Self-drain: DRAINING on`. Match → log_warn; no match → log_pass.
- **Assertions:** L436-440, L441-445 warn-then-pass on positive match.
- **Correctness:** WARN-ONLY DEMOTION — explicitly documented as "negative assertion is inherently weaker". A real KV-write leak post-drain is downgraded to a warning, not a failure. Justified by author for benign log noise risk, but means the test cannot fail on this contract.
- **Tooling:** log-grep (docker logs).
- **Severity:** MEDIUM — green-sticker: cannot fail. A regression where SelfDrainCoordinator gains a KV import would be visible in logs but classified as a warning, not a test failure. The compile-time test `noConsensusOrKvImports` is the real guard.

#### test_cluster_recovers_to_five_on_duty (L449)
- **Claims:** S20 — 5 ON_DUTY within 60s after restart.
- **Actually checks:** `restart_all_nodes` strict; `wait_for "5 ON_DUTY healthy cores" 60` strict; `assert_cluster_healthy`.
- **Assertions:** L457-460 strict; L463-469 strict; L470.
- **Correctness:** SOUND.
- **Tooling:** mixed.

#### Summary (02-chaos / test-self-drain-quorum-loss.sh)
| function | correctness | tooling | severity |
|---|---|---|---|
| test_initial_state | PARTIAL | CLI | LOW |
| test_pick_victims_and_kill_three_simultaneously | SOUND | mixed | — |
| test_survivors_self_drain_and_exit | SOUND | docker | — |
| test_survivor_exit_codes_are_two | SOUND | docker | — |
| test_drain_trigger_log_signature_present | WARN-THEN-PASS | CLI | LOW |
| test_no_kv_writes_after_drain_trigger | WARN-ONLY (cannot fail) | log-grep | MEDIUM |
| test_cluster_recovers_to_five_on_duty | SOUND | mixed | — |

7 tests; 9-10 log_pass paths (multiple per function in event/log scans). Two explicit demotions; both documented.

---

## Suite 03-scaling

### test-01-quorum-safety.sh

#### test_seed_config (L10)
- **Claims:** Cluster config seeded.
- **Actually checks:** ready + leader + `seed_cluster_config` (no explicit assertion on the seed result).
- **Assertions:** RC propagation through `set -e`.
- **Correctness:** SOUND — relies on helper rc; if seed fails the suite aborts.
- **Tooling:** CLI.

#### test_initial_state (L16)
- **Claims:** At least 3 nodes.
- **Actually checks:** `cluster_member_count >= 3`.
- **Assertions:** L19.
- **Correctness:** WEAK — floor 3, not strict 5 like baseline tests. Allows previous-suite-degraded cluster to pass.
- **Tooling:** CLI.
- **Severity:** LOW.

#### test_reject_scale_to_1 (L63)
- **Claims:** Scale to 1 is rejected.
- **Actually checks:** `direct_scale_status` for `{"coreCount":1}` returns status ≥ 400.
- **Assertions:** L66-72 `>= 400 -> pass; else fail`.
- **Correctness:** PARTIAL — accepts ANY 4xx OR 5xx as "rejection". A 503/500 from a broken validator (server crash) would pass this test as "rejected" when it's actually "server cannot process". Should validate 4xx specifically (400/409/422 are correct rejections). The `direct_scale_status` helper also iterates all nodes and returns the FIRST non-000 status, but with a leader-only valid endpoint the followers return 4xx for "not leader" which conflates with validator rejection.
- **Tooling:** curl-direct.
- **Severity:** MEDIUM — green-sticker: `>= 500` accepted as success. The helper's leader-iteration also means a "not leader" 4xx from a follower is indistinguishable from "scale rejected" — coverage gap for the actual validator.

#### test_reject_scale_to_2 (L74)
- Same shape as `test_reject_scale_to_1`. PARTIAL / MEDIUM.

#### test_reject_scale_above_max (L85)
- Same shape (scale to 20). PARTIAL / MEDIUM.

#### test_cluster_unchanged (L96)
- **Claims:** Cluster unchanged after rejections.
- **Actually checks:** `>= 3` floor; health == healthy.
- **Assertions:** L99 ge 3; L100 healthy.
- **Correctness:** WEAK — "unchanged" should compare pre and post counts; only checks the same floor.
- **Tooling:** CLI.
- **Severity:** LOW — name/check mismatch.

#### Summary (03-scaling / test-01-quorum-safety.sh)
| function | correctness | tooling | severity |
|---|---|---|---|
| test_seed_config | SOUND | CLI | — |
| test_initial_state | WEAK | CLI | LOW |
| test_reject_scale_to_1 | PARTIAL (5xx accepted) | curl-direct | MEDIUM |
| test_reject_scale_to_2 | PARTIAL | curl-direct | MEDIUM |
| test_reject_scale_above_max | PARTIAL | curl-direct | MEDIUM |
| test_cluster_unchanged | WEAK | CLI | LOW |

6 tests / 6 log_pass calls. Three medium-severity green-stickers in the rejection trio.

---

### test-02-scale-up.sh

#### test_seed_config (L10)
- Same as siblings. SOUND.

#### test_baseline_5_nodes (L16)
- **Claims:** Baseline at 5.
- **Actually checks:** `wait_for_node_count_fast 5 60`; strict eq 5.
- **Assertions:** L17-18; L20.
- **Correctness:** SOUND.
- **Tooling:** CLI.

#### test_scale_up_to_7 (L23)
- **Claims:** Scale 5 → 7.
- **Actually checks:** `scale_cluster 7` + `wait_for_node_count_fast 7 300` + strict eq 7.
- **Assertions:** L28; L31.
- **Correctness:** SOUND.
- **Tooling:** CLI.

#### test_7_nodes_healthy (L34)
- **Claims:** Healthy at 7.
- **Actually checks:** `assert_cluster_healthy`.
- **Assertions:** L35.
- **Correctness:** SOUND.
- **Tooling:** CLI.

#### test_restore_to_5 (L38)
- **Claims:** Restore to 5.
- **Actually checks:** `scale_cluster 5` + `wait_for_node_count_fast 5 180` + strict eq 5.
- **Assertions:** L41; L44.
- **Correctness:** SOUND.
- **Tooling:** CLI.

#### Summary (03-scaling / test-02-scale-up.sh)
| function | correctness | tooling | severity |
|---|---|---|---|
| test_seed_config | SOUND | CLI | — |
| test_baseline_5_nodes | SOUND | CLI | — |
| test_scale_up_to_7 | SOUND | CLI | — |
| test_7_nodes_healthy | SOUND | CLI | — |
| test_restore_to_5 | SOUND | CLI | — |

5 tests / 5 log_pass. Clean.

---

### test-03-scale-down.sh

#### test_seed_config (L17)
- Same as siblings. SOUND (extra: `await_generation_quiesced || true` is a soft pre-warm; no assertion).

#### test_scale_up_to_7 (L27)
- **Claims:** Scale up to 7 (precondition for scale-down).
- **Actually checks:** `scale_cluster 7` + `wait_for_node_count_fast 7 180` + strict eq 7.
- **Assertions:** L33; L36.
- **Correctness:** SOUND.
- **Tooling:** CLI.

#### test_scale_down_under_load (L39)
- **Claims:** Scale 7 → 5 under load; error rate <2%.
- **Actually checks:** Start load against app slice route; sleep 5 (load ramp); scale_cluster 5; `wait_for_node_count_fast 5 180`; wait load; `assert_error_rate_below 2.0`.
- **Assertions:** L52, L61.
- **Correctness:** SOUND with the same 3xx-as-success caveat in `start_load` (low impact for app route deliberately returning 200).
- **Tooling:** mixed (curl-direct + CLI).
- **Severity:** LOW.

#### test_5_nodes_healthy (L64)
- **Claims:** 5 nodes; healthy.
- **Actually checks:** strict eq 5 + healthy.
- **Assertions:** L67-68.
- **Correctness:** SOUND.
- **Tooling:** CLI.

#### test_no_data_loss (L71)
- **Claims:** No data loss after scale-down.
- **Actually checks:** `cluster_events` is non-empty.
- **Assertions:** L74 `assert_ne ""`.
- **Correctness:** WEAK — TAUTOLOGICAL: events endpoint reachable is not "no data loss". The test name massively overstates the check. A real data-loss regression (e.g. dropped slice state during scale-down) would not be detected here at all.
- **Tooling:** CLI.
- **Severity:** HIGH — green-sticker: tautological assertion combined with grossly misleading test name. Any reader (operator, release reviewer) would believe this test gives a data-loss guarantee; it gives nothing of the kind. Should either be renamed `test_events_reachable_after_scale` or replaced with a real data-loss assertion (write keys pre-scale → verify post-scale).

#### Summary (03-scaling / test-03-scale-down.sh)
| function | correctness | tooling | severity |
|---|---|---|---|
| test_seed_config | SOUND | CLI | — |
| test_scale_up_to_7 | SOUND | CLI | — |
| test_scale_down_under_load | SOUND | mixed | LOW |
| test_5_nodes_healthy | SOUND | CLI | — |
| test_no_data_loss | WEAK (egregious) | CLI | HIGH |

5 tests / 5 log_pass. One HIGH-severity green-sticker that misnames a non-assertion.

---

## Aggregate Findings

### Recurring patterns

1. **Non-empty as success** — `cluster_status`, `cluster_events`, `list_blueprints`, deploy result, stream list. Five+ instances across suites. The most egregious is `test_no_data_loss` (03-scaling).
2. **Name/check mismatch — "unchanged" / "still active" / "no change" checked as "exists"** — `test_no_leader_change` (soak), `test_leader_unchanged` (kill-node), `test_leader_still_active` (kill-multiple), `test_cluster_unchanged` (quorum-safety). Four instances.
3. **Warn-then-pass on phase=NORMAL** — every chaos `test_initial_state` (5 files). Acknowledged but blanket-applied; a phase regression would only surface as a downstream timeout, not directly.
4. **`>= 400` accepted as rejection** — 03-scaling/test-01 rejection trio. 5xx server crashes pass as "rejected".
5. **3xx accepted as success** — `start_sustained_load`/`start_load` count `200..399`; affects soak + scale-down + kill-under-load error-rate assertions.
6. **Negative assertions as warnings only** — `test_no_kv_writes_after_drain_trigger` cannot fail by design; same pattern in `test_drain_trigger_log_signature_present` (Rabia publish race justification).
7. **log-grep tooling** — used only in 02-chaos `test_transport_unreachable_event_logged` and `verify_no_kv_writes_after_drain`. Both are fragile to log-format drift; both are explicitly documented as "soft" or "alternative-acceptance" by their authors.

### High-impact items (≥ MEDIUM)
- **HIGH** — `test_no_data_loss` (03-scaling/test-03-scale-down.sh L71): name promises data-loss check, asserts only events endpoint non-empty.
- **MEDIUM** — `test_status_endpoint`, `test_events_available` (00-smoke): non-empty-as-success.
- **MEDIUM** — `test_deploy_blueprint` (00-smoke): non-empty-as-success.
- **MEDIUM** — `test_no_leader_change` (soak), `test_leader_unchanged` (kill-node): existence ≠ unchanged.
- **MEDIUM** — `test_stream_exists` (streaming-soak): empty list passes via if-then-log_pass.
- **MEDIUM** — `test_collect_post_stats` (soak): "leak detection" claim with no comparison assertion.
- **MEDIUM** — scaling reject trio (03/test-01): 5xx accepted as rejection.
- **MEDIUM** — `test_transport_unreachable_event_logged` (joining-window-kill): swim-faulty accepted, defeats S01 path-isolation premise.
- **MEDIUM** — `test_no_kv_writes_after_drain_trigger` (self-drain): warn-only on positive match — cannot fail.

### Tooling distribution
- **CLI-dominant:** 00-smoke (cluster-formation), all 01-stability (CLI for cluster ops; curl for load/publish), all 03-scaling.
- **curl-direct heavy:** 00-smoke/test-slice-deployment.sh (route probing), 01-stability load helpers, 03-scaling rejection direct-leader probes.
- **docker tooling:** all 02-chaos for kill/inspect.
- **log-grep:** confined to 02-chaos for smoking-gun reasons / negative KV-write checks.

### Pass-call ratio (demotion sniff test)
| Suite | total test fns | log_pass calls (rough) | demotions noted |
|---|---|---|---|
| 00-smoke | 13 | ~12 | 0 |
| 01-stability | 13 | ~11 | 1 (stream_exists) |
| 02-chaos | 28 | ~35 | 5 (phase-warn x5, drain-event warn, kv-write warn, pick-non-leader skip, S01 acceptance widening x2) |
| 03-scaling | 16 | ~16 | 0 explicit demotion (but rejection trio's 5xx-accepted is a coverage hole, not a demotion) |

02-chaos is the demotion hot zone — all of them documented in test header comments, but several still hide real regressions per the patterns above.
