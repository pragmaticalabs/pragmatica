# Forge Test Port Allocation

Each test class has a dedicated port range to avoid conflicts when running tests in parallel.
Tests use per-method port offsets to avoid TIME_WAIT issues between sequential test methods.

**IMPORTANT**: Port ranges must NOT overlap. Each test class needs: `BASE_PORT + MAX_OFFSET + (NODES - 1)` ports.

## Port Allocation Table

| Test Class                    | Base Port | Base Mgmt Port | Max Offset | Max Cluster Port | Max Mgmt Port | Nodes |
|-------------------------------|-----------|----------------|------------|------------------|---------------|-------|
| ForgeClusterIntegrationTest   | 5770      | 5870           | 15         | 5787             | 5887          | 3     |
| ClusterFormationTest          | 5020      | 5120           | 20         | 5042             | 5142          | 3     |
| SliceDeploymentTest           | 5050      | 5150           | 30         | 5082             | 5182          | 3     |
| SliceInvocationTest           | 5090      | 5190           | 45         | 5137             | 5237          | 3     |
| MetricsTest                   | 5140      | 5240           | 0          | 5142             | 5242          | 3 (shared) |
| NodeFailureTest               | 5150      | 5250           | 60         | 5214             | 5314          | 5     |
| BootstrapTest                 | 5220      | 5320           | 40         | 5262             | 5362          | 3     |
| RollingUpdateTest             | 5270      | 5370           | 60         | 5334             | 5434          | 5     |
| ChaosTest                     | 5340      | 5440           | 50         | 5394             | 5494          | 5     |
| ManagementApiTest             | 5400      | 5500           | 95         | 5497             | 5597          | 3     |
| ControllerTest                | 5500      | 5600           | 35         | 5537             | 5637          | 3     |
| NetworkPartitionTest          | 5540      | 5640           | 40         | 5582             | 5682          | 3     |
| TtmTest                       | 5590      | 5690           | 35         | 5627             | 5727          | 3     |
| GracefulShutdownTest          | 5630      | 5730           | 30         | 5662             | 5762          | 3     |

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
- **Port spacing**: Use 5-port increments (enough for 5-node clusters)
- **Sequential execution**: All tests have `@Execution(ExecutionMode.SAME_THREAD)`
- **Management port offset**: BASE_MGMT_PORT = BASE_PORT + 100

## Adding New Tests

When adding a new test class:
1. Calculate required range: `MAX_OFFSET + (NODES - 1)`
2. Find a base port with sufficient gap from neighbors
3. Add an entry to this table
4. Implement the `getPortOffset()` pattern
5. Use `@Execution(ExecutionMode.SAME_THREAD)` annotation

## Reserved Ranges

- 5790+ / 5890+: Reserved for future tests
