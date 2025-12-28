# Getting Started with Aether

This guide walks you through creating your first Aether project, from zero to a running distributed application.

## Prerequisites

- **Java 25** (required - Aether uses modern JVM features)
- **Maven 3.9+**
- **~15 minutes**

## Option 1: Quick Start with CLI (Recommended)

### Install Aether CLI

```bash
# Download and install
curl -sSL https://aether.pragmatica.dev/install.sh | bash

# Or with Maven
mvn dependency:get -Dartifact=org.pragmatica-lite.aether:cli:0.6.1
alias aether="java -jar ~/.m2/repository/org/pragmatica-lite/aether/cli/0.6.1/cli-0.6.1.jar"
```

### Create a New Project

```bash
# Create project structure
aether init my-app --group-id com.example

cd my-app
```

This creates:

```
my-app/
├── pom.xml                      # Parent POM with Aether dependencies
├── slices/
│   └── hello-world/
│       ├── pom.xml
│       └── src/main/java/com/example/hello/
│           └── HelloWorld.java  # Your first slice
├── app/
│   ├── pom.xml
│   └── src/main/java/com/example/
│       └── Application.java     # Application entry point
└── forge.yml                    # Forge testing configuration
```

### Build and Run

```bash
# Build all modules
mvn clean install

# Start a single-node cluster
aether start

# In another terminal, deploy your slice
aether deploy hello-world

# Test it
curl http://localhost:8080/hello?name=World
# Response: {"message": "Hello, World!"}
```

### Scale It

```bash
# Add more nodes
aether node add --port 4041
aether node add --port 4042

# Check cluster status
aether status
# Nodes: 3, Slices: 1 (hello-world: 1 instance)

# Scale the slice
aether scale hello-world --instances 3

# Aether distributes instances across nodes
aether status
# Nodes: 3, Slices: 1 (hello-world: 3 instances)
```

## Option 2: Manual Setup

### Project Structure

Create a multi-module Maven project:

```xml
<!-- pom.xml (parent) -->
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.example</groupId>
    <artifactId>my-app</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <packaging>pom</packaging>

    <properties>
        <maven.compiler.source>25</maven.compiler.source>
        <maven.compiler.target>25</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <aether.version>0.6.1</aether.version>
        <pragmatica.version>0.9.0</pragmatica.version>
    </properties>

    <modules>
        <module>slices/hello-world</module>
        <module>app</module>
    </modules>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.pragmatica-lite.aether</groupId>
                <artifactId>slice-api</artifactId>
                <version>${aether.version}</version>
            </dependency>
            <dependency>
                <groupId>org.pragmatica-lite</groupId>
                <artifactId>core</artifactId>
                <version>${pragmatica.version}</version>
            </dependency>
        </dependencies>
    </dependencyManagement>
</project>
```

### Create Your First Slice

```xml
<!-- slices/hello-world/pom.xml -->
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.example</groupId>
        <artifactId>my-app</artifactId>
        <version>1.0.0-SNAPSHOT</version>
        <relativePath>../../pom.xml</relativePath>
    </parent>

    <artifactId>hello-world</artifactId>

    <dependencies>
        <dependency>
            <groupId>org.pragmatica-lite.aether</groupId>
            <artifactId>slice-api</artifactId>
        </dependency>
        <dependency>
            <groupId>org.pragmatica-lite</groupId>
            <artifactId>core</artifactId>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-jar-plugin</artifactId>
                <version>3.3.0</version>
                <configuration>
                    <archive>
                        <manifestEntries>
                            <Slice-Class>com.example.hello.HelloWorld</Slice-Class>
                            <Slice-Artifact>${project.groupId}:${project.artifactId}:${project.version}</Slice-Artifact>
                        </manifestEntries>
                    </archive>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

```java
// slices/hello-world/src/main/java/com/example/hello/HelloWorld.java
package com.example.hello;

import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.aether.slice.SliceRoute;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;

public class HelloWorld implements Slice {

    // Define the slice's entry points
    @Override
    public List<SliceMethod<?, ?>> methods() {
        return List.of(
            SliceMethod.sliceMethod("greet", GreetRequest.class, GreetResponse.class, this::greet)
        );
    }

    // Define HTTP routes (optional)
    @Override
    public List<SliceRoute> routes() {
        return List.of(
            SliceRoute.get("/hello", "greet")
        );
    }

    // The actual business logic
    private Promise<GreetResponse> greet(GreetRequest request) {
        var name = request.name() != null ? request.name() : "World";
        return Promise.success(new GreetResponse("Hello, " + name + "!"));
    }

    // Lifecycle methods
    @Override
    public Promise<Unit> start() {
        System.out.println("HelloWorld slice started");
        return Promise.success(Unit.unit());
    }

    @Override
    public Promise<Unit> stop() {
        System.out.println("HelloWorld slice stopped");
        return Promise.success(Unit.unit());
    }

    // Request/Response records
    public record GreetRequest(String name) {}
    public record GreetResponse(String message) {}
}
```

### Create the Application Module

```xml
<!-- app/pom.xml -->
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.example</groupId>
        <artifactId>my-app</artifactId>
        <version>1.0.0-SNAPSHOT</version>
        <relativePath>../pom.xml</relativePath>
    </parent>

    <artifactId>app</artifactId>

    <dependencies>
        <dependency>
            <groupId>org.pragmatica-lite.aether</groupId>
            <artifactId>node</artifactId>
            <version>${aether.version}</version>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-shade-plugin</artifactId>
                <version>3.5.1</version>
                <executions>
                    <execution>
                        <phase>package</phase>
                        <goals><goal>shade</goal></goals>
                        <configuration>
                            <transformers>
                                <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                                    <mainClass>com.example.Application</mainClass>
                                </transformer>
                            </transformers>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

