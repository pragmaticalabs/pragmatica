# RFC Proposals

Request for Comments (RFC) documents for cross-project design decisions.

## Index

| RFC | Title | Status | Affects |
|-----|-------|--------|---------|
| [RFC-0000](RFC-0000-ecosystem-foundation.md) | Ecosystem Foundation | Approved | all projects |
| [RFC-0001](RFC-0001-core-slice-contract.md) | Core Slice Contract | Draft | jbct-cli, aether |
| [RFC-0002](RFC-0002-dependency-protocol.md) | Dependency Protocol | Draft | jbct-cli, aether |
| [RFC-0003](RFC-0003-http-layer.md) | HTTP Layer | Draft | jbct-cli, aether |
| [RFC-0004](RFC-0004-slice-packaging.md) | Slice Packaging | Draft | jbct-cli, aether |
| [RFC-0005](RFC-0005-blueprint-format.md) | Blueprint Format | Draft | jbct-cli, aether |
| [RFC-0006](RFC-0006-slice-runtime-config.md) | Slice Runtime Config | Draft | jbct-cli, aether |
| [RFC-0007](RFC-0007-dependency-sections.md) | Dependency Sections | Draft | jbct-cli, aether |

## Workflow

```
Draft → Review → Approved → Implemented
                    ↓
              Superseded (if replaced)
```

### States

- **Draft**: Initial proposal, open for feedback
- **Review**: Multi-agent consensus in progress
- **Approved**: Both implementation PRs merged
- **Implemented**: Fully integrated into codebase
- **Superseded**: Replaced by newer RFC (link provided)

### Process

1. Author creates RFC as Draft
2. Affected project agents review and propose changes
3. Multi-agent consensus reached (all affected agents approve)
4. PRs created in affected projects implementing/acknowledging RFC
5. User reviews and approves PRs
6. Only when **both PRs merged** → RFC status becomes "Approved"

## Naming Convention

```
RFC-NNNN-short-title.md
```

- 4-digit zero-padded number
- Lowercase hyphenated title
- Example: `RFC-0042-async-slice-invocation.md`

## Supersession

When an RFC is superseded:
1. Add `Superseded-By: RFC-XXXX` to front matter
2. Update status to `Superseded`
3. Old RFC remains for historical reference
4. New RFC links back to what it supersedes

No versioning within RFCs (e.g., no `RFC-0001-v2`). Create new RFC instead.
