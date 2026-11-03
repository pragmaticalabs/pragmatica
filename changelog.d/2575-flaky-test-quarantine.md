### Added (2026-11-02 — #2575: flaky-test quarantine)
- **A flaky test can be quarantined instead of reddening every unrelated PR.** It is tagged `@Tag("quarantine")`,
  with a `// quarantine: #<issue>` comment naming its open issue. Entry, exit and escalation are set by the CTO
  ruling recorded in `know` 5544ec434b. Required CI jobs exclude the tag in both surefire and failsafe: the module
  groups, the Ember and Consensus shards and forge-tests. A non-required `quarantine` job runs exactly the tagged
  tests on every push, so they stay exercised and visible, and never gate a merge.
- `tools/check-quarantine.py check` runs in `static-gates`. It fails on:
  - a tag without an adjacent issue reference;
  - a non-canonical spelling, or a `"quarantine"` literal or annotation type that would hide a tag from the guard;
  - any pom or ci.yml test exclusion, or exclusion property override, that would let the tag back into a required job.
- `tools/check-quarantine.py issues` runs last in the `quarantine` job: a tag citing a closed issue, a pull request
  or an issue it cannot look up turns that non-required job red. It never gates a merge.

  [verified: tools/test_check_quarantine.py]