```java
// app/src/main/java/com/example/Application.java
package com.example;

import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.node.AetherNodeConfig;
import org.pragmatica.cluster.net.NodeId;
import org.pragmatica.cluster.net.NodeInfo;

import java.util.List;

import static org.pragmatica.cluster.net.NodeId.nodeId;
import static org.pragmatica.cluster.net.NodeInfo.nodeInfo;
import static org.pragmatica.net.NodeAddress.nodeAddress;

public class Application {
    public static void main(String[] args) {
        // Define cluster topology
        var nodes = List.of(
            nodeInfo(nodeId("node-1"), nodeAddress("localhost", 4040)),
            nodeInfo(nodeId("node-2"), nodeAddress("localhost", 4041)),
            nodeInfo(nodeId("node-3"), nodeAddress("localhost", 4042))
        );

        // Determine which node we are (from command line or environment)
        var myNodeId = nodeId(System.getProperty("node.id", "node-1"));
        var myPort = Integer.parseInt(System.getProperty("node.port", "4040"));

        // Create configuration
        var config = AetherNodeConfig.builder()
            .self(myNodeId)
            .port(myPort)
            .coreNodes(nodes)
            .managementPort(myPort + 1000)  // Management API on port + 1000
            .httpRouterPort(8080)           // HTTP router for slice routes
            .build();

        // Create and start the node
        var node = AetherNode.aetherNode(config);

        node.start()
            .onSuccess(_ -> System.out.println("Aether node " + myNodeId + " started"))
            .onFailure(cause -> {
                System.err.println("Failed to start: " + cause.message());
                System.exit(1);
            });

        // Keep running until interrupted
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            node.stop().await();
            System.out.println("Node stopped");
        }));
    }
}
```

### Build and Run

```bash
# Build everything
mvn clean install

# Start node 1
java -Dnode.id=node-1 -Dnode.port=4040 -jar app/target/app-1.0.0-SNAPSHOT.jar

# In separate terminals, start nodes 2 and 3
java -Dnode.id=node-2 -Dnode.port=4041 -jar app/target/app-1.0.0-SNAPSHOT.jar
java -Dnode.id=node-3 -Dnode.port=4042 -jar app/target/app-1.0.0-SNAPSHOT.jar
```

### Deploy Your Slice

```bash
# Copy slice JAR to local Maven repository (it's already there after mvn install)

# Use management API to deploy
curl -X POST http://localhost:5040/deploy \
  -H "Content-Type: application/json" \
  -d '{"artifact": "com.example:hello-world:1.0.0-SNAPSHOT", "instances": 1}'

# Test the slice
curl "http://localhost:8080/hello?name=Developer"
# {"message": "Hello, Developer!"}
```

## Project Structure Best Practices

### Recommended Layout

```
my-app/
├── pom.xml                          # Parent POM
├── domain/                          # Shared domain types
│   ├── pom.xml
│   └── src/main/java/.../
│       ├── OrderId.java            # Value objects
│       ├── Money.java
│       └── OrderStatus.java
├── slices/
│   ├── place-order/                # Use case slice
│   │   ├── pom.xml
│   │   └── src/main/java/.../
│   │       └── PlaceOrder.java
│   ├── inventory-service/          # Service slice
│   │   ├── pom.xml
│   │   └── src/main/java/.../
│   │       └── InventoryService.java
│   └── pricing-service/            # Service slice
│       ├── pom.xml
│       └── src/main/java/.../
│           └── PricingService.java
├── app/                            # Application entry point
│   ├── pom.xml
│   └── src/main/java/.../
│       └── Application.java
└── forge.yml                       # Testing configuration
```

### Slice Dependencies

Slices can depend on other slices. Aether handles the wiring:

```java
// PlaceOrder depends on InventoryService and PricingService
@Slice
public interface PlaceOrder {
    Promise<OrderResult> execute(PlaceOrderRequest request);

    // Factory receives dependencies - Aether injects them
    static PlaceOrder placeOrder(InventoryService inventory, PricingService pricing) {
        return new PlaceOrderImpl(inventory, pricing);
    }
}
```

In `pom.xml`, declare the dependency with `provided` scope:

```xml
<dependency>
    <groupId>com.example</groupId>
    <artifactId>inventory-service</artifactId>
    <classifier>api</classifier>
    <scope>provided</scope>
</dependency>
```

Aether resolves these at runtime, whether the dependent slice is local or remote.

## Testing with Forge

Forge is Aether's built-in testing tool for chaos engineering and load testing:

```bash
# Start Forge with your application
aether forge start

# Open dashboard
open http://localhost:8080

# From the dashboard you can:
# - Generate load against your slices
# - Kill nodes and watch recovery
# - Trigger rolling restarts
# - Monitor metrics in real-time
```

See [Forge Testing Guide](forge-guide.md) for details.

## Next Steps

- [Slice Lifecycle](../slice-lifecycle.md) - Understand slice states and transitions
- [Migration Guide](migration-guide.md) - Extract slices from existing code
- [Scaling Guide](scaling.md) - Configure scaling behavior
- [CLI Reference](cli-reference.md) - All CLI commands
