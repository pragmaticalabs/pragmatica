### Fixed (2026-09-29 — #1662: the hierarchy runtime workflow selected tests #1545 deleted)
- **`hierarchy-runtime.yml` failed on every release push after #1545.** Its `HIERARCHY_TESTS` list still named
  `HierarchicalGovernorConcurrencyRestartTest` and `HierarchicalCoreRestartTest`, which #1545 removed along with the
  preserved-journal restart mode, so the pre-build selection check refused the run. Both names are gone from the list.
- The journal-independent half of the deleted governor test is back as the Forge test
  `HierarchicalGovernorConcurrentNominationTest`: every worker of a community nominates concurrently through
  `GovernorAuthorityMessage.Request`, and every reply names one governor and one positive community term. It is in
  the workflow selection.
- The hierarchy reconciliation and validation specs no longer present removed classes as current coverage.
