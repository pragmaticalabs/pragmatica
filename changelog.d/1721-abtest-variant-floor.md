### Fixed (2026-10-04 — #1721: an A/B test wrote the slice target below the runtime floor)
- **The A/B test manager wrote the variant onto the slice's own target at 1 instance**, so for the length of
  a test the slice ran under the runtime floor of `SliceSpec.MIN_INSTANCES` (3, #1495). The variant is the
  slice's own target (same key, new version), so there is no separate target to exempt: the canary is now
  written at the floor, a first write for a slice with no target uses it, and the conclusion writes
  (promote, rollback) are clamped to at least the floor even when the operator's `minInstances` is 1.
  The operator's floor itself is still carried untouched (#982). A canary therefore costs 3 instances,
  not 1. Replaces the "known exception" in #1495's changelog entry and `slice-developers/deployment.md`.
