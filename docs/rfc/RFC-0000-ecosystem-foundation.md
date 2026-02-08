---
RFC: 0000
Title: Ecosystem Foundation
Status: Approved
Author: Sergiy Yevtushenko
Created: 2026-01-15
Updated: 2026-01-15
Affects: [all projects]
---

## Summary

Foundation document establishing the ecosystem structure, RFC governance conventions, and their justifications. Unlike technical RFCs, this document describes the "why" and "how" of cross-project coordination.

## Motivation

### Current Pain Points

1. **Manual copy-paste** - Context shared by asking one agent, copying to another
2. **Split source of truth** - Some decisions exist in multiple places, sometimes contradicting
3. **Tracking in head** - User manually tracks which agent needs what from whom
4. **Waiting for dependencies** - Agents block on work from other projects (e.g., Maven publish)
5. **Misunderstanding propagates** - One agent's incorrect assumption affects downstream agents

### Goal

Make management of multiple agents working in parallel as effortless as possible by:
- Establishing single source of truth for cross-project decisions
- Defining explicit boundaries so agents know their responsibilities
- Creating discoverable documentation that agents can reference

## Design

### Ecosystem Structure

**Projects (6-7 total):**
- **pragmatica-lite** - Core functional programming library (Result, Promise, Option, Cause)
- **jbct-cli** - Java annotation processor for slice code generation
- **aether** - Distributed runtime consuming generated slices
- **coding-technology** - Documentation, standards, governance (proposals/ directory)
- **mailbox-mcp** - Agent coordination tool
- 2 non-coding projects (product/business context)

**Dependency relationships:**
- Explicit: Maven dependencies (pragmatica-lite → jbct-cli → aether)
- Implicit: Generated code contracts (jbct-cli output must match aether expectations)
- Circular: Some projects share context that isn't cleanly one-directional

**Most fragile interaction:** jbct-cli ↔ aether (generated code contracts)

### Governance Conventions

#### RFC Workflow

1. Author creates RFC as Draft in `coding-technology/proposals/`
2. Affected project agents review and propose changes
3. Multi-agent consensus reached (all affected agents approve)
4. PRs created in affected projects implementing/acknowledging RFC
5. User reviews and approves PRs
6. Only when **both PRs merged** → RFC status becomes "Approved"

**Justification:** Multi-agent consensus before user review ensures:
- Agents have already resolved conflicts before escalating to user
- User sees a coherent proposal, not fragmented opinions
- Reduces user's cognitive load in managing parallel agents

#### Numbering & Supersession

- Format: `RFC-NNNN-short-title.md` (4-digit, zero-padded)
- Supersession: Old RFC links to new RFC (traditional RFC approach)
- No versioning (RFC-0001-v2); instead create RFC-0002 that supersedes

**Justification:** Supersession over versioning because:
- Clear audit trail of design evolution
- Each RFC is immutable after approval
- Easier to reference specific decisions

### Key Decisions

| Decision | Choice | Justification |
|----------|--------|---------------|
| RFC format | Markdown | Human-readable, version-controlled, familiar |
| RFC location | coding-technology/proposals/ | Central repo, already has JEP-style proposal |
| Source of truth | Single governing document per topic | Eliminates split-brain across projects |
| Boundaries | Explicit in each RFC | Agents know exactly what's their responsibility |
| Approval | Multi-agent consensus + user approval | Reduces user burden, catches conflicts early |

## Alternatives Considered

1. **Per-project documentation** - Rejected because it creates split source of truth
2. **Wiki-style docs** - Rejected because version control is essential
3. **Versioned RFCs (v2, v3)** - Rejected in favor of supersession for cleaner history

## References

- RFC process: https://github.com/siy/coding-technology/tree/master/proposals
- Template: [TEMPLATE.md](TEMPLATE.md)
