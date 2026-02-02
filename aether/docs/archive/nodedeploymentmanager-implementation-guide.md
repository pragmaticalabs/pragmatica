# NodeDeploymentManager Implementation Guide

## Current State

The NodeDeploymentManager has a complete skeleton implementation that's commented out (lines 47-276). The structure is
good and follows the correct event-driven pattern, but needs these updates:

1. Uncomment the implementation
2. Update to use AetherKey/AetherValue (not strings)
3. Remove configure() pattern
4. Add @MessageReceiver annotations
5. Fix consensus integration
6. Update state transition handling

## Step-by-Step Implementation

### Step 1: Add @MessageReceiver Methods to Interface

**Location**: Lines 21-22 (after SliceDeployment record)

```java
public interface NodeDeploymentManager {
    record SliceDeployment(SliceNodeKey key, SliceState state, long timestamp) {}

    // ADD THESE:
    @MessageReceiver
    void onValuePut(ValuePut<AetherKey, AetherValue> valuePut);

    @MessageReceiver
    void onValueRemove(ValueRemove<AetherKey, AetherValue> valueRemove);

    @MessageReceiver
    void onQuorumStateChange(QuorumStateNotification quorumStateNotification);

    // ... rest of interface
}
```

### Step 2: Fix onValuePut in ActiveNodeDeploymentState

**Location**: Lines 47-66 (currently commented)

**Current (commented) code**:

```java
//public void onValuePut(ValuePut<?, ?> valuePut) {
//    var keyString = valuePut.cause().key().toString();
//    if (!pattern.matcher(keyString).matches()) {
//        return;
//    }
//    SliceNodeKey.sliceNodeKey(keyString)
//        .onSuccess(sliceKey -> {
//            if (valuePut.cause().value() instanceof String stateValue) {
//                handleSliceStateUpdate(sliceKey, stateValue);
//            }
//        })
```

**Change to**:

```java
@Override
public void onValuePut(ValuePut<AetherKey, AetherValue> valuePut) {
    var key = valuePut.cause().key();
    var value = valuePut.cause().value();

    // Only handle slice-node keys for THIS node
    if (!(key instanceof SliceNodeKey sliceKey) ||
        !sliceKey.nodeId().equals(self)) {
        return;
    }

    // Only handle slice-node values
    if (!(value instanceof AetherValue.SliceNodeValue sliceValue)) {
        log.warn("Unexpected value type for slice-node key: {}", value.getClass());
        return;
    }

    log.debug("ValuePut received for key: {}, state: {}", sliceKey, sliceValue.state());

    var timestamp = System.currentTimeMillis();
    var deployment = new SliceDeployment(sliceKey, sliceValue.state(), timestamp);
    deployments.put(sliceKey, deployment);

    // Process state transition
    processStateTransition(sliceKey, sliceValue.state());
}
```

**Key Changes**:

- No more string parsing - use AetherKey/AetherValue directly
- Pattern matching removed - just check if key is SliceNodeKey for this node
- Simplified - directly access state from SliceNodeValue

### Step 3: Fix onValueRemove in ActiveNodeDeploymentState

**Location**: Lines 68-92 (currently commented)

**Change to**:

```java
@Override
public void onValueRemove(ValueRemove<AetherKey, AetherValue> valueRemove) {
    var key = valueRemove.cause().key();

    // Only handle slice-node keys for THIS node
    if (!(key instanceof SliceNodeKey sliceKey) ||
        !sliceKey.nodeId().equals(self)) {
        return;
    }

    log.debug("ValueRemove received for key: {}", sliceKey);

    // WARNING: Removal may happen during abrupt stop due to lack of consensus.
    // In this case slice might be active and we should immediately stop it,
    // unload and remove, ignoring errors.
    var deployment = deployments.remove(sliceKey);
    if (deployment != null && deployment.state() == SliceState.ACTIVE) {
        // Force immediate cleanup
        sliceStore.deactivateSlice(sliceKey.artifact())
            .flatMap(_ -> sliceStore.unloadSlice(sliceKey.artifact()))
            .onFailure(cause -> log.error(
                "Failed to cleanup slice {} during abrupt removal: {}",
                sliceKey, cause.message()
            ));
    }
}
```

