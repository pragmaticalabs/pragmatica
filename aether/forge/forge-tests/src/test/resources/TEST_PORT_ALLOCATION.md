# Forge Test Port Allocation

Each test class has a dedicated port range to avoid conflicts when running tests in parallel.
Tests use per-method port offsets to avoid TIME_WAIT issues between sequential test methods.

**IMPORTANT**: Port ranges must NOT overlap. Each test class has a 500-port gap to ensure no conflicts,
even with large per-method offsets (e.g., ManagementApiTest has offsets up to 380).

## Port Allocation Table

| Test Class                    | Base Port | Base Mgmt Port | Max Offset | Notes |
|-------------------------------|-----------|----------------|------------|-------|
| ClusterFormationTest          | 5000      | 5100           | 80         | 3 nodes |
| SliceDeploymentTest           | 5500      | 5600           | 120        | 3 nodes |
| SliceInvocationTest           | 6000      | 6100           | 180        | 3 nodes |
| MetricsTest                   | 6500      | 6600           | 0          | 3 nodes (shared cluster) |
| NodeFailureTest               | 7000      | 7100           | 120        | 5 nodes |
| BootstrapTest                 | 7500      | 7600           | 80         | 3 nodes |
| RollingUpdateTest             | 8000      | 8100           | 120        | 5 nodes |
| ChaosTest                     | 8500      | 8600           | 100        | 5 nodes |
| ManagementApiTest             | 9000      | 9100           | 380        | 3 nodes |
| ControllerTest                | 9500      | 9600           | 35         | 3 nodes |
| NetworkPartitionTest          | 10000     | 10100          | 80         | 3 nodes |
| TtmTest                       | 10500     | 10600          | 35         | 3 nodes |
| GracefulShutdownTest          | 11000     | 11100          | 60         | 3 nodes |
| ForgeClusterIntegrationTest   | 11500     | 11600          | 15         | 3 nodes |
| InvocationMetricsTest         | 12000     | 12100          | 0          | 5 nodes (shared cluster, `@BeforeAll`) |
| SliceVersionLifecycleTest     | 12500     | 12600          | 0          | 3 nodes (shared cluster, app-http 12700; #198 §8.2/§11.3) |
| StreamFanoutConsumerTest      | 13000     | 13100          | 0          | 5 nodes (shared cluster, app-http 13200; #265 STEP 0 streaming baseline) |
| StreamCrashDurabilityTest     | 13500     | 13600          | 0          | 5 nodes (shared cluster, app-http 13700; streaming-persistence A6 WAL crash-durability) |
| StreamOwnerFailoverTest       | 14000     | 14100          | 0          | 5 nodes (shared cluster, app-http 14200; #457 RF=2 owner-kill failover, default membership) |
| StreamOwnerFailoverPinnedTest | 15000     | 15100          | 0          | 5 nodes (shared cluster, app-http 15200; #491 RF=2 owner-kill failover, pinned membership) |
| MultiPartitionStreamTest      | 16000     | 16100          | 0          | 5 nodes (shared cluster, app-http 16200; #429 multi-partition e2e — distribution/order/read-paths) |
| StreamPublishReshuffleTest    | 17000     | 17100          | 0          | 5 nodes (shared cluster, app-http 17200; #430 publish-under-owner-kill-reshuffle chaos) |
| MultiPartitionCrashDurabilityTest | 17500 | 17600          | 0          | 5 nodes (shared cluster, app-http 17700; #431 multi-partition WAL crash-durability, per-partition replay) |

## Per-Method Offset Pattern

Tests use `TestInfo` to get unique port offsets per test method:

```java
@BeforeEach
void setUp(TestInfo testInfo) {
    int portOffset = getPortOffset(testInfo);
    cluster = forgeCluster(3, BASE_PORT + portOffset, BASE_MGMT_PORT + portOffset, "prefix");
    // ...
}

private int getPortOffset(TestInfo testInfo) {
    return switch (testInfo.getTestMethod().map(m -> m.getName()).orElse("")) {
        case "testMethod1" -> 0;
        case "testMethod2" -> 5;  // 5-port increment for 5-node clusters
        case "testMethod3" -> 10;
        default -> 15;
    };
}
```

## Notes

- **MetricsTest** uses `@BeforeAll`/`@AfterAll` (shared cluster) so no per-method offset needed
- **Port spacing**: 500-port gaps between test classes ensure no overlap even with max offsets
- **Sequential execution**: All tests have `@Execution(ExecutionMode.SAME_THREAD)`
- **Management port offset**: BASE_MGMT_PORT = BASE_PORT + 100

## Adding New Tests

When adding a new test class:
1. Calculate required range: `MAX_OFFSET + (NODES - 1)`
2. Use the next available 500-port boundary (e.g., 12000, 12500, etc.)
3. Add an entry to this table
4. Implement the `getPortOffset()` pattern
5. Use `@Execution(ExecutionMode.SAME_THREAD)` annotation

## Reserved Ranges

- 12500+ / 12600+: Allocated to SliceVersionLifecycleTest (app-http 12700)
- 13000+ / 13100+: Allocated to StreamFanoutConsumerTest (app-http 13200)
- 13500+ / 13600+: Allocated to StreamCrashDurabilityTest (app-http 13700)
- 14000+ / 14100+: Allocated to StreamOwnerFailoverTest (app-http 14200)
- 15000+ / 15100+: Allocated to StreamOwnerFailoverPinnedTest (app-http 15200)
- 16000+ / 16100+: Allocated to MultiPartitionStreamTest (app-http 16200)
- 17000+ / 17100+: Allocated to StreamPublishReshuffleTest (app-http 17200)
- 17500+ / 17600+: Allocated to MultiPartitionCrashDurabilityTest (app-http 17700)
- 18000+: Reserved for future tests