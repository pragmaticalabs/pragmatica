# Suite NN-Name Charter

**Test-ID convention:** `TC-<SUITE>-<NUMBER>` where:
- `<SUITE>` = the suite directory's numeric prefix (e.g. `02-CHAOS`, `08-RESOURCES`)
- `<NUMBER>` = zero-padded 3-digit index, stable across suite reorganisations
- Example: `TC-02-CHAOS-005` = 5th test in `02-chaos/` suite

**Charter purpose:** Document what every test in this suite verifies and against which contract. A reviewer should be able to pick any random test ID and immediately answer:
1. Which contract / spec section does it test?
2. What regression would it catch?
3. Why is every assertion non-tautological?

If you cannot answer those three questions for a test, the test does not belong in the suite — either rewrite it or delete it.

---

## Contracts under test

Enumerate the contracts this suite verifies. Cite the canonical spec for each. If a contract has no spec home, log a `[CONTRACT-GAP]` entry — a spec follow-up will document it later.

| ID | Contract | Spec citation |
|---|---|---|
| C1 | <one-line statement of what the contract guarantees> | `aether/docs/specs/<spec-name>.md §<section>` or `[CONTRACT-GAP]` |
| C2 | ... | ... |

**Example (from suite 00-smoke):**

| ID | Contract | Spec citation |
|---|---|---|
| C1 | Cluster reaches the canonical "ready" state with N members, leader elected, ≥N-1 active cores | `test-readiness-contract.md §1.1` |
| C2 | Slice deployment makes the slice's app route serve 200 on its declared endpoint | `unified-deploy-spec.md §3`; `slice-lifecycle.md §2` |

---

## Test-to-contract map

Per-test entry. **Every test function in this suite MUST appear here.** Severity column distinguishes:
- `core` — fundamental contract; failure means the feature is broken
- `regression-net` — catches regressions in adjacent code without being the primary contract test
- `smoke` — pre-condition check (e.g., `test_cluster_ready` setup) — low-value but useful for diagnostics

| TC ID | Test function | File:line | Contract(s) | Severity | Notes |
|---|---|---|---|---|---|
| TC-NN-001 | `test_X` | `test-file.sh:LINE` | C1 | core | — |
| TC-NN-002 | `test_Y` | `test-file.sh:LINE` | C1, C2 | core | <e.g., "Asserts via SHA-256 equality"> |
| ... |

---

## Suite-level invariants

Document any cross-test invariants. These often surface naturally as "things that must be true before any test runs" or "things the suite cleanly leaves behind":

- **Pre-conditions:** what the suite assumes about the cluster (e.g., "cluster A non-destructive, 5 nodes, NODE_COUNT=5, no prior tests have left ON_DUTY+DRAINING entries").
- **Side effects:** what state the suite mutates (e.g., "scales the cluster; restores via `restore_cluster_baseline` in EXIT trap").
- **Cleanup discipline:** what the suite guarantees on success and failure (e.g., "auto-heal re-enabled in EXIT trap regardless of test outcome").

---

## Known limitations

Tests in this suite that have documented coverage gaps. These are flagged for the next audit cycle and are not blocking RC1 unless they escalate.

| TC ID | Limitation | Tracking |
|---|---|---|
| TC-NN-XXX | <what it doesn't cover that it arguably should> | <issue #, RC2 item, etc.> |

---

## Charter changelog

| Date | Author | Change |
|---|---|---|
| YYYY-MM-DD | <name> | Initial charter |
| YYYY-MM-DD | <name> | Added TC-NN-NNN for <new test> |

---

## Authoring guidance

When adding a new test to this suite, in this order:

1. **Identify the contract.** What spec section does the test verify? If none exists, the spec gap matters more than the test — file the spec first.
2. **Assign a TC ID.** Next available number; don't reuse IDs from deleted tests.
3. **Write the test.** Reference contracts inline via comments: `# Contract: C1 (test-readiness-contract §1.1)`.
4. **Write the assertion.** It must fail when the contract is violated. Re-read the assertion ignoring the test name — would a reviewer who doesn't know the test's intent understand what's being verified?
5. **Update this charter.** Add the new row to the test-to-contract map. Add a charter changelog entry.

**Smell list — fail-the-charter-review patterns:**
- A test that needs `# WARN_PASS_OK: <reason>` for the CI lint — the reason had better be airtight.
- `assert_ne "$result" ""` on a response without further structure checks (tautology — see audit §2.1).
- A test name that promises stability ("unchanged", "still active", "no drift") but only checks existence.
- A `wait_for_X || log_warn` followed by `log_pass` with no fallback hard assertion (warn-then-pass demotion).
- A `2>/dev/null || true` anywhere in the test (silent stderr trap; see `feedback_silent_stderr_is_a_trap`).
- A test function defined in the file but not invoked via `run_test` (dead code).

All of the above are caught by the CI lint at `aether/tests/integration/lint-tests.sh`.
