---
title: Aether Knowledge Bundle — Projector & Lint Implementation Brief
status: Draft v0.1 (for implementation)
companion_to: aether-knowledge-bundle-spec.md (AKB spec)
audience: implementing agent / tooling author
---

# AKB Projector & Lint — Implementation Brief

This brief specifies the two pieces the AKB spec defines by contract but does not
implement: the **projector** (emits `akb:projected` regions from code) and the
**lint** (enforces §9 conformance). It assumes the AKB spec is the normative
authority — when this brief and the spec disagree, the spec wins, except where
Appendix D explicitly proposes a spec change.

It also assumes **jbct-parser** (PEG Java 25 → CST) and **jbct-lint** already
exist. Nothing here re-parses Java; everything consumes the existing CST.

---

## 0. Scope & relationship to the spec

Builds: the annotation extraction front-end, the projector (`generate`/`verify`),
the bundle lint, and the annotation lint. Does **not** build: the docs
themselves, the index generator (separate, trivial), or any serving layer.

Maps to spec sections: annotation store §8.1; CST extraction §8.2;
three-consumer architecture §8.3; annotation↔code lint §8.4; provenance regions
§5; conformance §9; freshness §9.6/§9.7; CI gate §9.

---

## 1. Architecture: one front-end, three back-ends

The §8.3 "one truth, three consumers" principle is realized as a single
extraction front-end feeding three back-ends. Build the front-end once.

```
            ┌───────────────────────────┐
 source ───▶│  jbct-parser CST (exists) │
            └─────────────┬─────────────┘
                          ▼
            ┌───────────────────────────┐
            │  EXTRACTION FRONT-END      │   walk CST, read @akb,
            │  CST ──▶ Fact model (IR)   │   resolve bound elements
            └──────┬──────────┬──────────┘
                   │          │          
        ┌──────────▼──┐  ┌────▼─────────┐  ┌──────────────────┐
        │ PROJECTOR   │  │ ANNOTATION   │  │ LSP feed (later, │
        │ Facts ▶ md  │  │ LINT (§9.8)  │  │ out of scope)    │
        │ §9.6        │  │              │  │                  │
        └─────────────┘  └──────────────┘  └──────────────────┘

   BUNDLE LINT (§9.1–9.5, 9.7) runs on the markdown tree only — no CST needed.
```

Two independent inputs, therefore two lint families:
- **Bundle lint** — reads the markdown tree. Owns §9.1–9.5, 9.7. Has value
  *immediately*, before any projection exists.
- **Front-end + projector + annotation lint** — read the CST. Own §9.6, §9.8.

---

## 2. Core principle: derive-first, declare-the-remainder

The in-code store can itself harbor drift if it duplicates facts the code already
states. Eliminate that surface:

- **Derived fact** — read directly from the bound CST node (method signature,
  field name/type, default *literal*, enum constant name). **Cannot drift** —
  there is no second copy. Prefer this always.
- **Declared fact** — written in the annotation only because the code cannot
  express it (a semantic constraint like `>= 1`, a human-meaningful note). **Can
  drift**; MUST be linted against a code counterpart where one exists; kept
  minimal where none does.

Consequence: an annotation is mostly **routing** (where does this fact go) plus
the irreducible declared remainder. It SHOULD NOT restate a literal the CST
already yields. (This tightens spec §8.1 — see Appendix D.1.)

---

## 3. In-code annotation grammar

### 3.1 Lexical form

`@akb` directives are carried in a structured comment block immediately preceding
the bound declaration. Multiple lines accumulate onto the same bound element.
(Comment-style vs Java-annotation binding is an open call — spec App. C.2; the
extraction contract below is binding-agnostic.)

```java
// @akb id=config/pool.max-size type=ConfigReference projects-to=reference/config/pool.md#max-size
// @akb constraint=">= 1"
public static final int DEFAULT_POOL_MAX_SIZE = 64;   // 64 is DERIVED, never declared
```

### 3.2 The `@akb` directive and its keys

