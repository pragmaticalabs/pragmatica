# End-to-End Testing Guide

Test Aether clusters with Testcontainers for realistic integration testing.

## Overview

The E2E testing framework provides:
- **AetherNodeContainer**: Testcontainer wrapper for single nodes
- **AetherCluster**: Multi-node cluster management
- **Built-in test categories**: Formation, deployment, chaos, rolling updates

## Prerequisites

- Docker running locally
- Maven with JDK 25
- Built project JARs (`mvn package -DskipTests`)

## Quick Start

```java
class MyE2ETest {
    private static final Path PROJECT_ROOT = Path.of("..");
    private AetherCluster cluster;

    @BeforeEach
    void setUp() {
        cluster = AetherCluster.create(3, PROJECT_ROOT);
    }

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            cluster.close();
        }
    }

    @Test
    void clusterFormsQuorum() {
        cluster.start();
        cluster.awaitQuorum();

        assertThat(cluster.runningNodeCount()).isEqualTo(3);
        assertThat(cluster.anyNode().getHealth()).contains("\"status\":\"healthy\"");
    }
}
```

## AetherNodeContainer

Wrapper for individual Aether node containers.

### Creation

```java
// Single node
var node = AetherNodeContainer.aetherNode("node-1", projectRoot);

// Node with peers
var peers = "node-1:node-1:8090,node-2:node-2:8090";
var node = AetherNodeContainer.aetherNode("node-1", projectRoot, peers);
```

### Lifecycle

```java
node.start();           // Start container
node.stop();            // Stop container
node.isRunning();       // Check if running
```

### API Methods

```java
node.getHealth();       // GET /health
node.getStatus();       // GET /status
node.getNodes();        // GET /nodes
node.getSlices();       // GET /slices
node.getMetrics();      // GET /metrics

node.deploy(artifact, instances);  // POST /deploy
node.scale(artifact, instances);   // POST /scale
node.undeploy(artifact);           // POST /undeploy
```

### Generic HTTP

```java
String response = node.get("/custom-endpoint");
String response = node.post("/custom-endpoint", jsonBody);
```

### Port Access

```java
node.managementPort();  // Mapped management port (8080)
node.clusterPort();     // Mapped cluster port (8090)
node.managementUrl();   // Full URL: http://localhost:<port>
```

## AetherCluster

Manages multi-node clusters for testing.

### Creation

```java
// Create 3-node cluster
var cluster = AetherCluster.create(3, projectRoot);

// Create 5-node cluster for higher availability
var cluster = AetherCluster.create(5, projectRoot);
```

### Lifecycle

```java
cluster.start();           // Start all nodes sequentially
cluster.startParallel();   // Start all nodes in parallel
cluster.close();           // Stop all nodes
```

### Waiting for State

```java
cluster.awaitQuorum();              // Wait for quorum
cluster.awaitAllHealthy();          // Wait for all healthy
cluster.awaitNodeCount(3);          // Wait for node count
```

### Node Access

```java
cluster.anyNode();             // Get any running node
cluster.node("node-2");        // Get specific node
cluster.nodes();               // Get all nodes
cluster.leader();              // Get leader (Optional)
```

### Chaos Operations

```java
cluster.killNode("node-2");           // Stop a node
cluster.restartNode("node-2");        // Restart a node
cluster.rollingRestart(Duration.ofSeconds(5));  // Rolling restart
```

### Status

```java
cluster.runningNodeCount();    // Number of running nodes
cluster.size();                // Total cluster size
```

## Test Categories

### Cluster Formation Tests

Test cluster startup and quorum:

```java
@Test
void threeNodeCluster_formsQuorum() {
    cluster.start();
    cluster.awaitQuorum();

    assertThat(cluster.runningNodeCount()).isEqualTo(3);
    assertThat(cluster.leader()).isPresent();
}

@Test
void cluster_nodesVisibleToAllMembers() {
    cluster.start();
    cluster.awaitQuorum();

    for (var node : cluster.nodes()) {
        var nodes = node.getNodes();
        assertThat(nodes).contains("node-1", "node-2", "node-3");
    }
}
```

### Node Failure Tests

Test resilience to node failures:

```java
@Test
void cluster_maintainsQuorum_afterOneNodeFailure() {
    cluster.start();
    cluster.awaitQuorum();

    cluster.killNode("node-2");
    cluster.awaitNodeCount(2);

    // Cluster should still function
    var health = cluster.anyNode().getHealth();
    assertThat(health).contains("\"quorum\":true");
}

@Test
void cluster_recovers_afterNodeRestart() {
    cluster.start();
    cluster.awaitQuorum();

    cluster.killNode("node-2");
    cluster.restartNode("node-2");
    cluster.awaitNodeCount(3);

    assertThat(cluster.runningNodeCount()).isEqualTo(3);
}
```

### Slice Deployment Tests

Test slice lifecycle:

