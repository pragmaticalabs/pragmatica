# MCP Integration Architecture

## Overview

The Model Context Protocol (MCP) integration provides external management and monitoring capabilities for the Aether
cluster. It follows a three-layer architecture ensuring perfect parity between CLI and MCP interfaces.

## Architecture Principles

### Three-Layer Design

```
┌─────────────────┐  ┌─────────────────┐
│   CLI Module    │  │   MCP Module    │
│                 │  │                 │  
│ Command Parser  │  │ JSON-RPC        │
│ Argument Validation│ │ WebSocket/HTTP  │ 
│ Output Formatting│  │ Protocol Handler│
└─────────────────┘  └─────────────────┘
         │                     │
         └─────────┬───────────┘
                   │
         ┌─────────────────┐
         │ Command Handlers│ ← Single Source of Truth
         │                 │
         │ BlueprintHandler│
         │ SliceHandler    │  
         │ ClusterHandler  │
         │ MonitorHandler  │
         └─────────────────┘
                   │
         ┌─────────────────┐
         │ Core Components │
         │                 │
         │ ClusterDeployMgr│
         │ NodeDeployMgr   │
         │ EndpointRegistry│
         │ SliceStore      │
         └─────────────────┘
```

### Design Principles

1. **Perfect Parity**: CLI and MCP provide identical functionality
2. **Unified Logic**: Single command handler implementation
3. **Protocol Agnostic**: Core handlers independent of transport
4. **Consistency**: No feature discrepancies between interfaces
5. **Single Agent**: Only one MCP agent connected cluster-wide

## MCP Server Architecture

### Multi-Node Deployment

```java
interface MCPServer {
    // Runs on every node, but only one agent can connect cluster-wide
    Promise<AgentConnection> connectAgent(AgentId agentId);
    void disconnectAgent(AgentId agentId);
    
    // Agent lock mechanism prevents multiple connections
    boolean acquireAgentLock(AgentId agentId, Duration ttl);
    void releaseAgentLock(AgentId agentId);
}
```

### Agent Connection Management

**Agent Lock Schema**:

```
mcp/agent-lock/{agent-id} → {
  "node-id": "node-1",
  "timestamp": 1234567890, 
  "ttl": 30000
}
```

**Connection Process**:

1. Agent attempts connection to any node
2. Node tries to acquire cluster-wide agent lock in KV-Store
3. If successful, establishes connection and starts TTL refresh
4. If lock held by another node, connection rejected
5. Lock automatically expires if TTL not refreshed

### Protocol Implementation

**JSON-RPC over WebSocket/HTTP**:

```java
interface MCPProtocol {
    // Standard JSON-RPC 2.0 methods
    Response handleRequest(String method, JsonObject params);
    
    // Streaming support for events
    void subscribeToEvents(EventFilter filter);
    void publishEvent(ClusterEvent event);
}
```

**Supported Transports**:

- **WebSocket**: Real-time bidirectional communication
- **HTTP**: Request/response for simple operations
- **Server-Sent Events**: One-way event streaming

## Command Handler Design

### Handler Interface

```java
interface CommandHandler<Request, Response> {
    Promise<Response> handle(Request request);
    
    // Validation and authorization
    Result<Request> validateRequest(Request request);
    boolean isAuthorized(Request request, SecurityContext context);
}
```

### Blueprint Management

```java
interface BlueprintHandler {
    // CRUD operations
    Promise<Unit> publishBlueprint(PublishBlueprintRequest request);
    Promise<Blueprint> getBlueprint(GetBlueprintRequest request);
    Promise<List<BlueprintSummary>> listBlueprints(ListBlueprintsRequest request);
    Promise<Unit> updateBlueprint(UpdateBlueprintRequest request);
    Promise<Unit> deleteBlueprint(DeleteBlueprintRequest request);
    
    // Validation
    Promise<ValidationResult> validateBlueprint(ValidateBlueprintRequest request);
}
```

### Slice Management

