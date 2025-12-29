# Getting Started with Aether

This guide walks you through running your first Aether cluster and deploying a slice.

## Prerequisites

- **Java 25** (required - Aether uses modern JVM features)
- **Maven 3.9+**
- **~10 minutes**

## Quick Start

### 1. Build the Project

```bash
git clone https://github.com/pragmatica-lite/aether.git
cd aether
mvn package -DskipTests
```

### 2. Start Forge (Easiest Option)

Forge is the fastest way to see Aether in action:

```bash
./script/aether-forge.sh
```

Open [http://localhost:8888](http://localhost:8888) to see the dashboard.

Forge runs a simulated cluster with:
- 5 nodes (configurable via `CLUSTER_SIZE`)
- Load generator
- Real-time metrics visualization
- Chaos testing controls

### 3. Or Start a Real Cluster

Start a 3-node cluster (each in a separate terminal):

```bash
# Terminal 1
./script/aether-node.sh --node-id=node-1 --port=8091 \
  --peers=localhost:8091,localhost:8092,localhost:8093

# Terminal 2
./script/aether-node.sh --node-id=node-2 --port=8092 \
  --peers=localhost:8091,localhost:8092,localhost:8093

# Terminal 3
./script/aether-node.sh --node-id=node-3 --port=8093 \
  --peers=localhost:8091,localhost:8092,localhost:8093
```

### 4. Use the CLI

Check cluster status:

```bash
./script/aether.sh --connect localhost:8080 status
./script/aether.sh --connect localhost:8080 nodes
```

Or use interactive mode:

```bash
./script/aether.sh --connect localhost:8080

aether> status
aether> nodes
aether> metrics
aether> exit
```

## Project Structure

```
aether/
├── script/                    # Run scripts
│   ├── aether.sh             # CLI tool
│   ├── aether-node.sh        # Node runner
│   └── aether-forge.sh       # Forge simulator
├── cli/                       # CLI module
├── node/                      # Node runtime
├── forge/                     # Forge simulator
├── slice/                     # Slice management
├── slice-api/                 # Slice API (for slice developers)
├── cluster/                   # Consensus layer
├── example-slice/             # Example slice implementation
└── examples/order-demo/       # Complete demo application
```

## Creating Your First Slice

### 1. Add Dependencies

```xml
<dependency>
    <groupId>org.pragmatica-lite.aether</groupId>
    <artifactId>slice-api</artifactId>
    <version>0.6.2</version>
</dependency>
<dependency>
    <groupId>org.pragmatica-lite</groupId>
    <artifactId>core</artifactId>
    <version>0.9.0</version>
</dependency>
```

### 2. Implement the Slice Interface

```java
package com.example.hello;

import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;

public class HelloWorld implements Slice {

    @Override
    public List<SliceMethod<?, ?>> methods() {
        return List.of(
            SliceMethod.sliceMethod(
                "greet",
                GreetRequest.class,
                GreetResponse.class,
                this::greet
            )
        );
    }

    private Promise<GreetResponse> greet(GreetRequest request) {
        var name = request.name() != null ? request.name() : "World";
        return Promise.success(new GreetResponse("Hello, " + name + "!"));
    }

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

    public record GreetRequest(String name) {}
    public record GreetResponse(String message) {}
}
```

### 3. Configure the JAR Manifest

```xml
<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-jar-plugin</artifactId>
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
```

### 4. Deploy

```bash
mvn install
./script/aether.sh deploy com.example:hello-world:1.0.0
```

## Running the Order Demo

The `examples/order-demo` contains a complete multi-slice application:

```bash
cd examples/order-demo
./run.sh
```

This demonstrates:
- Multiple interdependent slices
- Inter-slice communication
- Scaling behavior

## Next Steps

- [CLI Reference](cli-reference.md) - All CLI commands
- [Forge Guide](forge-guide.md) - Load and chaos testing
- [Slice Developer Guide](../slice-developer-guide.md) - Writing slices
- [Scaling Guide](scaling.md) - Auto-scaling configuration
- [Architecture Overview](../architecture-overview.md) - How it works
