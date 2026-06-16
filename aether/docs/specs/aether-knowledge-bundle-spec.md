---
title: Aether Knowledge Bundle (AKB) — Specification & Operations
status: Draft v0.1 (for implementation)
profile_of: Open Knowledge Format (OKF) v0.1
applies_to: Aether / Pragmatica documentation corpus
---

# Aether Knowledge Bundle (AKB) — Specification & Operations

AKB is a **strict profile of OKF** for the Aether documentation corpus. It keeps
OKF's substrate (a directory of markdown files with YAML frontmatter, shippable
by `git clone`, readable without tooling) and **inverts OKF's permissive
consumption model into strict, lintable conformance**, because the corpus is a
release-gated artifact, not an ever-growing agent-written catalog.

Key terms (RFC 2119): **MUST**, **SHOULD**, **MAY**. A bundle that violates a
MUST is non-conformant and MUST fail CI.

---

## 0. How to read this document

This document serves three consumers. Read the part that applies; the agent
reads all of it.

| Consumer | Reads | Does |
|----------|-------|------|
| **Implementing agent** | All sections | Conforms to §9; executes §10 once, then §11 continuously |
| **Human maintainer** | §1–§8, §12, Appendices | Authors concepts, makes editorial calls (App. C) |
| **Consumption agent** (slice authoring, ops, cluster mgmt) | §3, §4, §8 | Traverses the bundle / reads the in-code store at full fidelity |

The document is itself AKB-shaped: §1–§9 are the **spec** (stable contract),
§10–§11 are **procedures** (the migrate-once and maintain-forever playbooks),
§12 is **sequencing**. Treat the spec as normative and the procedures as the
operationalization of it — when they appear to conflict, the spec wins and the
procedure is wrong.

---

## 1. Purpose, scope, relationship to OKF

**Purpose.** Represent the entire Aether documentation corpus as one coherent,
agent-traversable knowledge bundle that supports every Aether task — setup,
operations, slice authoring, cluster management — from a single source of truth,
and that **cannot silently drift from the code it describes**.

**What AKB keeps from OKF:** markdown + YAML frontmatter; metadata-as-code (docs
live with source); a required, self-describing `type`; `index.md` progressive
disclosure; `log.md` history; the "reference external schemas, don't subsume
them" non-goal; the tiny-required-core / extensible philosophy.

**What AKB inverts or adds (the strict delta):**

1. Broken internal links are **errors**, not "not-yet-written knowledge."
2. Orphaned concepts (unreachable from any index) are **errors**.
3. `type` is drawn from a **closed vocabulary** (§4.2), not free-form.
4. Relationships are **typed** (§4.4) and constrained as a DAG (§6).
5. Concepts carry a **consumption mode** (§4.3) telling an agent *how* to use them.
6. Volatile, code-belonging facts are **projected from code**, not hand-written,
   at **region granularity** (§5, §8) — making the projected tier drift-proof by
   construction rather than drift-caught by CI.

**Out of scope.** AKB does not define serving/query infrastructure, does not
replace domain schemas (OpenAPI, the config schema, slice contracts — it
*references* them), and does not carry decision rationale (that lives in `ADR`
concepts and external articles).

---

## 2. Model and terminology

- **Bundle** — the Aether corpus: a directory tree of markdown concept files.
  The unit of distribution; versioned with the code (a bundle at git tag `X` is
  the documentation *of* the code at tag `X`).
- **Concept** — one markdown document = one unit of knowledge. Has frontmatter +
  body.
- **Concept ID** — the file path within the bundle minus `.md`
  (`concepts/dht/topology.md` → `concepts/dht/topology`).
- **Provenance region** — a contiguous span of a concept body that is either
  `authored` (human-owned, editable) or `projected` (code-owned, generated,
  read-only). A single concept MAY mix regions (§5).
- **Projection** — the one-directional `code → docs` generation of `projected`
  regions from the **in-code knowledge store** (§8). Never `docs → code`.
- **In-code knowledge store** — structured annotations in the source that hold
  the code-belonging volatile facts (config keys/defaults/constraints, API
  signatures, error codes, slice contracts). The single source of truth for the
  projected tier; consumed by docs, LSP tooling, and validators alike (§8.3).