```java
interface SliceHandler {
    // Slice lifecycle control
    Promise<Unit> activateSlice(ActivateSliceRequest request);
    Promise<Unit> deactivateSlice(DeactivateSliceRequest request);
    Promise<Unit> restartSlice(RestartSliceRequest request);
    
    // Status and monitoring
    Promise<List<SliceStatus>> listSlices(ListSlicesRequest request);
    Promise<SliceDetails> getSliceDetails(GetSliceDetailsRequest request);
}
```

### Cluster Management

```java
interface ClusterHandler {
    // Cluster status
    Promise<ClusterStatus> getClusterStatus(GetClusterStatusRequest request);
    Promise<List<NodeStatus>> listNodes(ListNodesRequest request);
    Promise<NodeDetails> getNodeDetails(GetNodeDetailsRequest request);
    
    // Operational commands
    Promise<Unit> triggerReconciliation(TriggerReconciliationRequest request);
    Promise<Unit> rebalanceCluster(RebalanceClusterRequest request);
}
```

### Monitoring and Events

```java
interface MonitorHandler {
    // Event streaming
    EventStream<ClusterEvent> subscribeToEvents(EventSubscriptionRequest request);
    
    // Metrics
    Promise<MetricsSnapshot> getMetrics(GetMetricsRequest request);
    Promise<List<AlertStatus>> getAlerts(GetAlertsRequest request);
}
```

## CLI Interface

### Command Structure

```bash
# Blueprint management
aether blueprint publish <file>
aether blueprint get <name>
aether blueprint list
aether blueprint update <name> <file>
aether blueprint delete <name>
aether blueprint validate <file>

# Slice management  
aether slice list [--node <node-id>]
aether slice status <artifact>
aether slice activate <artifact> [--instances <count>]
aether slice deactivate <artifact>
aether slice restart <artifact>

# Cluster management
aether cluster status
aether cluster nodes
aether cluster reconcile
aether cluster rebalance

# Monitoring
aether monitor events [--filter <filter>]
aether monitor metrics
aether monitor alerts
```

### CLI Implementation Pattern

```java
// CLI command delegates to same handlers as MCP
@Command(name = "blueprint")
class BlueprintCommand {
    
    @Inject
    private BlueprintHandler blueprintHandler;
    
    @Command(name = "publish")
    public void publishBlueprint(@Option("file") String file) {
        var request = new PublishBlueprintRequest(parseFile(file));
        
        blueprintHandler.handle(request)
                       .onSuccess(result -> System.out.println("Blueprint published successfully"))
                       .onFailure(cause -> System.err.println("Failed: " + cause.message()))
                       .await();
    }
}
```

## MCP Methods

### Blueprint Operations

```json
// Publish blueprint
{
  "method": "blueprint.publish",
  "params": {
    "name": "production-v1",
    "blueprint": {
      "slices": [
        {
          "artifact": "org.example:web-service:1.2.0",
          "instances": 3
        }
      ]
    }
  }
}

// Response
{
  "result": {
    "status": "success",
    "message": "Blueprint published successfully"
  }
}
```

### Slice Operations

```json
// Get slice status
{
  "method": "slice.status", 
  "params": {
    "artifact": "org.example:web-service:1.2.0"
  }
}

// Response
{
  "result": {
    "artifact": "org.example:web-service:1.2.0",
    "desired_instances": 3,
    "actual_instances": 3,
    "instances": [
      {
        "node_id": "node-1", 
        "state": "ACTIVE",
        "endpoints": ["http://10.0.1.1:8080/api"]
      }
    ]
  }
}
```

### Event Streaming

```json
// Subscribe to events
{
  "method": "monitor.subscribe",
  "params": {
    "filter": {
      "types": ["slice_activated", "node_added", "blueprint_updated"],
      "artifacts": ["org.example:*"]
    }
  }
}

// Event notification
{
  "method": "event", 
  "params": {
    "type": "slice_activated",
    "timestamp": 1234567890,
    "data": {
      "artifact": "org.example:web-service:1.2.0",
      "node_id": "node-1",
      "instance": 1
    }
  }
}
```

## Event System

### Event Types

