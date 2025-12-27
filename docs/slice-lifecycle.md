# Slice Lifecycle

Although main focus of this document is the Slice lifecycle, it inevitably touches several other components.

## Glossary

- Consensus Key-Value Store (or just KV-store) - underlying state machine for cluster consensus protocol.
- Artifact - the loadable Slice binary
- Repository - external artifact repository. Implied some kind of Maven-compatible repository.
- SliceStore - local Slice storage
- EndpointRegistry - registry with all active endpoints (also handles endpoint invocation)
- RouteRegistry - registry for HTTP routes declared by slices, stored in KV-store for cluster-wide consistency
- NodeDeploymentManager - synchronization component, which triggers necessary actions to make actual state of slices
  match desired state as it is described in the consensus KV store.

## High Level Life Cycle

<details>
@startuml sliceStates

hide empty description
[*] --> LOAD
LOAD --> LOADING
LOADING -> LOADED
LOADING --> FAILED
LOADED --> ACTIVATE
ACTIVATE --> ACTIVATING
ACTIVATING --> ACTIVE
ACTIVATING --> FAILED
ACTIVE --> DEACTIVATE
DEACTIVATE -up-> DEACTIVATING
DEACTIVATE --> FAILED
DEACTIVATING -up-> LOADED
LOADED --> UNLOAD
FAILED --> UNLOAD
UNLOAD --> UNLOADING
UNLOADING --> [*] : unloadingDone
@enduml

</details>

## Sequence Diagram

<details>
@startuml interaction
participant Initiator as initiator
participant "Consensus\nKV-store" as kvstore
participant "Deployment\nManager" as dm
participant "Slice\nStore" as ss
participant "Route\nRegistry" as rr
participant "Endpoint\nRegistry" as er
entity "Slice" as slice

group "Slice Loading"

initiator -> kvstore : slice-node-key+LOAD
kvstore -> dm : ValuePut LOAD
dm -> kvstore : slice-node-key+LOADING
dm -> ss : Load Slice

alt Success

ss --> dm : Slice Load Success
dm --> kvstore : slice-node-key+LOADED
kvstore -> initiator : ValuePut LOADED

else Failure

ss --> dm : Slice Load Failure
dm --> kvstore : slice-node-key+FAILED
kvstore -> initiator : ValuePut FAILED

end
end

group "Slice Activation"

initiator -> kvstore : slice-node-key+ACTIVATE
dm -> kvstore : slice-node-key+ACTIVATING
dm -> ss : Activate Slice
ss -> slice : Start

alt Success

slice --> ss : Started Successfully
ss --> dm : Activation Success
dm --> kvstore : slice-node-key+ACTIVE + (multiple) slice-instance-endpoint-key+AVAILABLE
dm -> rr : Register routes from slice.routes()
rr -> kvstore : Put route-keys (idempotent)
kvstore --> initiator : Value Put ACTIVE

else Failure

slice --> ss : Start Failure
ss --> dm : Activation Failure
dm --> kvstore : slice-node-key+FAILED
kvstore --> initiator : Value Put FAILED

end
end

group "Slice Deactivation"

initiator -> kvstore : slice-node-key+DEACTIVATE
kvstore -> dm : Value Put DEACTIVATE
dm -> kvstore : slice-node-key+DEACTIVATING + (multiple) remove slice-instance-endpoint-key
dm -> dm : Check if last instance
alt Last Instance
dm -> rr : Unregister routes for artifact
rr -> kvstore : Remove route-keys
end
dm -> ss : Deactivate Slice
ss -> slice : Stop

alt Success

slice --> ss : Stopped Successfully
ss --> dm : Deactivation Success
dm --> kvstore : slice-node-key+LOADED
kvstore --> initiator : Value Put LOADED

else Failure

slice --> ss : Stop Failure
ss --> dm : Deactivation Failure
dm --> kvstore : slice-node-key+FAILED
kvstore --> initiator : Value Put FAILED

end
end

group "Slice Unloading"

initiator -> kvstore : slice-node-key+UNLOAD
kvstore -> dm : Value Put UNLOAD
dm -> kvstore : slice-node-key+UNLOADING
dm -> ss : Unload Slice
ss -> dm : Slice Unloaded
dm -> kvstore : delete slice-node-key
kvstore -> initiator : Value Remove slice-node-key

end

group "Endpoint Registration (all nodes)"
kvstore -> er : Value Put (multiple) slice-instance-endpoint-key
end
group "Endpoint Deregistration (all nodes)"
kvstore -> er : Value Remove (multiple) slice-instance-endpoint-key
end

group "Route Registration (all nodes)"
kvstore -> rr : Value Put route-key
note right: Routes are stored in KV-store\nand replicated to all nodes
end
group "Route Deregistration (all nodes)"
kvstore -> rr : Value Remove route-key
end

@enduml

</details>

## Route Self-Registration

Slices can declare HTTP routes via the `routes()` method returning `List<SliceRoute>`. Routes are managed automatically during slice lifecycle:

### Registration (on Activation)

When a slice instance is activated successfully:

1. NodeDeploymentManager calls `slice.routes()` to get declared routes
2. Each route is registered to RouteRegistry with the artifact identifier
3. RouteRegistry stores routes in the consensus KV-store
4. All nodes receive the route via ValuePut notification
5. Routes are **idempotent** - multiple instances of the same slice can register the same route

### Deregistration (on Deactivation)

When a slice instance is deactivated:

1. NodeDeploymentManager checks if this is the **last active instance** of the artifact
2. If other instances exist (on any node), routes remain registered
3. If this is the last instance, all routes for the artifact are removed from KV-store
4. All nodes receive the removal via ValueRemove notification

This ensures:
- Routes remain available as long as at least one instance can handle requests
- No dangling routes pointing to non-existent slice instances
- Cluster-wide consistency via consensus KV-store