- **Index** — `index.md`, a generated directory listing for progressive
  disclosure (§7).
- **Relation** — a typed, directed edge between concepts (§4.4), constrained as a
  DAG (§6).

---

## 3. Bundle structure (two-tier information architecture)

The corpus is organized in **two tiers**: a thin **task-entry tier** (how a
consumer arrives — by intent) over a **topic tier** (where the knowledge lives —
by subject). Consumers arrive with tasks; knowledge is organized by topic; the
task tier routes intent into topic.

```
aether-knowledge/
├── index.md                       # root progressive-disclosure entry (MAY carry okf_version)
├── log.md                         # optional curated change history
│
├── playbooks/                     # TASK-ENTRY TIER — execute-steps, the front doors
│   ├── index.md
│   ├── write-a-slice.md           # type: Playbook
│   ├── bootstrap-a-cluster.md
│   ├── operate-a-cluster.md
│   └── debug-networking.md
│
├── concepts/                      # TOPIC TIER — read-for-context
│   ├── index.md
│   ├── dht/      { topology.md, routing.md, … }
│   ├── consensus/{ rabia.md, … }
│   ├── time/     { hlc.md, … }
│   └── slices/   { model.md, lifecycle.md }
│
├── reference/                     # PROJECTED TIER — reference-do-not-restate (generated)
│   ├── index.md
│   ├── config/  { *.md with projected regions }
│   └── api/      { *.md with projected regions }
│
├── templates/                     # template-to-instantiate
│   └── slice.md                   # type: SliceTemplate (doc + canonical example + lint rules)
│
├── antipatterns/                  # type: AntiPattern — first-class negative knowledge
│   └── index.md
│
└── decisions/                     # type: ADR — rationale lives here, not in the spec
    └── index.md
```

### 3.1 Reserved filenames

`index.md` (§7) and `log.md` (§7) are reserved at every level and MUST NOT be
used for concept documents. All other `.md` files are concepts.

---

## 4. Frontmatter schema

Every concept MUST begin with a parseable YAML frontmatter block.

```yaml
---
type: <closed vocabulary §4.2>        # REQUIRED
consumption_mode: <closed §4.3>       # REQUIRED
description: <one sentence>            # REQUIRED (feeds index + retrieval)
title: <display name>                 # RECOMMENDED
tags: [<tag>, …]                      # OPTIONAL
timestamp: <ISO 8601>                 # REQUIRED for concepts with projected regions
relations:                            # OPTIONAL, typed (§4.4)
  depends-on: [<concept-id>, …]
  refines:    [<concept-id>, …]
  supersedes: [<concept-id>, …]
  implements: [<concept-id>, …]
source_refs:                          # REQUIRED for projected/spliced concepts (§8)
  - <path-or-symbol in source authoritative for a projected region>
---
```

### 4.1 Required / recommended / extension

`type`, `consumption_mode`, and `description` are **required** on every concept
(stricter than OKF, which requires only `type`). Producers MAY add extension
keys; consumers MUST preserve unknown keys and MUST NOT reject documents for
having them.

### 4.2 Type vocabulary (closed)

| `type` | Meaning | Default `consumption_mode` |
|--------|---------|----------------------------|
| `Architecture` | System structure (DHT, Rabia, HLC) | `read-for-context` |
| `Concept` | A single primitive / idea explained | `read-for-context` |
| `Guide` | Human-facing task narrative | `read-for-context` |
| `Playbook` | Agent-executable task entry point | `execute-steps` |
| `ConfigReference` | Config keys/defaults/constraints | `reference-do-not-restate` |
| `ApiReference` | API signatures / contracts | `reference-do-not-restate` |
| `SliceTemplate` | Slice contract + canonical example + lint rules | `template-to-instantiate` |
| `AntiPattern` | What not to do, and why | `read-for-context` |
| `ADR` | Architecture decision record (rationale) | `read-for-context` |
| `Reference` | Mirrored external material | `reference-do-not-restate` |

A `type` outside this list is a conformance error. (Editorial call — see App. C.)

### 4.3 Consumption mode (closed)

Tells an agent *how* to use a concept — the field that prevents the
knowledge/action conflation:

- `read-for-context` — read to understand; do not execute.
- `execute-steps` — the body is a procedure to run.
- `template-to-instantiate` — the body is a scaffold to copy and fill.
- `reference-do-not-restate` — authoritative facts; link to it, never paraphrase
  it into another concept.

### 4.4 Typed relations (closed)

Relationships are declared in frontmatter and typed (OKF leaves them untyped in
prose; AKB does not). Defined types: `depends-on`, `refines`, `supersedes`,
`implements`. A soft, untyped `see-also` MAY be expressed as an inline body link.
Every relation target MUST be a valid concept ID. See §6 for graph constraints.

---

## 5. Body and provenance regions

The body is markdown. Producers SHOULD favor structural markdown (headings,
tables, fenced code) over prose. Conventional headings: `# Schema`, `# Examples`,
`# Steps`, `# Citations`.

### 5.1 Region states and markers

Every byte of a body is either **authored** (default) or inside a **projected
region**, fenced by greppable HTML comments that survive markdown rendering:

```markdown
Pool sizing is governed by back-pressure, not latency — set it from the slowest
downstream, not the fastest. <!-- authored: the "why" -->

<!-- akb:projected source="config/pool.max-size" generator="jbct-projector@1" checksum="sha256:…" -->
| Key | Default | Constraint |
|-----|---------|------------|
| `pool.max-size` | 64 | `>= 1` |
<!-- akb:/projected -->
```

This is what lets a `ConfigReference` be an **authored envelope around a projected
fact** — the spliced case that pure projected-XOR-authored cannot express, and
the case that your config-key contradiction actually was.

### 5.2 Read-only / one-directional rule

Projection is strictly `code → docs`. Content inside `akb:projected` fences is
**generated and read-only**: it MUST NOT be hand-edited, and a regeneration MUST
reproduce it byte-for-byte (the lint regenerates and diffs — a non-empty diff is
a stale-or-tampered error). Authored content outside the fences is never touched
by projection.

### 5.3 Reference, don't restate

Any code-belonging fact (config key, signature, error code, contract) MUST appear
as a `projected` region or a link to a `reference-do-not-restate` concept — never
as paraphrased authored prose. Restating is exactly the move that lets two copies
of a fact diverge.

---

## 6. Cross-linking and the relation DAG

- **Internal links** use bundle-relative paths beginning with `/`
  (`/concepts/dht/topology.md`). Relative links MAY be used within a directory.
- **Broken internal links are errors** (§9). External URLs are unconstrained.
- **Graph constraints** (normative):
  - `depends-on` MUST be acyclic across the whole bundle.
  - `supersedes` targets MUST exist and MUST be marked deprecated (a
    `deprecated: true` extension key or relocation to `decisions/`); dangling
    `supersedes` is an error.
  - `refines` / `implements` targets MUST exist.
- The transitive `depends-on` closure of a concept **is** its progressive-
  disclosure reading path: "what must I read to understand X" is computed, not
  guessed.

---

## 7. Index and log files

**`index.md`** MAY appear in any directory and is generated for progressive
disclosure. It has no frontmatter (except the bundle-root `index.md`, which MAY
carry `okf_version`). Each entry SHOULD carry the linked concept's `description`.
Producers SHOULD generate indexes; the generator MUST list **every** non-reserved
concept in its directory — this is the mechanism that makes orphans detectable.

**`log.md`** MAY record curated, human-readable history per scope (newest first,
ISO `YYYY-MM-DD` headings). This is the *curated* narrative of change, distinct
from raw `git log`.

---

## 8. In-code knowledge store (the projection source)

The code-belonging volatile tier is stored **in the code**, as structured
annotations, and projected into the bundle. This converts drift from a detective
control (CI catches divergence) into a preventive one (the fact has exactly one
home).

### 8.1 Annotation mini-schema

The store MUST be structured (not free prose), so it can project into typed
frontmatter and be linted. Illustrative form (binding is an editorial call —
App. C):

```java
// @akb id=config/pool.max-size type=ConfigReference
// @akb default=64 constraint=">= 1"
// @akb projects-to=reference/config/pool.md#max-size
public static final int DEFAULT_POOL_MAX_SIZE = 64;
```

### 8.2 Extraction via jbct-parser CST

