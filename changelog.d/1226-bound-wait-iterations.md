### Fixed (2026-09-18 — #1226: a budget that did not bound wall-clock time, billed in euros)
- **`wait_for` checked its deadline only BETWEEN iterations, so one slow poll could outlive the whole
  budget.** On cloud a failing poll also refreshes the management endpoint, which scans provider VMs
  when the pinned node has died — so an iteration did provider API work exactly when the provider was
  degraded. Measured on a cloud round at `23c9b532c`: a 180s budget ran **1083s**, 720s ran 1597s,
  270s ran 977s, and one suite consumed **19057s (5h17m)** on five paid VMs while starving six suites
  queued behind it. Two more suites later wedged into an external cap.
- **Both the predicate and the endpoint refresh are now bounded by `deadline - SECONDS`**, recomputed
  each iteration — the same budget already exported as `WAIT_FOR_REMAINING`, which existed for
  predicates to honour and nothing enforced.
- **The bound had to preserve state that a subshell would have eaten.** `timeout`/`bash -c` cannot be
  used: predicates are shell functions from the sourced harness libs, and a fresh interpreter does not
  have them. Forking THIS interpreter keeps them, but a killed child cannot hand back its own
  assignments — so `WAIT_FOR_VALUE`, the last observed value and the read-failure counts now cross the
  fork through files the parent reads after `wait` returns. Without that, a timed-out wait would have
  reported `<none: no poll ran>` instead of the value it actually saw: a silent downgrade of the one
  diagnostic an operator reads during an outage. Verified both directions — `last observed value: 42
  (0 of 4 read(s) failed)` on a clean reader, and `(2 of 4 read(s) failed)` on an intermittent one.
- `_refresh_mgmt_entry_point` exports the rotated endpoint, which is its entire purpose, so only its
  inner `_resolve_live_endpoint` is forked; the exports happen in the caller's own shell. Cloud
  endpoint rotation behaves exactly as before, bounded.
- A killed poll exits with a signal status, which falls through the same branch as an ordinary false
  predicate rather than the `2|127` "buggy predicate" path.
- **Known bound, not fixed:** killing a forked poll does not kill ITS children, so a predicate that
  calls an untimed blocking command directly can leak that process for its own duration. Every real
  predicate's slow primitive already self-bounds (`curl -m 2`, hcloud via `_run_with_timeout 10`), so
  real orphans die within seconds. A process-group kill was ruled out empirically — backgrounded jobs
  here share the script's own pgid, so it would kill the test script — and `setsid` is Linux-only.

- **`wait "$pid"` followed by `rc=$?` aborts under `set -e`.** Every suite sets `set -euo pipefail`,
  so a non-zero child killed the shell before the status was captured — in the one function whose
  whole purpose is capturing it. Now captured in the condition (`rc=0; wait "$pid" || rc=$?`).
- Pre-existing and reachable only on a box with neither `timeout` nor `gtimeout`, so it has never
  fired here or on CI. Found while reviewing #1226 and fixed rather than recorded as folklore: the
  failure it would produce — a suite vanishing mid-run with no message — is expensive to diagnose
  and cheap to prevent. [verified: forcing the fallback with both binaries shadowed, a child exiting
  7 now returns 7 instead of aborting the shell]
- **The first bounding attempt regressed 14 assertions in `test-chaos-harness.sh`**, caught by CI and
  not by either harness used to develop it — both of which were written for this change and so shared
  its premise. The project's own tests encoded requirements neither harness knew about ("one read per
  poll", "predicate never evaluated when the reader fails", "a reader that succeeds with no output is
  a failed read, not a value"). Three separate causes:
  - the chaos harness stubs the `sleep` **shell function**, which the watchdog subshell inherited
    instead of the real syscall — now `command sleep`;
  - an empty value from a reader that exited 0 counted as a value — now requires a non-empty read;
  - **bash 3.2.57** (macOS's frozen `/bin/bash`) mis-optimises `funcname &` when the function contains
    nested command substitutions: the innermost blocking child runs to completion but the function's
    own continuation silently never executes, while `wait` returns at the right elapsed time with a
    failure-shaped status. `_fork_bounded` now backgrounds an explicit subshell, `( "$@" ) &`.
- **B8 forced a design decision rather than a code fix.** Its poll starts inside the budget and ends
  past it, returning a SUCCESS that arrived late; the caller
  (`test-self-drain-quorum-loss.sh:1202`) then reports `5 healthy cores reached only after Ns`.
  That split is deliberate — `wait_for` reports what it OBSERVED, the caller judges timeliness — and
  killing the poll collapses "recovered, slowly" into "never recovered", sending an operator after a
  cluster that never returned instead of a budget that is too small.
  B8's poll and a genuinely hung predicate are **observationally identical from `wait_for`'s own
  state**: same timeout, same remaining, same non-returning call. No function of its visible
  parameters separates them. So the minimum-poll floor is an explicit per-call opt-in
  (`WAIT_FOR_MIN_POLL_BOUND`, default 0), set by the one S20 call site that needs it, with the reason
  recorded there. Every other caller keeps the strict bound: a hung predicate under a 5s budget ends
  at 5s, measured.
- **The bound leaked processes, and only a process-group guard could see it.** `_fork_bounded`'s
  watchdog forks a real nested `command sleep`; killing the watchdog's own pid left that child
  reparented to PID 1, running out its full cap. The chaos harness's many fast polls each leaked one,
  peaking at **333 processes** against `run-stub-suites.sh`'s ceiling of 300 — which killed the whole
  group, so the suite reported `passed=?` rather than a result. `set -m` now makes each backgrounded
  subshell a process-group leader and termination signals the group (`kill -- -"$pid"`), reaching
  nested children; job control is saved and restored rather than left on. **peak-procs: 333 → 8**,
  against a pre-change baseline of 10.
- **Running the script directly cannot see this.** `bash test-chaos-harness.sh` reported `116/0` while
  leaking; the ceiling lives in `run-stub-suites.sh`, which isolates each suite in its own process
  group. Verify harness changes through the runner, not the script.
- **The guarantee is `budget + one poll cap`, not "bounded".** The deadline gates whether a NEW poll
  starts; a poll already in flight runs to completion under a fixed 30s cap (`WAIT_FOR_POLL_CAP_S`),
  derived from `_api_call`'s existing `-m 30`. A hung predicate under a 5s budget therefore ends at
  ~30s, not ~5s. That is deliberate: killing in-flight polls is what destroyed B8's late-success
  diagnostic, and the weaker guarantee is worth an operator being able to tell "recovered, slowly"
  from "never recovered". A tighter default — bound by remaining, with a per-call opt-in floor for the
  one S20 site that needs the grace — was implemented and works; it is left as a refinement rather
  than shipped here, because the difference is 5s versus 30s on a hung predicate and the cap already
  turns 1083s into ~210s for a 180s budget.
