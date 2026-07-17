# Landscape — versioned application-set template (spec v0.1, embedded)

> Landed from #462 (spec v0.1, 2026-07-17); this file is the living document, the ticket tracks implementation.

## 1. Motivation

Aether has a strong application unit — the blueprint: atomic multi-slice deploys, canary/rolling/blue-green, per-blueprint migrations — and cluster provisioning below it. It has nothing **above** it: no way to declare "this cluster runs these applications at these versions." Kubernetes grew Terraform + Helm + ArgoCD as separate products because k8s is only a state store; Aether already has the consensus KV (desired-state store), leader-side reconcilers (CDM), and a versioned app unit — so the landscape layer is **one more KV key level and one more leader loop**, not a tool:

- **No state file** — the cluster's KV is the state (`landscape diff` compares a document against it).
- **No deployed operator** — the reconciler is a leader loop that already has siblings.
- **GitOps without an agent** — `aether landscape apply` is an idempotent desired-state put; running it from CI *is* GitOps.

Vocabulary: *application landscape* is the established enterprise-architecture term for exactly this concept. One-liner: **the landscape declares which applications exist; the cluster decides how they run.**

## 2. Concept & format

A landscape is a **versioned artifact** (Maven GAV, published like any blueprint) declaring the set of blueprint pins a cluster runs. The complete v1 schema:

```toml
id = "com.acme:landscape:2026.07.1"

[[blueprints]]
artifact = "com.acme:ticketing:1.4.2"

[[blueprints]]
artifact = "com.acme:analytics:2.1.0"
```

No ordering, no overrides, no resources, no config, no secrets — by design (§3). Duplicate GA (same groupId:artifactId, different versions) is a validation error.

## 3. Design axioms (owner decisions, 2026-07-17)