| Key | Required | Role | Derived/Declared |
|-----|----------|------|------------------|
| `id` | yes | stable fact identity | declared (routing) |
| `type` | yes | `ConceptType` (closed, ties to spec §4.2) | declared (routing) |
| `projects-to` | yes | `<concept-path>#<anchor>` target region | declared (routing) |
| `constraint` | no | semantic constraint not expressible as a literal | declared (linted if a guard exists) |
| `note` | no | short semantic remark | declared (not linted) |

No `default=`, `name=`, `signature=`, `type-of=` keys: those are **derived** from
the bound element. Adding them is a lint warning (declared-what-is-derivable).

### 3.3 Binding: CST node → what is derived

| Bound CST node | Valid `type` | Derived from the node |
|----------------|--------------|------------------------|
| Field declaration | `ConfigReference` | field name, field type, default literal (initializer) |
| Method declaration | `ApiReference` | full signature: modifiers, return, name, params, throws |
| Type declaration | `ApiReference`, `SliceTemplate` | type name, type params, supertypes/interfaces |
| Enum constant | `Reference` (error codes) | constant name |

A type/node mismatch (e.g. `ConfigReference` on a method) is a §9.8 error.

### 3.4 Coverage relationship

Every `@akb` annotation MUST have its target fence already present in the bundle
(§5.3). Annotation with no fence → **unplaced fact** (lint). Fence with no
annotation → **orphan fence** (lint). See §6 rules L9/L10.

---

## 4. Extraction (CST → Fact model)

### 4.1 Fact model (IR)

```
Fact {
  id           : string              // @akb id
  type         : ConceptType         // closed set
  target       : { concept: path, anchor: string }   // from projects-to
  bound        : CstRef              // node the annotation attaches to
  derived      : map<string, Value>  // pulled from `bound` (signature, default, …)
  declared     : map<string, Value>  // constraint, note
  origin       : { file: path, line: int }
}
```

`derived` is populated by a per-node-kind extractor (§3.3). `declared` is the
parsed remainder of the directive.

### 4.2 The walk (pseudo-code)

```
facts = []
for node in cst.preorder():
    block = leading_akb_comments(node)        # the // @akb … lines, if any
    if block is empty: continue
    dir = parse_directives(block)             # → {id,type,projects-to,constraint?,note?}
    require(dir.id, dir.type, dir.projects_to) or error(E_MISSING_KEY, node)
    require(valid_binding(dir.type, node))    or error(E_BAD_BINDING, node)
    derived = extract_derived(dir.type, node) # signature / default literal / name …
    warn_if(dir declares anything in derived's keyset, W_DECLARED_DERIVABLE)
    facts.append(Fact(dir, node, derived))
return facts
```

### 4.3 Resolution & errors

- `parse_directives` failure → `E_MALFORMED_DIRECTIVE` (file:line).
- Duplicate `id` across the codebase → `E_DUPLICATE_FACT_ID`.
- Unknown `type` → `E_UNKNOWN_TYPE` (must be in the §4.2 closed set).
All extraction errors are blocking; the projector MUST NOT emit from a bundle
with extraction errors.

---

## 5. Projection (Facts → `akb:projected` regions)

### 5.1 Run modes

| Mode | Effect | Used by |
|------|--------|---------|
| `generate` | rewrites fence interiors in place; bumps `timestamp` on change | developer, locally |
| `verify` | regenerates into memory, diffs against disk; **never writes** | CI (§9.6 gate) |

`verify` exits non-zero on any byte difference. This is the generated-code-is-
checked-in pattern (cf. `gofmt -l`): developers run `generate` and commit; CI
runs `verify` to prove they did.

### 5.2 Ownership boundary (what the projector may write)

The projector writes **only**:
1. the bytes **inside** `akb:projected … / akb:/projected` fences, and
2. the `timestamp` frontmatter field of a concept whose projected content
   changed (§5.5), and the fence's own `checksum` attribute.

It MUST NOT touch authored body, fence placement, other frontmatter keys, or any
file with no matching fact. This is the concrete enforcement of the spec's
one-directional, projected-regions-read-only rule (§5.2).

### 5.3 Place vs fill (the safe asymmetry)

- **Humans place** fences: a `<!-- akb:projected source=… anchor=… -->` /
  `<!-- akb:/projected -->` pair (possibly empty) is dropped into an authored
  document where the layout makes sense. Placement is an authoring decision.
