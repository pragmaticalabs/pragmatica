# Existing TODO Items in Codebase

This document catalogs all existing TODO comments found in the codebase that should be addressed during development.

## SliceKVSchema.java

**Location**: `slice/src/main/java/org/pragmatica/aether/slice/kvstore/SliceKVSchema.java:16`

```java
// TODO: rework as StructuredKey and provide StructuredPattern
```

**Context**: The current KV schema uses string-based keys. This should be refactored to use structured keys with pattern
matching capabilities.

**Priority**: Medium
**Impact**: Architecture improvement, type safety

## SliceState.java

**Location**: `slice/src/main/java/org/pragmatica/aether/slice/SliceState.java:15`

```java
// TODO: rework timeout handling, perhaps use existing values as defaults
```

**Context**: Timeout handling in slice state transitions needs improvement.

**Priority**: Low
**Impact**: Configuration management

## NodeDeploymentManager.java

### TODO 1: Structured Keys

**Location**: `node/src/main/java/org/pragmatica/aether/deployment/node/NodeDeploymentManager.java:69-71`

```java
// TODO: We're should use KVStore with dedicated structured keys. This, in turn, should
//       simplify matching patterns and destructuring keys into elements. Take further look into
//       SliceKVSchema, perhaps it can be a starting point for this.
```

**Context**: Current implementation uses string pattern matching for KV keys. Should migrate to structured key system.

**Priority**: Medium
**Impact**: Code clarity, performance, type safety

### TODO 2: Abrupt Stop Handling

**Location**: `node/src/main/java/org/pragmatica/aether/deployment/node/NodeDeploymentManager.java:109-112`

```java
// TODO: WARNING: unlike other notifications, removal may happen
//  not during normal operation but also during abrupt stop due to
//  lack of consensus. In this case slice might be active and we should
//  immediately stop it, unload and remove, ignoring errors.
```

**Context**: Need to handle abrupt consensus loss scenarios properly.

**Priority**: High
**Impact**: System stability, error recovery

### TODO 3: State Transition Rework

**Location**: `node/src/main/java/org/pragmatica/aether/deployment/node/NodeDeploymentManager.java:124`

```java
// TODO: we may need to rework it
```

**Context**: The `handleSliceStateUpdate` method may need refactoring.

**Priority**: Low
**Impact**: Code organization

### TODO 4: Move Timeouts to SliceStore

**Location**: `node/src/main/java/org/pragmatica/aether/deployment/node/NodeDeploymentManager.java:152-156`

```java
// TODO: move timeouts to SliceStore.
//  Timeouts should be inserted as close to actual operations as possible.
//  Otherwise they don't cancel the operation itself, but subsequent transformations.
//  This may result in incorrect handling of subsequent operations as they will
//  be executed only when original operation is completed.
```

**Context**: Timeout handling architecture needs improvement for proper operation cancellation.

**Priority**: High
**Impact**: System reliability, proper resource cleanup

### TODO 5: Slice Readiness Notification

**Location**: `node/src/main/java/org/pragmatica/aether/deployment/node/NodeDeploymentManager.java:171`

```java
// TODO: we may need to send notification about slice readiness.
```

**Context**: Consider adding notifications when slices reach LOADED state.

**Priority**: Low
**Impact**: Monitoring, observability

### TODO 6: Slice Activation Notification

**Location**: `node/src/main/java/org/pragmatica/aether/deployment/node/NodeDeploymentManager.java:191`

```java
// TODO: We may want to generate a notification for slice activation.
```

**Context**: Consider adding notifications when slices become ACTIVE.

**Priority**: Low
**Impact**: Monitoring, observability

### TODO 7: Link with Consensus (CRITICAL)

**Location**: `node/src/main/java/org/pragmatica/aether/deployment/node/NodeDeploymentManager.java:234`

```java
// TODO: link with consensus
```

**Context**: The `updateSliceState` method currently simulates consensus updates locally. This is the critical missing
piece for actual cluster operation.

**Priority**: Critical
**Impact**: Core functionality - cluster won't work without this

### TODO 8: Immutable MessageRouter API

**Location**: `node/src/main/java/org/pragmatica/aether/deployment/node/NodeDeploymentManager.java:293`

```java
// TODO: rework for immutable MessageRouter builder API
```

**Context**: Current implementation uses mutable MessageRouter. Should migrate to immutable builder pattern.

**Priority**: Low
**Impact**: API design, immutability

## Priority Classification

### Critical Priority

1. **Link with consensus (NodeDeploymentManager:234)** - Core functionality blocker
2. **Abrupt stop handling (NodeDeploymentManager:109-112)** - System stability

### High Priority

3. **Move timeouts to SliceStore (NodeDeploymentManager:152-156)** - Reliability issue

### Medium Priority

4. **Structured keys refactoring (SliceKVSchema:16, NodeDeploymentManager:69-71)** - Architecture improvement

### Low Priority

5. **Timeout configuration (SliceState:15)** - Configuration management
6. **State transition rework (NodeDeploymentManager:124)** - Code organization
7. **Notification improvements (NodeDeploymentManager:171, 191)** - Observability enhancements
8. **Immutable MessageRouter (NodeDeploymentManager:293)** - API design

## Relationship to New Development

Many of these TODOs align with the planned architecture improvements:

- **Consensus linking** will be addressed by ClusterDeploymentManager implementation
- **Structured keys** align with blueprint schema extensions
- **Notification improvements** relate to MCP event system design
- **Timeout handling** fits with overall reliability improvements