Projection is **another consumer of the jbct-parser PEG Java 25 CST**, not a new
tool. The projector walks the CST, reads `@akb` annotations, and emits the
`akb:projected` regions. The marginal cost is low precisely because the parser
already exists.

### 8.3 One truth, three consumers

The same in-code store feeds:

1. **Doc projection** — humans + coarse retrieval (this bundle).
2. **LSP / IDE tooling** — hover, completion, diagnostics.
3. **Validators** — lint rules co-located with the slice/config they govern.

Design the store for all three. The docs are the *least* fidelity-critical of the
three outputs; a code-aware consumption agent SHOULD read the store directly at
full fidelity and treat the rendered docs as the human/low-bandwidth surface.

### 8.4 Linting the store against its own code

A structured-but-unchecked annotation drifts from its own code the same way a
prose comment does. The store therefore MUST be linted against the code it
annotates (e.g. `@akb default=64` MUST match the annotated literal; a
`projects-to` target MUST exist). Skipping this relocates drift; it does not
remove it.

---

## 9. Conformance (normative checklist)

A bundle is AKB-conformant iff **all** of the following hold. CI MUST enforce
them as a blocking gate.

1. Every non-reserved `.md` has parseable frontmatter with non-empty `type` from
   the closed vocabulary (§4.2).
2. Every concept has a `consumption_mode` from the closed set (§4.3) and a
   non-empty one-line `description`.
3. Every concept is reachable from at least one `index.md` — **no orphans**.
4. Every internal link resolves — **no broken internal links**.
5. Every `relations` entry uses a defined type and resolves to an existing
   concept; `depends-on` is acyclic; `supersedes` targets exist and are
   deprecated (§6).
6. Every `akb:projected` region regenerates byte-identically from the current
   in-code store — **no stale or hand-edited projections** (§5.2).
7. Every concept with projected regions declares `source_refs` and a `timestamp`
   ≥ the last change of every referenced source (staleness guard; mostly moot
   once projection is wired, but enforced as a backstop).
8. Every `@akb` annotation agrees with the code it annotates (§8.4).

Soft guidance (SHOULD, not gating): index entries carry descriptions; bodies
favor structural markdown; `log.md` curated per scope.

---

## 10. Migration — restructuring existing docs (one-time)

Executed once against `release-1.0.0-rc1`. The rc1 audit (broken links, orphans,
the pool-config contradiction) is the **input inventory** for Phase 0.

- **Phase 0 — Inventory & classify.** Parse every existing doc. Assign each to a
  `type` (§4.2) and a knowledge quadrant: *code-derived/volatile* (→ projected),
  *human/stable* (→ authored concept), etc. Emit the orphan list and broken-link
  list. Do not rewrite yet.
- **Phase 1 — Stand up the spine.** Create the bundle skeleton (§3), the root
  index, the closed vocabularies, and the §9 lint in CI **as non-blocking
  warnings**. The lint failing loudly is the point.
- **Phase 2 — Prove ONE vertical: slice authoring.** Build `templates/slice.md`
  (`SliceTemplate`), `playbooks/write-a-slice.md` (`Playbook`), the `concepts/`
  it depends on, and the projected `reference/api` it needs. Drive the full loop
  end to end: intent → progressive disclosure → generate → validate. **Do not
  generalize until this vertical holds.**
- **Phase 3 — Wire projection for the vertical.** Add `@akb` annotations to the
  vertical's source; stand up the CST projector (§8.2); replace restated
  config/API prose with `akb:projected` regions; resolve the pool-config
  contradiction by projecting the key and *authoring* only the "why 30 minutes."
- **Phase 4 — Replicate.** Repeat Phases 2–3 for ops and cluster-management
  verticals.
