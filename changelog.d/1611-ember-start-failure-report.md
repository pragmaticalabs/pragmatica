### Fixed (2026-11-19 — #1611: Ember start-failure report could omit the failing node)
- **The failing node is recorded before the abort signal fires.** The order was already correct in `EmberCluster.start`; it now lives in one package-visible method, `recordFailureThenSignal`, and `EmberClusterStartFailureOrderTest` pins it with a delayed map put (swapping the two handlers turns it red).