**Key Changes**:

- Direct key type checking (no string parsing)
- Handle abrupt removal case (deactivate + unload immediately)

### Step 4: Remove handleSliceStateUpdate Method

**Location**: Lines 95-107 (currently commented)

**Action**: DELETE - No longer needed. State updates handled directly in onValuePut.

### Step 5: Fix processStateTransition

**Location**: Lines 110-121 (currently commented)

**Change from old switch to modern switch expression**:

```java
private void processStateTransition(SliceNodeKey sliceKey, SliceState state) {
    switch (state) {
        case LOAD -> handleLoading(sliceKey);
        case LOADING -> {} // Transitional state, no action
        case LOADED -> handleLoaded(sliceKey);
        case ACTIVATE -> handleActivating(sliceKey);
        case ACTIVATING -> {} // Transitional state, no action
        case ACTIVE -> handleActive(sliceKey);
        case DEACTIVATE -> handleDeactivating(sliceKey);
        case DEACTIVATING -> {} // Transitional state, no action
        case FAILED -> handleFailed(sliceKey);
        case UNLOAD -> handleUnloading(sliceKey);
        case UNLOADING -> {} // Transitional state, no action
    }
}
```

**Key Changes**:

- Use new switch syntax (not arrow syntax in this case, traditional is fine)
- Handle LOAD trigger (not LOADING)
- Handle ACTIVATE trigger (not ACTIVATING)
- Handle DEACTIVATE trigger (not DEACTIVATING)
- Handle UNLOAD trigger (not UNLOADING)

### Step 6: Add KVStore Integration

**First, add KVStore to ActiveNodeDeploymentState constructor**:

```java
record ActiveNodeDeploymentState(
        NodeId self,
        SliceStore sliceStore,
        SliceActionConfig configuration,
        KVStore<AetherKey, AetherValue> kvStore,  // ADD THIS
        ConcurrentHashMap<SliceNodeKey, SliceDeployment> deployments
) implements NodeDeploymentState {
```

**Then fix updateSliceState** (Lines 202-211):

```java
private void updateSliceState(SliceNodeKey sliceKey, SliceState newState) {
    var timestamp = System.currentTimeMillis();
    var value = new AetherValue.SliceNodeValue(newState);

    // Write to consensus KV-Store
    kvStore.put(sliceKey, value)
        .onFailure(cause -> log.error(
            "Failed to update slice state for {}: {}",
            sliceKey, cause.message()
        ));
}
```

**Key Changes**:

- No more string concatenation
- Direct SliceNodeValue creation
- Write to consensus via kvStore.put()

### Step 7: Remove Pattern Field

**Location**: ActiveNodeDeploymentState record (line 34)

**Action**: Remove `Pattern pattern` field - no longer needed since we use instanceof checks

### Step 8: Fix Factory Method - Remove configure()

**Location**: Lines 215-276 (currently commented)

**Change to**:

```java
static NodeDeploymentManager nodeDeploymentManager(
        NodeId self,
        MessageRouter router,
        SliceStore sliceStore,
        KVStore<AetherKey, AetherValue> kvStore,  // ADD THIS
        SliceActionConfig configuration
) {
    record deploymentManager(
            NodeId self,
            SliceStore sliceStore,
            KVStore<AetherKey, AetherValue> kvStore,
            SliceActionConfig configuration,
            MessageRouter router,
            AtomicReference<NodeDeploymentState> state
    ) implements NodeDeploymentManager {

        @Override
        public void onValuePut(ValuePut<AetherKey, AetherValue> valuePut) {
            state.get().onValuePut(valuePut);
        }

        @Override
        public void onValueRemove(ValueRemove<AetherKey, AetherValue> valueRemove) {
            state.get().onValueRemove(valueRemove);
        }

        @Override
        public void onQuorumStateChange(QuorumStateNotification quorumStateNotification) {
            switch (quorumStateNotification) {
                case ESTABLISHED -> state().set(
                    new NodeDeploymentState.ActiveNodeDeploymentState(
                        self(),
                        sliceStore(),
                        configuration(),
                        kvStore(),  // ADD THIS
                        new ConcurrentHashMap<>()
                    )
                );
                case DISAPPEARED -> {
                    // Clean up any pending operations before going dormant
                    // Individual Promise timeouts will handle their own cleanup
                    state().set(new NodeDeploymentState.DormantNodeDeploymentState());
                }
            }
        }
    }

    return new deploymentManager(
            self,
            sliceStore,
            kvStore,
            configuration,
            router,
            new AtomicReference<>(new NodeDeploymentState.DormantNodeDeploymentState())
    );
}

// Convenience method
static NodeDeploymentManager nodeDeploymentManager(
        NodeId self,
        MessageRouter router,
        SliceStore sliceStore,
        KVStore<AetherKey, AetherValue> kvStore
) {
    return nodeDeploymentManager(self, router, sliceStore, kvStore,
                                 SliceActionConfig.defaultConfiguration());
}
```

