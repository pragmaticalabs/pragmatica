# Cluster Module

Rabia consensus algorithm implementation with modular architecture.

## Overview

Provides a highly modular implementation of the [Rabia consensus algorithm](https://dl.acm.org/doi/10.1145/3477132.3483582), a CFT (crash-fault-tolerant) distributed consensus protocol. Unlike RAFT or Paxos, Rabia does not rely on a persistent event log, making it simpler to implement and use.

### Core Components

- **Consensus Engine** - Orchestrates the consensus process with batch command tracking and Promise-based API
- **State Machine** - Pluggable component for processing commands after consensus, with snapshot support for recovery
- **Persistence Layer** - Pluggable storage for bootstrapping and recovery (in-memory default)
- **Message Router** - Targeted message delivery between nodes using the Message Bus pattern
- **Leader Manager** - Deterministic leader selection for cluster-wide operations

### Protocol Flow

1. **Propose** - Node proposes a batch of commands
2. **Round 1 Vote** - Nodes vote on the proposal
3. **Round 2 Vote** - Refinement based on round 1 majority (may be skipped via fast path)
4. **Decision** - With f+1 votes, nodes commit or use coin flip

Batch deduplication ensures correctness when multiple nodes submit identical commands concurrently.

## Dependencies

- `pragmatica-lite-consensus`
- `pragmatica-lite-messaging`
- `pragmatica-lite-core`