- **The projector fills/refreshes** the interior. It never creates, moves, or
  deletes a fence.

This keeps document layout human-owned while making content drift-proof, and it
makes coverage gaps visible (L9/L10) rather than silent.

### 5.4 Rendering

A type-keyed renderer registry maps `ConceptType → (Fact[] → markdown)`. Renderers
MUST be **deterministic**: stable sort (by anchor, then id), fixed formatting, no
timestamps or volatile data inside the rendered bytes. Exact output format per
type (table columns, signature style) is an editorial call (App. C.1).

Contract per renderer:
- input: all facts whose `target` matches `(concept, anchor)`, sorted.
- output: the exact bytes to place between the fence markers, newline-normalized.
- pure function of its input facts — same facts ⇒ same bytes.

### 5.5 Checksum & timestamp

- **checksum** = hash of the rendered interior, written to the fence attribute.
  Lets a reviewer/consumer detect a hand-edit **without** re-running the projector
  (a hand-edit changes content but not the stale checksum → mismatch). Defense-in-
  depth over the regenerate-diff; keep-or-drop is an editorial call (App. C.2),
  the tradeoff being offline tamper-detection vs. diff noise on every content
  change.
- **timestamp** is bumped (in `generate`) **iff** the rendered interior differs
  from disk. Unchanged content ⇒ untouched timestamp ⇒ no spurious diffs and a
  clean `verify`. (This makes the projector the owner of `timestamp` for
  projected concepts — App. D.3.)

### 5.6 Splice algorithm (pseudo-code)

```
for concept in bundle.concepts_with_fences():
    text = read(concept.file)
    changed = false
    for fence in find_fences(text):                  # by (source/anchor)
        group = facts.matching(concept.path, fence.anchor)
        if group is empty: error(L10_ORPHAN_FENCE, fence); continue
        rendered = renderer[group.type](group)
        if mode == verify:
            if fence.interior != rendered or fence.checksum != hash(rendered):
                fail(L6_STALE_PROJECTION, fence)
        else: # generate
            if fence.interior != rendered:
                fence.interior = rendered
                fence.checksum = hash(rendered)
                changed = true
    if changed and mode == generate:
        bump_timestamp(concept); write(concept.file, text)
# facts whose target fence was never seen:
for f in facts where no fence matched: report(L9_UNPLACED_FACT, f)
```

---

## 6. Lint check definitions (§9 made concrete)

Each rule: inputs, algorithm, failure, severity. `E` = blocking error (gate);
`W` = advisory. Rule ids `Ln`.

| id | spec | what it checks | sev |
|----|------|----------------|-----|
| L1 | §9.1 | `type` present, parseable, in closed vocab | E |
| L2 | §9.2 | `consumption_mode` in set; `description` non-empty single line | E |
| L3 | §9.3 | no orphan concepts (unreachable from index closure) | E |
| L4 | §9.4 | no broken internal links | E |
| L5 | §9.5 | relations typed + resolve; `depends-on` acyclic; `supersedes` non-dangling & target deprecated | E |
| L6 | §9.6 | projected regions byte-fresh (projector `verify`) | E |
| L7 | §9.7 | projected concepts: `timestamp` ≥ source change (backstop) | E |
| L8 | §9.8 | declared attrs agree with CST; binding valid | E |
| L9 | new | every fact has a placed fence (no unplaced facts) | E |
| L10 | new | every fence has a matching fact (no orphan fences) | E |
| W1 | §2 | annotation declares a derivable attribute | W |
| W2 | §7 | index entry missing its concept's description | W |

L9/L10 are additions implied by §5.3 — recommend folding into spec §9 (App. D.2).

**L3 — orphans.** Build `C` = all non-reserved `.md`. Build `R` = closure: start
at root `index.md`, follow links to concepts and to subdir `index.md`, repeat.
`orphans = C − R`. Fail if non-empty.

**L4 — broken links.** Collect internal links (body links starting `/` or
relative; every `relations` target). File-level resolution is **E**; anchor-level
(`#frag` exists as a heading or fence anchor in target) is **SHOULD/W** initially
(App. C.3).

**L5 — relation DAG.** Validate each `relations` key ∈ closed set and each target
exists. Build the `depends-on` digraph; DFS/Tarjan for cycles → fail on any.
For each `supersedes` target: must exist and carry `deprecated: true` → else fail.