**Key Changes**:

- Remove configure() call (lines 269-273)
- Remove buildPattern() method (lines 256-259)
- Add KVStore parameter
- Keep @MessageReceiver methods in interface
- Routes will be registered in centralized assembly

### Step 9: Add Missing Import

```java
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.message.MessageReceiver;
```

## Complete Implementation Checklist

- [ ] Add @MessageReceiver methods to interface
- [ ] Uncomment ActiveNodeDeploymentState.onValuePut
- [ ] Update onValuePut to use AetherKey/AetherValue (not strings)
- [ ] Uncomment and update onValueRemove
- [ ] Delete handleSliceStateUpdate method
- [ ] Update processStateTransition with correct triggers
- [ ] Uncomment and keep handleLoading through handleUnloading
- [ ] Add KVStore parameter to ActiveNodeDeploymentState
- [ ] Fix updateSliceState to write to KVStore
- [ ] Remove Pattern field from ActiveNodeDeploymentState
- [ ] Uncomment factory method
- [ ] Remove configure() calls from factory
- [ ] Add KVStore parameter to factory
- [ ] Remove buildPattern() method
- [ ] Add missing imports
- [ ] Compile and fix any errors
- [ ] Write integration test

## Testing Strategy

### Unit Test Example

```java
@Test
void onValuePut_activatesSlice_whenReceivingLoadCommand() {
    var nodeId = NodeId.nodeId("node-1");
    var artifact = Artifact.artifact("org.example:slice:1.0.0").unsafe();
    var sliceKey = new SliceNodeKey(artifact, nodeId);
    var sliceValue = new SliceNodeValue(SliceState.LOAD);

    // Create manager
    var manager = NodeDeploymentManager.nodeDeploymentManager(
        nodeId, router, sliceStore, kvStore
    );

    // Transition to active
    manager.onQuorumStateChange(QuorumStateNotification.ESTABLISHED);

    // Send LOAD command
    var valuePut = new ValuePut<>(new KVCommand.Put<>(sliceKey, sliceValue));
    manager.onValuePut(valuePut);

    // Verify SliceStore.loadSlice was called
    verify(sliceStore).loadSlice(artifact);
}
```

### Integration Test Outline

1. **Setup**: Create cluster with 3 nodes
2. **Establish quorum**: All nodes transition to Active
3. **Publish blueprint**: Write to `blueprints/test`
4. **ClusterDeploymentManager allocates**: Writes LOAD to `slices/node-1/artifact`
5. **NodeDeploymentManager reacts**: Calls SliceStore.loadSlice
6. **Verify state transitions**: LOAD → LOADING → LOADED → ACTIVATE → ACTIVATING → ACTIVE
7. **Cleanup**: DEACTIVATE → DEACTIVATING → LOADED → UNLOAD → UNLOADING → removed

## Notes

- Keep all timeout handling in SliceStore (as noted in TODOs)
- Pattern matching replaced with instanceof checks (simpler, type-safe)
- No string parsing - use structured keys/values directly
- State transitions happen via KV-Store writes (consensus-backed)
- Dormant state does nothing (safe default)
- Active state only processes keys for THIS node

## Next: ClusterDeploymentManager

Once NodeDeploymentManager is complete, implement ClusterDeploymentManager which:

1. Watches for blueprint changes
2. Allocates instances round-robin
3. Writes LOAD commands to slice-node-keys
4. Performs reconciliation

This is documented separately in implementation-status.md.
