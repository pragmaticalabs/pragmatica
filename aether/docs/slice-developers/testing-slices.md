# End-to-End Testing Guide

Test Aether clusters with **Forge** — an in-process, in-JVM multi-node cluster — for realistic
integration testing without Docker.

## Overview

The E2E testing framework (module `aether/forge/forge-tests`) provides:
- **`EmberCluster`**: an in-process 3-7 node cluster (real consensus, real streams, real
  deployment FSM), driven by plain HTTP calls to each node's management port — no container
  wrapper API
- **`forge.sh`**: the local gate that runs it (`smoke` / `ci` / `full` / a single class)
- **JUnit 5 + Awaitility + AssertJ**, `@TestInstance(PER_CLASS)` with `@BeforeAll`/`@AfterAll` so
  a whole test class shares one cluster instead of paying formation cost per test

## Prerequisites

- Maven with JDK 25
- Built project JARs (`mvn install -DskipTests`, or `./build.sh` first)
- No Docker required — Forge clusters run as in-process nodes on `localhost`

## Quick Start

```java
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.awaitility.Awaitility.await;

@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MyE2ETest {
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(240);
    private EmberCluster cluster;

    @BeforeAll
    void setUp() {
        cluster = emberCluster(3, 5500, 5600, 5400, "my");

        cluster.start()
               .await()
               .onFailure(cause -> { throw new AssertionError("Cluster start failed: " + cause.message()); });

        await().atMost(WAIT_TIMEOUT).until(() -> cluster.currentLeader().isPresent());
    }

    @AfterAll
    void tearDown() {
        if (cluster != null) cluster.stop().await();
    }

    @Test
    void clusterFormsQuorum() {
        assertThat(cluster.nodeCount()).isEqualTo(3);
        assertThat(cluster.currentLeader().isPresent()).isTrue();
    }
}
```

`emberCluster(initialSize, basePort, baseMgmtPort, baseAppHttpPort, nodeIdPrefix)` allocates
non-overlapping ports per test class — pick a distinct base-port block per class so parallel
Maven modules don't collide (existing classes use 5050/5150/5250 for formation, 5500/5600/5400
for deployment, etc. — grep the test directory for the next free block).

## `EmberCluster` — API surface

Everything is either a lifecycle/topology call directly on `EmberCluster`, or a plain
`java.net.http.HttpRequest` against a node's management port. There is no per-node wrapper object
with typed methods for each management endpoint.

### Lifecycle and topology

```java
cluster.start();                    // Promise<Unit> — start all nodes
cluster.start(heldBackNodeIds);     // start with some nodes deliberately not joining yet
cluster.stop();                     // Promise<Unit> — stop all nodes
cluster.addNode();                  // Promise<NodeId> — grow the cluster by one
cluster.addNode(labels);            // with role/label metadata
cluster.addWorkerNode();            // worker-role node (non-quorum-participating)
cluster.killNode(nodeIdStr);        // Promise<Unit> — hard-stop a node
cluster.killNode(nodeIdStr, graceful); // graceful drain-then-stop
cluster.blackhole(nodeIdStr);       // Promise<Unit> — simulate a network partition (no kill)
```

### Status and node access

```java
cluster.nodeCount();                // int
cluster.currentLeader();            // Option<String> — leader node id
cluster.status();                   // ClusterStatus(List<NodeStatus> nodes, String leaderId)
cluster.allNodes();                 // List<AetherNode> — in-process node handles
cluster.getNode(nodeIdStr);         // Option<AetherNode>
cluster.getLeaderManagementPort();  // Option<Integer>
cluster.nodeMetrics();              // List<NodeMetrics>
cluster.slicesStatus();             // List<SliceStatus>
```

Each `NodeStatus` carries `id`, `port`, `mgmtPort`, `state`, `isLeader` — use `mgmtPort()` to
build the HTTP calls below.

### Talking to a node — plain HTTP, not a wrapper

```java
private final HttpOperations http = jdkHttpOperations(); // org.pragmatica.http.JdkHttpOperations

private String httpGet(int port, String path) {
    var request = HttpRequest.newBuilder()
                             .uri(URI.create("http://localhost:" + port + path))
                             .GET()
                             .timeout(Duration.ofSeconds(5))
                             .build();
    return http.sendString(request).await().map(HttpResult::body).or("{\"error\":\"request failed\"}");
}
```

Common management paths (all under `/api/`, prefix required): `/api/health`, `/api/nodes/status`,
`/api/metrics`, `/api/blueprints/{id}` (GET/POST/DELETE for deploy/scale/undeploy), `/api/deploy`
and `/api/deploy/{id}/{promote,complete,rollback}` for staged deployments. Read the actual route
definitions under `aether/node/.../api/routes/` or `aether/forge/forge-api/.../api/` rather than
trusting a doc snapshot of the full path list — these evolve.

## Test Suite Overview

The forge-tests module currently has **34 test classes** (~100 `@Test` methods) — formation,
deployment, chaos/partition, streams, durable-entity, controller/scaling, and diagnostic-probe
coverage. Rather than a table here (which drifts the moment a class is added, as the previous
version of this doc did), the current, authoritative list is:

```bash
ls aether/forge/forge-tests/src/test/java/org/pragmatica/aether/forge/*Test.java
```

Two JUnit tags partition the suite: `@Tag("Smoke")` (fast, run by default via `./forge.sh`) and
`@Tag("Heavy")` (slower probes, excluded from the default CI run).