1. **Applications are independent — no ordering, no dependency DAG.** Cross-app interaction happens only via durable streams (pub/sub is intra-app), and a durable transport absorbs deploy-order gaps: a consumer app arriving later hydrates from the stream. Declaration order is used for deterministic *failure handling* only, never dependency. CDM's `allDependenciesActive` gating stays intra-blueprint; the landscape layer never touches it.
2. **No shared resources, no cross-blueprint runtime dependencies — a stance, not a deferral.** The blueprint is the atomicity and ownership boundary (ALL_OR_NOTHING deploys, per-blueprint migrations); sharing across blueprints would blur exactly the boundary that makes those tractable. Revisit only on a real use case with real demand.
3. **The landscape is environment-free.** Cluster bootstrap/update owns environment-ness (nodes, resources, secrets, sources — node-config composition already covers layering there). The landscape owns application *structure* only. Same landscape applies to dev/staging/prod clusters; sizing divergence is emergent (autoscaler + operator + cluster capacity, with `SCALE_CAPPED` (#425) as the honest surface when a small cluster can't fit the seeds).
4. **Counts are state, bounds are policy.** Blueprint `instances` is a *seed*, used only at a blueprint's first deployment; thereafter `SliceTargetValue.targetInstances` in KV is the sole authority and landscape/blueprint applies never rewrite it. Policy fields (`maxInstances`, scaling thresholds) ARE declarative and are updated in KV when a new blueprint version changes them. Same TOML section, opposite semantics — documented loudly to pre-empt both mirror-image bug reports.
5. **Honest atomicity.** Landscape apply guarantees *eventual convergence of the blueprint set with per-blueprint atomicity and per-blueprint health-gated strategies* — it is NOT a cross-application transaction, and no aggregate "landscape health" number is derived (per-scenario surfaces only, per resilience principles).

## 4. Operations — guarantee + mechanism

| Operation | Guarantee | Mechanism |
|---|---|---|
| `landscape apply <gav\|file>` | Desired set recorded durably; convergence begins; idempotent | Put `LandscapeKey → LandscapeValue` via consensus; leader-side **LDM** (Landscape Deployment Manager, sibling loop to CDM) converges pins **sequentially in declaration order**, stops at first blueprint failure, records per-blueprint outcome |
| failure recovery | No mechanism needed | Re-running `apply` is the resume: converged blueprints no-op, the failed one retries, the remainder continues |
| pin version change | Standard blueprint upgrade (rolling/canary per that blueprint's `[deployment]`); current scale preserved (axiom 4) | LDM re-publishes/applies the blueprint at the pinned version; existing CDM machinery does the rest |
| blueprint removed from doc | **Nothing is undeployed by default**; reported in `status` as pending-removal | `--prune` flag explicitly undeploys removed blueprints (existing `blueprints delete` machinery). No destructive action without the flag (kubectl `--prune` precedent) |
| `landscape status` | Snapshot: applied landscape id, per-pin declared vs actually-deployed version, convergence phase, where the last apply stopped and why, drift | Read-side projection of `LandscapeValue.applyState` + existing deployment status; **drift** = blueprints deployed imperatively outside the landscape → reported as *unmanaged*, never corrected |
| `landscape rollback` | Converge to previous landscape version; scale state untouched | Apply the previous id (KV keeps current + previous for the fast path; the artifact repo is the full history) |
| `landscape validate` / `diff` | Offline schema + pin-resolvability check / desired-vs-KV delta | Parse + verify every pin is published; set/version diff against `LandscapeValue` |

## 5. KV & reconciler design

- `LandscapeKey` — singleton per cluster. `LandscapeValue(landscapeId, pins[], applyState{per-pin phase/outcome/timestamp}, previousId)`.
- **LDM**: leader-only, watches `LandscapeValue`, converges the set as above; writes `applyState` transitions (observability-first: snapshot reads, no hot-path cost). Landscape transitions emit `ClusterEvent`s (applied / blocked-at-pin / pruned).
- **Rejected alternative**: CLI-side orchestration (loop of `blueprints apply`). No new cluster machinery, but loses in-cluster declarative state, `status` from any node, audit, and the "cluster is the reconciler" property that motivates the feature.

## 6. Surface (project invariants)

- **REST** (`aether/node` routes + `ManagementRoute` entries): `GET /api/landscape` (status; VIEWER), `POST /api/landscape/apply` (+ `prune` flag; ADMIN), `POST /api/landscape/rollback` (ADMIN), `GET /api/landscape/diff` (VIEWER).
- **CLI**: `aether landscape apply|status|rollback|validate|diff|publish`.
- **Docs**: `management-api.md` + `cli.md` + this spec as `aether/docs/specs/landscape-spec.md`; feature-catalog entry; CHANGELOG.

## 7. Out of scope (v1)

Ordering/DAGs; cross-blueprint activation gating; shared resources; environment overlays/templating; app-level secret refs; multi-cluster landscapes (cross-cluster is an explicit non-goal per `unified-deploy-spec.md`); a git-watching sync agent (CI + idempotent apply covers GitOps).

## 8. Validation gates & companion check

1. **Cross-namespace stream subscription check** (companion, may be a separate ticket): stream namespaces are blueprint-scoped (`BlueprintNamespace`: groupId+artifactId). Confirm a consumer in app B can explicitly address app A's stream namespace. If it cannot, axiom 1 is vacuously true (apps can't interact at all) and the gap should be ticketed — the landscape design itself is unaffected either way.
2. **E2E acceptance**: apply a 2-app landscape → both converge; induce a failure at pin 2 → status shows the stop point → re-apply resumes; bump one pin → rolling upgrade preserving current scale; `--prune` removes a dropped pin; an imperative out-of-landscape deploy shows as unmanaged drift; apply on a cluster too small for the seeds → `SCALE_CAPPED` observable.

## 9. Naming decision (recorded 2026-07-17)

**`landscape`** — the EA term of art ("application/system landscape") with exact semantics: independent things coexisting, no implied order. *Rejected:* `enterprise` (collision risk with a plausible future commercial "Aether Enterprise" tier — unfixable after GA), `portfolio` (financial connotation), `constellation` (register). Known minor collision: Canonical Landscape (Ubuntu fleet management) — product name vs our subcommand/concept name, always used in Aether context; acceptable.

