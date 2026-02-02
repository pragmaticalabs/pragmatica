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

- 12000+ / 12100+: Reserved for future tests