- **Phase 5 — Harden.** Flip §9 lint from warning to **blocking**. Delete
  superseded docs (don't leave them as orphans).

---

## 11. Maintenance — lifecycle operations (recurring)

This section is the skill-shaped `Playbook` the agent re-consults. Event → action:

| Event | Action |
|-------|--------|
| Code change touches an `@akb`-annotated fact | Regenerate projections; CI diffs; fail if any `akb:projected` region is stale (§9.6) |
| New feature added | Author `Concept`(s) + a `Playbook` entry; declare typed `relations`; regenerate the affected `index.md` |
| Behavior change with a decision | Add an `ADR`; `supersedes` the old concept; mark the old one deprecated (§6) |
| Deprecation | Add `supersedes` edge from the replacement; never delete-without-redirect (avoid orphaning inbound links) |
| New version / release tag | Check out the tag, regenerate the projected tier → per-version reference docs **for free** (§2) |
| Any doc PR | Run the full §9 conformance gate before merge |
| New `AntiPattern` discovered | Add to `antipatterns/`; link from the relevant `Playbook` (what-not-to-do is load-bearing for agents) |

**Invariant the agent maintains:** authored content carries the "why"; projected
content carries the "what"; the two never swap homes, and projected content is
never hand-edited.

---

## 12. Rollout sequencing

Build the **spine + one front door** before generalizing — the same ladder
discipline that prevents over-building. Concretely: §10 Phases 0–3 (spine, lint,
*slice-authoring vertical only*, projection for that vertical) must hold
end-to-end before Phase 4 touches a second vertical. Generalizing the
architecture across all four task domains before the first vertical proves the
intent→disclosure→generate→validate loop is the un-lazy trap. One vertical
working beats four verticals scaffolded.

---

## Appendix A — Worked examples

**A.1 A `Concept` (authored, topic tier)**

```markdown
---
type: Concept
consumption_mode: read-for-context
description: HLC packs a 48-bit microsecond clock and a 16-bit logical counter into 64 bits.
title: Hybrid Logical Clocks
relations:
  refines: [/concepts/time/causality.md]
---
# Model
Aether's HLC is a compact 64-bit value: 48 bits of microseconds, 16 bits of
logical counter. …
```

**A.2 A `ConfigReference` (authored envelope + projected fact — the spliced case)**

```markdown
---
type: ConfigReference
consumption_mode: reference-do-not-restate
description: Connection-pool sizing keys and their constraints.
timestamp: 2026-06-15T00:00:00Z
source_refs: [com.pragmatica.aether.net.PoolConfig]
---
# Why
Size the pool from the slowest downstream under back-pressure, not from latency.

# Keys
<!-- akb:projected source="PoolConfig" generator="jbct-projector@1" checksum="sha256:…" -->
| Key | Default | Constraint |
|-----|---------|------------|
| `pool.max-size` | 64 | `>= 1` |
<!-- akb:/projected -->
```

**A.3 A `Playbook` (task-entry tier)**

```markdown
---
type: Playbook
consumption_mode: execute-steps
description: Author, scaffold, and validate a new Aether slice.
relations:
  depends-on: [/concepts/slices/model.md, /templates/slice.md]
---
# Steps
1. Instantiate [/templates/slice.md](/templates/slice.md).
2. …
```

## Appendix B — Quick reference

- **Types (closed):** `Architecture`, `Concept`, `Guide`, `Playbook`,
  `ConfigReference`, `ApiReference`, `SliceTemplate`, `AntiPattern`, `ADR`,
  `Reference`.
- **Consumption modes (closed):** `read-for-context`, `execute-steps`,
  `template-to-instantiate`, `reference-do-not-restate`.
- **Relations (closed):** `depends-on` (acyclic), `refines`, `supersedes`
  (non-dangling, target deprecated), `implements`; soft `see-also` inline.
- **Provenance:** `authored` (editable) vs `akb:projected` (generated,
  read-only, byte-stable).
- **Conformance MUSTs:** §9 items 1–8.

## Appendix C — Editorial calls left to the maintainer

These were deliberately not decided; they're yours to finalize before
implementation:

1. **Type-vocabulary scope.** The §4.2 list is a proposed closed set. Confirm,
   trim, or extend it — but keep it closed, or conformance §9.1 loses its teeth.
2. **In-code annotation binding.** §8.1 shows comment-style `@akb` tags. Decide
   between structured doc-comments and real Java annotations (the latter are
   type-checked and CST-visible but intrude on signatures; the former are freer
   but need their own lint). Either works; the choice affects the projector and
   the §8.4 lint.
3. **Region-marker syntax.** §5.1 uses `<!-- akb:projected … -->` HTML-comment
   fences. Confirm, or pick an alternative that survives your markdown pipeline
   and stays greppable.