```java
@Test
void cluster_deploysSlice_acrossNodes() {
    cluster.start();
    cluster.awaitQuorum();

    var result = cluster.anyNode().deploy("org.example:test-slice:1.0.0", 3);

    assertThat(result).contains("\"status\":\"deployed\"");

    // Verify slices running
    var slices = cluster.anyNode().getSlices();
    assertThat(slices).contains("test-slice");
}

@Test
void cluster_scalesSlice_correctly() {
    cluster.start();
    cluster.awaitQuorum();

    cluster.anyNode().deploy("org.example:test-slice:1.0.0", 1);
    cluster.anyNode().scale("org.example:test-slice:1.0.0", 3);

    // Verify scaled
    var slices = cluster.anyNode().getSlices();
    assertThat(slices).contains("\"instances\":3");
}
```

### Chaos Tests

Test cluster behavior under failure:

```java
@Test
void cluster_rebalances_afterLeaderFailure() {
    cluster.start();
    cluster.awaitQuorum();

    var oldLeader = cluster.leader().orElseThrow();
    cluster.killNode(oldLeader.nodeId());
    cluster.awaitQuorum();

    // New leader should be elected
    var newLeader = cluster.leader().orElseThrow();
    assertThat(newLeader.nodeId()).isNotEqualTo(oldLeader.nodeId());
}

@Test
void cluster_survives_rollingRestart() {
    cluster.start();
    cluster.awaitQuorum();

    cluster.rollingRestart(Duration.ofSeconds(5));

    // Cluster should still be healthy
    cluster.awaitAllHealthy();
    assertThat(cluster.runningNodeCount()).isEqualTo(3);
}
```

### Rolling Update Tests

Test rolling update functionality:

```java
@Test
void rollingUpdate_shiftsTraffic_gradually() {
    cluster.start();
    cluster.awaitQuorum();

    // Deploy initial version
    cluster.anyNode().deploy("org.example:my-slice:1.0.0", 3);

    // Start rolling update
    var result = cluster.anyNode().post("/rolling-update/start", """
        {
          "artifactBase": "org.example:my-slice",
          "version": "2.0.0",
          "instances": 3
        }
        """);

    assertThat(result).contains("\"state\":\"DEPLOYING\"");

    // Adjust routing
    var updateId = extractUpdateId(result);
    cluster.anyNode().post("/rolling-update/" + updateId + "/routing",
        "{\"routing\": \"1:1\"}");

    // Complete
    cluster.anyNode().post("/rolling-update/" + updateId + "/complete", "{}");
}
```

## Running Tests

### Run All E2E Tests

```bash
mvn test -pl e2e-tests
```

### Run Specific Test Class

```bash
mvn test -pl e2e-tests -Dtest=ClusterFormationE2ETest
```

### Run Specific Test Method

```bash
mvn test -pl e2e-tests -Dtest=ClusterFormationE2ETest#threeNodeCluster_formsQuorum
```

### CI Configuration

E2E tests run on:
- Push to `main` branch
- Pull requests with `[e2e]` tag

```yaml
# .github/workflows/ci.yml
e2e-tests:
  runs-on: ubuntu-latest
  steps:
    - uses: actions/checkout@v4
    - name: Build
      run: mvn package -DskipTests
    - name: E2E Tests
      run: mvn test -pl e2e-tests
```

## Best Practices

### Test Isolation

Each test should create and destroy its own cluster:

```java
@BeforeEach
void setUp() {
    cluster = AetherCluster.create(3, PROJECT_ROOT);
}

@AfterEach
void tearDown() {
    cluster.close();  // Always cleanup
}
```

### Timeouts

Use reasonable timeouts for async operations:

```java
// Default quorum timeout: 60 seconds
cluster.awaitQuorum();

// Custom await with Awaitility
await().atMost(Duration.ofSeconds(30))
       .pollInterval(Duration.ofSeconds(2))
       .until(() -> condition);
```

### Resource Management

- Use `try-with-resources` for cluster management
- Stop containers even on test failure
- Clean up Docker networks

```java
try (var cluster = AetherCluster.create(3, projectRoot)) {
    cluster.start();
    // ... test code
}  // Automatically cleaned up
```

### Parallel Test Execution

E2E tests should NOT run in parallel due to Docker resource contention:

```xml
<!-- pom.xml -->
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <configuration>
        <forkCount>1</forkCount>
        <reuseForks>false</reuseForks>
    </configuration>
</plugin>
```

## Troubleshooting

### Container Won't Start

1. Check Docker is running: `docker ps`
2. Check image builds: `docker build -f docker/aether-node/Dockerfile .`
3. Check for port conflicts

### Quorum Not Forming

1. Increase timeout: tests may need more time
2. Check container logs: `docker logs <container>`
3. Verify network connectivity between containers

### Flaky Tests

1. Add explicit waits for state changes
2. Increase polling intervals
3. Check for resource contention

### Debug Container

```java
// Enable container logs in test output
node.withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("container")));
```
