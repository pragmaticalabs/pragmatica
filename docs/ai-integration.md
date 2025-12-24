# AI Integration Architecture

## Overview

Aether uses a **layered autonomy** architecture for AI integration. The key principle: the cluster must survive and operate effectively with only the lowest layer (decision tree). Upper layers add intelligence but are not dependencies.

## Layered Architecture

```
┌─────────────────────────────────────────────────────────┐
│  Layer 4: User                                          │
│  Frequency: On-demand                                   │
│  Role: Strategic decisions, overrides, teaching         │
│  Interface: CLI, Management API, Dashboard              │
├─────────────────────────────────────────────────────────┤
│  Layer 3: LLM (Claude, GPT, etc.)                       │
│  Frequency: Minutes to hours                            │
│  Role: Complex reasoning, anomaly analysis, planning    │
│  Interface: Management API (direct, no MCP)             │
├─────────────────────────────────────────────────────────┤
│  Layer 2: SLM (Small Language Model)                    │
│  Frequency: Seconds to minutes                          │
│  Role: Pattern recognition, simple decisions            │
│  Interface: Management API, Metrics stream              │
├─────────────────────────────────────────────────────────┤
│  Layer 1: Lizard Brain (DecisionTreeController)         │
│  Frequency: Milliseconds (every 1 second)               │
│  Role: Immediate reactions, scaling rules, health       │
│  Interface: Internal (runs in-process)                  │
│                                                         │
│  *** REQUIRED: Cluster MUST survive with only this ***  │
└─────────────────────────────────────────────────────────┘
```

## Design Principles

### 1. Layer 1 is Mandatory

The DecisionTreeController (lizard brain) is the only required component. It provides:

- CPU-based autoscaling
- Health monitoring
- Basic failure recovery
- Immediate reactions (< 1 second)

All other layers are optional enhancements.

### 2. Graceful Degradation

If a layer becomes unavailable, the system falls back to lower layers:

```
LLM unavailable → SLM handles
SLM unavailable → Decision tree handles
Decision tree always runs → Cluster survives
```

### 3. Escalation Flow

Problems flow upward, decisions flow downward:

```
Problems:    Layer 1 → Layer 2 → Layer 3 → Layer 4
             (detected) (analyzed) (reasoned) (reviewed)

Decisions:   Layer 4 → Layer 3 → Layer 2 → Layer 1
             (strategic) (tactical) (operational) (immediate)
```

### 4. Each Layer is Replaceable

Upper layers can be:
- Added without restart
- Removed without impact
- Swapped for alternatives
- Configured dynamically
- Taught through feedback

### 5. No MCP

Agents interact directly with the Management API. This decision was made because:

- Bidirectional MCP communication adds complexity
- Agents work better as narrow specialists
- Direct API access is more reliable
- MCP abstraction doesn't add proportional value

## Layer Responsibilities

### Layer 1: Lizard Brain (DecisionTreeController)

**Always running. No external dependencies.**

| Responsibility | Trigger | Response Time |
|---------------|---------|---------------|
| CPU autoscaling | CPU > 80% | < 1 second |
| Health checks | Node unresponsive | < 2 seconds |
| Slice restart | Slice crashed | Immediate |
| Load balancing | Endpoint updates | < 1 second |

**Implementation**: `DecisionTreeController` in `node/` module.

### Layer 2: SLM (Future)

**Local model. Fast inference. Pattern learning.**

| Responsibility | Trigger | Response Time |
|---------------|---------|---------------|
| Traffic pattern detection | Metrics history | 2-5 seconds |
| Anomaly detection | Unusual patterns | 2-5 seconds |
| Predictive hints | Historical data | 5-10 seconds |
| Simple reasoning | Escalated from L1 | 2-5 seconds |

**Implementation**: Planned. Will use local inference (Ollama, llama.cpp).

### Layer 3: LLM (Future)

**Cloud model. Complex reasoning. Strategic planning.**

| Responsibility | Trigger | Response Time |
|---------------|---------|---------------|
| Complex deployments | User request | 30-60 seconds |
| Multi-cloud decisions | Cost/latency analysis | Minutes |
| Anomaly explanation | Escalated from L2 | 30-60 seconds |
| Capacity planning | Trend analysis | Minutes to hours |

**Implementation**: Planned. Direct API to Claude/GPT via Management API.

### Layer 4: User

**Human oversight. Strategic direction. Teaching.**

| Responsibility | Trigger | Response Time |
|---------------|---------|---------------|
| Override decisions | User action | Immediate |
| Configure rules | User action | Immediate |
| Approve deployments | Escalated from L3 | Minutes to hours |
| Teach the system | Feedback on decisions | Async |

**Implementation**: CLI, Management API, future Dashboard.

## Integration Points

### Management API

All layers (except L1) interact through the Management API:

```
POST /api/v1/deploy     - Deploy slices
POST /api/v1/scale      - Scale slices
POST /api/v1/undeploy   - Remove slices
GET  /api/v1/status     - Cluster status
GET  /api/v1/metrics    - Current metrics
GET  /api/v1/nodes      - Node list
GET  /api/v1/slices     - Slice list
```

### Metrics Stream

For real-time monitoring (L2/L3):

```
GET /api/v1/metrics/stream  - SSE stream of metrics
```

### Decision Feedback

For teaching the system:

```
POST /api/v1/feedback   - Approve/reject decision
POST /api/v1/rules      - Add/modify decision rules
```

## Agent Design Guidelines

When building agents for Aether management:

1. **Narrow specialization** - One agent per concern (scaling, deployment, monitoring)
2. **Stateless operation** - Query cluster state, don't maintain shadow state
3. **Idempotent actions** - Same request → same result
4. **Graceful failure** - If agent fails, cluster continues with lower layers
5. **Clear escalation** - Know when to escalate to higher layers

## Migration from MCP

The MCP module is deprecated. Previous MCP-based integrations should:

1. Replace MCP calls with direct Management API calls
2. Remove bidirectional communication complexity
3. Treat the agent as a client, not a peer
4. Handle failures gracefully (cluster doesn't depend on agent)

See `docs/archive/mcp-integration.md` for historical reference.

## Future Work

### Phase 1: Agent API Documentation
- Document all Management API endpoints
- Provide agent integration examples
- Create agent development guide

### Phase 2: SLM Integration
- Integrate local model (Ollama)
- Implement pattern learning
- Add anomaly detection

### Phase 3: LLM Integration
- Claude/GPT API integration
- Complex reasoning workflows
- Multi-cloud decision support

### Phase 4: Teaching Interface
- Feedback collection
- Rule learning from corrections
- Decision explanation