```java
sealed interface ClusterEvent {
    // Slice lifecycle events
    record SliceActivated(Artifact artifact, NodeId nodeId, int instance) implements ClusterEvent {}
    record SliceDeactivated(Artifact artifact, NodeId nodeId, int instance) implements ClusterEvent {}
    record SliceFailed(Artifact artifact, NodeId nodeId, int instance, Cause cause) implements ClusterEvent {}
    
    // Node events
    record NodeAdded(NodeId nodeId, NodeStatus status) implements ClusterEvent {}
    record NodeRemoved(NodeId nodeId) implements ClusterEvent {}
    record NodeDown(NodeId nodeId) implements ClusterEvent {}
    
    // Blueprint events  
    record BlueprintPublished(String name, Blueprint blueprint) implements ClusterEvent {}
    record BlueprintUpdated(String name, Blueprint oldBlueprint, Blueprint newBlueprint) implements ClusterEvent {}
    record BlueprintDeleted(String name, Blueprint blueprint) implements ClusterEvent {}
    
    // Cluster events
    record LeaderChanged(Option<NodeId> oldLeader, Option<NodeId> newLeader) implements ClusterEvent {}
    record ReconciliationStarted(NodeId leader) implements ClusterEvent {}
    record ReconciliationCompleted(NodeId leader, ReconciliationResult result) implements ClusterEvent {}
}
```

### Event Publishing

```java
interface EventPublisher {
    void publishEvent(ClusterEvent event);
    void publishMetric(MetricEvent metric);
    void publishAlert(AlertEvent alert);
}

// Integration with MCP server
MCPServer.subscribeToEvents(filter)
         .forEach(event -> sendToAgent(event));
```

## Security and Authorization

### Authentication

```java
interface SecurityContext {
    String getAgentId();
    Set<String> getPermissions(); 
    boolean hasPermission(String permission);
}

// Permission-based access control
enum Permission {
    BLUEPRINT_READ, BLUEPRINT_WRITE,
    SLICE_READ, SLICE_CONTROL,
    CLUSTER_READ, CLUSTER_MANAGE,
    MONITOR_READ, MONITOR_SUBSCRIBE
}
```

### Request Validation

```java
interface RequestValidator<T> {
    Result<T> validate(T request, SecurityContext context);
}

// Example validation
class PublishBlueprintValidator implements RequestValidator<PublishBlueprintRequest> {
    public Result<PublishBlueprintRequest> validate(PublishBlueprintRequest request, SecurityContext context) {
        return Result.all(
            validateBlueprintFormat(request.blueprint()),
            validatePermissions(context, BLUEPRINT_WRITE),
            validateResourceConstraints(request.blueprint())
        ).map(_ -> request);
    }
}
```

## Implementation Roadmap

### Phase 1: Foundation

- Command handler interfaces and basic implementations
- CLI framework with blueprint commands
- MCP server skeleton with JSON-RPC support
- Agent connection management

### Phase 2: Core Functionality

- Complete blueprint and slice management
- Event streaming infrastructure
- WebSocket transport implementation
- Integration with ClusterDeploymentManager

### Phase 3: Advanced Features

- Comprehensive monitoring and metrics
- Advanced filtering and subscriptions
- Security and authorization framework
- Performance optimizations

## Testing Strategy

### Unit Tests

- Command handler logic
- Request/response serialization
- Validation and authorization
- Event publishing and filtering

### Integration Tests

- CLI end-to-end scenarios
- MCP protocol compliance
- Agent connection lifecycle
- Cross-interface parity validation

### Test Infrastructure

```java
// Mock cluster components for isolated testing
// Test harness for CLI command execution
// WebSocket test client for MCP protocol testing
// Event verification utilities
```

## Open Questions

1. **Authentication**: Should we implement token-based auth, certificates, or integrate with external auth providers?

2. **Rate Limiting**: How should we handle high-frequency MCP requests or event subscriptions?

3. **Protocol Versioning**: How should we handle MCP protocol evolution and backward compatibility?

4. **Monitoring Scope**: What level of detail should monitoring events provide?

5. **Error Handling**: How should we handle partial failures in batch operations?