**L6 — freshness.** Delegates to projector `verify` (§5.6). Any diff or checksum
mismatch fails.

**L8 — annotation↔code.** For each Fact: confirm `valid_binding(type, bound)`;
for each **declared** attribute with a code counterpart (e.g. a `constraint` that
a precondition guard expresses), compare to the CST-derived value; mismatch →
fail. Derived attributes are not checked (cannot drift).

---

## 7. CI integration & developer workflow

- **Local, on source change:** developer runs `akb generate`, commits refreshed
  regions alongside the code change. One commit carries code + its projection.
- **Pre-merge gate (blocking):** `akb verify` runs (a) bundle lint L1–L5, L7;
  (b) extraction; (c) annotation lint L8–L10; (d) projector `verify` L6. Any `E`
  fails the merge.
- **Migration window:** run the whole gate as **non-blocking warnings** until
  spec §10 Phase 5, then flip to blocking (matches spec §12).

---

## 8. Output contract & exit codes

Emit machine-readable results so the agent and CI can act:

```json
{ "conformant": false,
  "violations": [
    { "rule": "L4", "severity": "error", "file": "concepts/dht/topology.md",
      "line": 42, "detail": "link /concepts/dht/rooting.md does not resolve" },
    { "rule": "L6", "severity": "error", "file": "reference/config/pool.md",
      "anchor": "max-size", "detail": "projected region stale" }
  ] }
```

Exit `0` iff no `error`-severity violations. Warnings never change the exit code.
Human-readable rendering is a thin formatter over the same structure.

---

## 9. Build sequencing

Mirror the spec's spine-first / one-vertical-first rollout (§12):

1. **Extraction front-end** (CST → Fact model). The shared spine; nothing
   projects or annotation-lints without it.
2. **Bundle lint L1–L5** (markdown-only). Ship first — value before any
   projection exists; runs against the migrating corpus immediately.
3. **Projector `generate`/`verify` + L6 + L8** for the **slice-authoring
   vertical only**. Prove the loop on one vertical (spec §10 Phase 2–3).
4. **Replicate** renderers + facts to ops and cluster-management verticals.
5. **Harden:** flip all rules to blocking; add L7 backstop retirement decision.

Do not build all renderers before vertical 1's loop closes — that is the
over-build trap.

---

## Appendix A — Annotation grammar (EBNF)

```
akb-block   = akb-line { akb-line } ;
akb-line    = "// @akb" WS directive ;
directive   = pair { WS pair } ;
pair        = key "=" value ;
key         = "id" | "type" | "projects-to" | "constraint" | "note" ;
value       = bareword | quoted ;
quoted      = '"' { char } '"' ;
projects-to-value = concept-path "#" anchor ;
```

## Appendix B — Fact model schema

(See §4.1. `ConceptType` is the spec §4.2 closed set restricted to projectable
types: `ConfigReference`, `ApiReference`, `SliceTemplate`, `Reference`.)

## Appendix C — Editorial calls (this brief)

1. **Renderer output formats** per `ConceptType` (table columns; signature
   style). Pin once, then they're frozen by the determinism contract.
2. **Checksum: keep or drop.** Offline tamper-detection + review visibility vs.
   diff noise on every content change. `verify` covers freshness either way.
3. **Anchor-level link checking:** MUST now, or SHOULD until anchors stabilize.

## Appendix D — Refinements to carry back into the AKB spec

1. **§8.1 example tightening.** Drop `default=64` from the annotation — the
   literal is derived from the field initializer. Annotations route + declare the
   irreducible remainder (`constraint`); they do not restate derivable literals.
   Add the derive-first principle (§2 here) to spec §8.
2. **§9 coverage rules.** Add L9 (no unplaced facts) and L10 (no orphan fences)
   to the conformance list — they close the §5.3 place/fill loop.
3. **Timestamp ownership.** State in §5/§9.7 that the projector owns `timestamp`
   for concepts containing projected regions, bumping it only on content change.
4. **Place/fill asymmetry.** Make spec §5.2 explicit that humans place fences and
   the projector only fills them (never creates/moves/deletes).