## Test Categories

### Cluster formation

```java
@Test
void threeNodeCluster_formsQuorum_andElectsLeader() {
    assertThat(cluster.nodeCount()).isEqualTo(3);
    assertThat(cluster.currentLeader().isPresent()).isTrue();
}

@Test
void cluster_nodesVisibleToAllMembers() {
    for (var node : cluster.status().nodes()) {
        var health = httpGet(node.mgmtPort(), "/api/health");
        assertThat(health).contains("\"connectedPeers\":2").contains("\"nodeCount\":3");
    }
}
```

### Node failure and recovery

```java
@Test
void cluster_survivesNodeKill_reelectsIfLeader() {
    var oldLeader = cluster.currentLeader().unwrap();
    cluster.killNode(oldLeader).await();

    await().atMost(WAIT_TIMEOUT).until(() -> cluster.currentLeader().isPresent()
                                              && !cluster.currentLeader().unwrap().equals(oldLeader));
}
```

### Network partition (no process kill)

```java
@Test
void cluster_toleratesBlackhole_ofMinorityNode() {
    cluster.blackhole("node-2").await();
    // minority side loses quorum participation; majority keeps serving — see guarantees.md §3
}
```

### Slice deployment

Deployment goes through the blueprint HTTP API on the leader's management port — there is no
`.deploy(artifact, instances)` convenience method:

```java
@Test
void cluster_deploysSlice_acrossNodes() {
    var leaderPort = cluster.getLeaderManagementPort().or(cluster.status().nodes().getFirst().mgmtPort());
    // POST /api/blueprints/{id} with the blueprint body, then poll /api/nodes/status
    // for SliceState.ACTIVE across the target instance count — see SliceDeploymentTest.java
    // for the full request/response shapes and polling pattern.
}
```

For the exact request bodies and polling idioms, read a current example test directly —
`aether/forge/forge-tests/src/test/java/org/pragmatica/aether/forge/SliceDeploymentTest.java` —
rather than copying a paraphrase here; the blueprint/deploy JSON shape is exactly the kind of
detail that drifts.

## Running Tests

```bash
./forge.sh                # smoke — formation + deployment/invocation + one stream path (default)
./forge.sh ci              # everything except @Tag("Heavy") — what CI runs
./forge.sh full            # every forge test, Heavy probes included (slow)
./forge.sh ClusterFormationTest   # a single class

# Equivalent raw Maven (forge.sh sets the required phase/profile/module scope for you):
mvn verify -Pwith-e2e -pl aether/forge/forge-tests -Dgroups=Smoke
```

Use `verify`, not `test` — `forge-tests` runs via the Failsafe plugin, which only enforces
failures at the `verify` phase; stopping at `test`/`integration-test` can print `BUILD SUCCESS`
over failing tests. `forge.sh` also hard-scopes `-pl aether/forge/forge-tests` deliberately, to
keep `HetznerCloudIT` (a separate module that provisions a real paid server when `HCLOUD_TOKEN` is
set) out of the reactor — don't widen that scope without reading `forge.sh`'s own comments first.

### CI

Forge runs in CI as the `ci` mode above (everything except `@Tag("Heavy")`); check
`.github/workflows/` for the current trigger conditions rather than assuming a specific branch
rule, which is a CI-config detail this doc shouldn't duplicate.

## Best Practices

### Test isolation and shared cluster cost

Formation of a real multi-node cluster is not free — that's why classes use
`@TestInstance(PER_CLASS)` with `@BeforeAll`/`@AfterAll` (one cluster per class, shared across its
`@Test` methods) rather than `@BeforeEach`/`@AfterEach` (one cluster per test method). If a test
leaves cluster state dirty for the next test in the class, clean it up explicitly in
`@BeforeEach` (see `SliceDeploymentTest`'s `cleanUp()` for the pattern), don't reach for
per-test cluster isolation as the fix — it defeats the point of the shared-cluster design.

### Sequential execution

`@Execution(ExecutionMode.SAME_THREAD)` on every forge test class, plus running the whole module
without forking, avoids port and resource contention between concurrently-running in-process
clusters:

```xml
<!-- aether/forge/forge-tests/pom.xml -->
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-failsafe-plugin</artifactId>
</plugin>
```

### Timeouts

```java
private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(240);
private static final Duration POLL_INTERVAL = Duration.ofMillis(500);

await().atMost(WAIT_TIMEOUT)
       .pollInterval(POLL_INTERVAL)
       .until(() -> cluster.currentLeader().isPresent());
```

240s is generous on purpose — forge clusters share the CI machine with everything else in the
build; a tight timeout produces flaky failures that are a scheduling artifact, not a real defect.

## Troubleshooting

### Quorum not forming

1. Check for port collisions with another test class or a locally-running Forge/Ember instance —
   `emberCluster(...)`'s base ports must not overlap another live cluster on the same machine.
2. Increase the `await()` timeout before assuming a real regression; formation time varies with
   machine load.
3. Run the single class directly (`./forge.sh ClusterFormationTest`) to rule out cross-class
   interference from a shared Maven JVM.

### Flaky tests

1. Add explicit `await()` waits for state changes instead of a fixed `Thread.sleep`.
2. Widen the poll interval/timeout rather than tightening it — see the timeout note above.
3. Check whether the failure is `@Tag("Heavy")`-class resource contention; `./forge.sh smoke` runs
   a much smaller, more stable set than `./forge.sh full`.
