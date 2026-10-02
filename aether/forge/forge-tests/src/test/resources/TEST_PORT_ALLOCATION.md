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
| StreamOwnerFailoverTest       | 14000     | 14100          | 0          | 5 nodes (shared cluster, app-http 14200; #457 RF=2 owner-kill failover, default membership; per-PR since #1550) |
| StreamOwnerFailoverPinnedTest | 15000     | 15100          | 0          | 5 nodes (shared cluster, app-http 15200; #491 RF=2 owner-kill failover, pinned membership) |
| ForgeProxyApiKeyForgeTest     | 15500     | 15700          | 0          | 3 nodes (shared cluster; SWIM UDP 15600-15602 = cluster+100, app-http 15800; #1105 Forge proxy calls carry the operator key under API_KEY). Moved off 14500 (#1688 CommunityObservabilityForgeTest registered it first); block confirmed free against rc4, #1688 and #1703 by v1688 |
| MultiPartitionStreamTest      | 16000     | 16100          | 0          | 5 nodes (shared cluster, app-http 16200; #429 multi-partition e2e — distribution/order/read-paths) |
| StreamPublishReshuffleTest    | 17000     | 17100          | 0          | 5 nodes (shared cluster, app-http 17200; #430 publish-under-owner-kill-reshuffle chaos) |
| MultiPartitionCrashDurabilityTest | 17500 | 17600          | 0          | 5 nodes (shared cluster, app-http 17700; #431 multi-partition WAL crash-durability, per-partition replay) |
| DeclarativeStreamConsumerTest | 18000     | 18100          | 0          | 5 nodes (shared cluster, app-http 18200; #488 declarative consumer delivery + #526 app-typed round trip) |
| DeclarativeConsumerPlacementTest | 18500  | 18600          | 0          | 7 nodes (shared cluster, app-http 18700; #535 delivery when the partition owner does not host the slice; 3 instances since #1495) |
| DurableTopicDeliveryForgeTest | 19000     | 19100          | 0          | 5 nodes (shared cluster, app-http 19200; #386 composed durable pub/sub path, Heavy) |
| DurableEntityForgeTest        | 24300     | 24320          | 0          | 5 nodes (shared cluster, app-http 24340, SWIM UDP 24400-24404; durable entities, Heavy). Moved off 19000, which it shared unregistered with the row above (#1627) |
| ClusterEventOwnerFailoverTest | 24210     | 24230          | 0          | 5 nodes (single method, app-http 24250, SWIM UDP 24310-24314; #1640 events survive the cluster-events owner and leader dying) |
| CoordinationSlopeInstrumentTest | 20000  | 20100          | 0          | 3 nodes (shared cluster, app-http 20200; #591 validates the coordination-load sampler against live endpoints) |
| MembershipChaosCycleTest      | 20500     | 20600          | 0          | 5 nodes (shared cluster, app-http 20700; #232 kill -> detect -> decommission -> heal, Heavy) |
| CoreAbsenceFenceOrderingTest  | 21000     | 21100          | 0          | 6 nodes (shared cluster, app-http 21200; #590 fence ordering, Heavy) |
| EmberAddNodeRoleLabelTest     | 21500     | 21600          | 0          | 3+2 nodes (shared cluster, app-http 21700; #590 addWorkerNode role-label guard, Heavy) |
| DurableEntityTimerDurabilityTest | 22000  | 22100          | 0          | 5 nodes (app-http 22200) — registered late: it bound these ports while absent from this table |
| NodeLifecyclePeriodicArmingForgeTest | 22050 | 22150        | 0          | 3 nodes (app-http 22250) — registered late, interleaved with the row above |
| EmberInstanceTagRoundTripTest | 22350     | 22450          | 0          | 3 nodes (app-http 22550) — registered late |
| MultiSourceCommunitySmokeTest | 22650     | 22750          | 0          | 5 core nodes initially (app-http 22850) — registered late |
| EmberSameIdentityRelaunchTest (aether/ember) | 23000-23300 scan | base+40 | 5 | 3 nodes + relaunch, app-http base+80; scans 100-port blocks from 23000 to 23300 for a free one (#1528/#1558 — below the 32768 ephemeral floor). Moved off 22000-23800, which overlapped the four rows above and the next one |
| Ember shared probed pool (aether/ember: EmberAmnesiacRestartTest, EmberColdStartSingleDialerTest, EmberHeldPartitionPublishTest, EmberIncarnationWiringTest, EmberPartitionedCoreSelfFenceTest, EmberKvBackupRestoreTest, EmberGenesisRecoveryTest, EmberWorkerDeadSeedTest, EmberClusterForeignAdmissionTest, EmberClusterPartialStartFailureTest, EmberClusterSwimStartFailureTest, EmberClusterHeldBackSwimStartFailureTest, EmberPacedBurstTest, EmberFollowerLinkLossKeepsLeaderTest, EmberTestPortsTest) | 1030-1830 scan | base+40 | 0 | 10 nodes (largest block is 10 slots, EmberPartitionedCoreSelfFenceTest, so the pool reaches 1939; mgmt base+40, app-http base+80, dead seed base+30; `EmberTestPorts.POOL_*`, step 50: the probe binds every port of a candidate, so a candidate overlapping a live cluster is skipped (two clusters at once is safe); the module runs these tests one after another; replaces ranges between 33700 and 52900, all inside the Linux ephemeral range 32768-60999) |
| AutoRollbackOnAllInstancesFailedTest (forge-tests) | 2000-2800 scan | base+40 | 0 | 6 nodes (app-http base+80; probed, step 100; moved off 42100-43900, which is inside the Linux ephemeral range 32768-60999) |
| ForgePortPreflightTest (forge-core) | 2910 | 2910 | 85 | 5 nodes (UDP only: a random base 2910-2995 whose 5 ports are probed and released; the band 2910-2999 is this test's alone; SWIM UDP 2910-2910 is a placeholder, the test binds nothing at +100; below the Linux ephemeral floor 32768) |
| HierarchyAuthorityAcceptanceTest (forge-tests) | 3000 | 3100 | 0 | 12 nodes (3 core nodes plus worker slots; mgmt base+100, app-http 3200; moved off 35100, inside the Linux ephemeral range 32768-60999) |
| HierarchicalDecisionReplayTest (forge-tests) | 3220 | 3320 | 0 | 12 nodes (3 core nodes plus worker slots; mgmt base+100, app-http 3420; moved off 35400, inside the Linux ephemeral range 32768-60999) |
| HierarchicalLeaderObservationGraceTest (forge-tests) | 3440 | 3540 | 0 | 12 nodes (3 core nodes plus worker slots; mgmt base+100, app-http 3640; moved off 35400, shared with the row above, inside the Linux ephemeral range 32768-60999) |
| HierarchicalWorkerRuntimeReplayTest (forge-tests) | 3660 | 3760 | 0 | 12 nodes (3 core nodes plus worker slots; mgmt base+100, app-http 3860; moved off 35700, inside the Linux ephemeral range 32768-60999) |
| HierarchicalGovernorConcurrentNominationTest (forge-tests) | 3880 | 3980 | 0 | 12 nodes (3 core nodes plus worker slots; mgmt base+100, app-http 4080; moved off 36300, inside the Linux ephemeral range 32768-60999) |
| HierarchicalWorkerReconnectTest (forge-tests) | 4100 | 4200 | 0 | 12 nodes (3 core nodes plus worker slots; mgmt base+100, app-http 4300; moved off 36600, inside the Linux ephemeral range 32768-60999) |
| HierarchicalCapacityFallbackTest (forge-tests) | 4320 | 4420 | 0 | 12 nodes (3 core nodes plus worker slots; mgmt base+100, app-http 4520; moved off 36900, inside the Linux ephemeral range 32768-60999) |
| StreamDefaultRfOwnerReplacementTest (forge-tests) | 4540 | 4640 | 0 | 7 nodes (app-http 4740; moved off 38400, inside the Linux ephemeral range 32768-60999) |
| StreamAckedRecordsOwnerKillTest (forge-tests) | 4760 | 4860 | 0 | 7 nodes (app-http 4960; moved off 38700, inside the Linux ephemeral range 32768-60999) |
| BlueprintSecurityOverrideClusterWideTest | 23500 | 23600    | 0          | 5 nodes (app-http 23700; 5 since #1495's floor, so three instances leave two non-hosting nodes for #1659) — registered late |
| LeaderTermFailoverTest        | 24000     | 24100          | 0          | 5 nodes (single method, app-http 24200; #1527/#1559 leader term strictly increases across two leader kills, and the re-election pre-latch fires). Moved off 37400, which is inside the Linux ephemeral range 32768-60999 |
| CommunityObservabilityForgeTest | 12800 | 12950 | 0 | 6 nodes (3 cores + 3 workers, single method, app-http 13300, SWIM UDP 12900-12905; #1652 community route and lifecycle events) |
| StreamConfirmationFactorOwnerKillTest | 14500 | 14600 | 0 | 5 nodes (app-http 14700; #1564 RF 3 / CF 2: an acked record survives the owner's loss) |
| StreamConfirmationEqualsFactorAvailabilityTest | 16500 | 16600 | 0 | 3 nodes (app-http 16700; #1564 RF 3 / CF 3: one lost core refuses writes until a replacement is placed) |

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

CI enforces step 3 at the pull request's merge ref: `tools/check-test-ports.py` fails when two rows overlap. It compares
per protocol: cluster/QUIC and SWIM (cluster + 100) are UDP; management and app-http are TCP. It also lists fixed ports
in `*/src/test` that no row covers. Keep the Notes column parseable: it must state `N nodes`, and may state
`app-http N` or `app-http base+N`, and `SWIM UDP a-b`.

## This table is not exhaustive

The rows above are the registered ranges, and the non-overlap rule holds only among them. As of #1558,
more test classes bind fixed ports without a row here: in `forge-tests`, for example
`DurableEntityForgeTest` (19000, which it shares with `DurableTopicDeliveryForgeTest`),
`PostRestartSlowRejoinDeficitFillProbeTest` (19500), `SurvivorLivenessAfterGracefulKillTest` (24500),
`TerminatedWorkerGhostTest` (24800), `ScheduledSingleFireHostingTest` (25500), the `Hierarchical*` classes
(28400-32600 here; the ones above 32768 moved to the rows above), `DurableProjectionRebuildForgeTest` (31600) and `ApiKeyFullRestartForgeTest` (31900). The
`aether/ember` tests also scan 25700-27500, 27700-29500 and 29700-31500. Before choosing a range, search
every five-digit literal under `src/test` rather than trusting this table, and prefer bases below 32768,
the start of the Linux ephemeral range.

## Reserved Ranges

- 12500+ / 12600+: Allocated to SliceVersionLifecycleTest (app-http 12700)
- 13000+ / 13100+: Allocated to StreamFanoutConsumerTest (app-http 13200)
- 13500+ / 13600+: Allocated to StreamCrashDurabilityTest (app-http 13700)
- 14000+ / 14100+: Allocated to StreamOwnerFailoverTest (app-http 14200)
- 15000+ / 15100+: Allocated to StreamOwnerFailoverPinnedTest (app-http 15200)
- 15500+ / 15700+: Allocated to ForgeProxyApiKeyForgeTest (SWIM 15600, app-http 15800)
- 16000+ / 16100+: Allocated to MultiPartitionStreamTest (app-http 16200)
- 17000+ / 17100+: Allocated to StreamPublishReshuffleTest (app-http 17200)
- 17500+ / 17600+: Allocated to MultiPartitionCrashDurabilityTest (app-http 17700)
- 18000+ / 18100+: Allocated to DeclarativeStreamConsumerTest (app-http 18200) — moved off 14000, which it
  silently shared with StreamOwnerFailoverTest while absent from this table (#535)
- 18500+ / 18600+: Allocated to DeclarativeConsumerPlacementTest (app-http 18700)
- 19000+ / 19100+: Allocated to DurableTopicDeliveryForgeTest (app-http 19200) — the #386 composed
  durable pub/sub path
- 19500+: Reserved for future tests