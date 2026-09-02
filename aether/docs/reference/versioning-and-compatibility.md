# Versioning & Compatibility

This page is the map across the versioning surfaces Aether actually has, and says plainly where a
policy has not been decided yet rather than implying one exists. Four surfaces get confused with
each other because they all use the word "version"; they are independent by design:

| Surface | What it versions | Status |
|---|---|---|
| Product release (`1.0.0-rc3`) | The whole codebase, as a release artifact | rc series, pre-GA; SemVer committed from GA (#321) |
| Envelope format (`ENVELOPE_FORMAT_VERSION`) | Slice-processor generated-code structure | Built, frozen at `1000` until GA |
| Slice HTTP API versions (`v1`, `v2`, ...) (#198) | An individual slice's own routes | Built |
| Management HTTP API (`/api/v1/...`) (#300) | The cluster's control-plane routes | Prefix scheme built (Commit 1, 2026-08-28); stream-route consolidation still in flight |

Node-to-node wire-protocol version skew is discussed separately below because, unlike the four
rows above, there is currently no mechanism for it at all — see "Rolling upgrades and node
version skew."

## Product release versioning

Aether is pre-GA — `1.0.0-rc3` at this writing — with one active release line
[mechanism: `CHANGELOG.md`, current branch]. **Aether commits to semantic versioning for the
product release from GA (`v1.0.0`) onward** [owner ruling 2026-08-28, #321]: minor releases
(`1.x.0`) are additive only — no removed or renamed public surface, no breaking behavior change;
breaking changes are reserved for major releases (`2.0.0`, ...). This governs the "Product
release" row in the table above. It is a separate axis from, and does not itself change, the
management API's own version axis (`/api/v1`, `/api/v2`, ...): that surface keeps its own
additive/breaking rules independently, per
[`../specs/management-api-versioning-spec.md`](../specs/management-api-versioning-spec.md) §2.6 —
an additive management-API change never mints a new API version, and an API major bump is not
*required* to coincide with a product major (though in practice a `v2` would normally coincide
with one).

**Pre-GA, this commitment does not apply.** Every rc may still break compatibility with the
previous one until GA ships — treat `1.0.0-rc3` and any later rc that way until the product
version itself reaches `1.0.0`.

**Not decided by the ruling, and still an open gap:** the *compatibility window* once a major
ships — how long a superseded major keeps receiving fixes, whether there is a backport or LTS
line, what a deprecation/EOL timeline looks like. The SemVer commitment above says how a version
number must behave; it does not by itself say how long an old one is supported. No such policy is
published anywhere in this repository today [checked `README.md`, `LICENSE`, `CHANGELOG.md` — none
states one], and this page will not invent a duration. **Tracked as #705** (filed 2026-08-28,
milestone v1.0.0): an owner-grade support-cost decision, parallel to how the version-skew gap
below was tracked as #666, not one to make silently in a docs pass.

Separately, what *is* an explicit, recorded policy — scoped to the management API surface only,
and still in Draft — is a **pre-GA no-backward-compatibility stance**: "pre-GA, a rename is free
(no-compat policy)" [mechanism: `aether/docs/specs/management-api-versioning-spec.md` §1, §2.3].
Do not read that as a blanket statement about every surface in the codebase (the envelope format,
for instance, already carries an explicit accept-set across rc's — see below); it is that one
Draft spec's stated design principle for its own surface, and it does not conflict with the
product-level SemVer commitment above — both apply pre-GA, to different axes.

For the authoritative statement of what "pre-GA" means for production-readiness (not a versioning
question, a scope one), see [`known-limitations.md`](known-limitations.md#release-maturity--rc-series-toward-ga)
— GA is gated on the scale-validation epic (#365), not a calendar date.

## Envelope format versioning (slice/runtime compatibility)

This is the mechanism that actually makes a rolling upgrade or a mixed-rc-version cluster work
today: the slice-processor stamps every generated slice with an integer `ENVELOPE_FORMAT_VERSION`,
and the runtime checks it against an accept-set (`SliceManifest.SUPPORTED_ENVELOPE_VERSIONS`)
before loading a slice [mechanism: `aether/slice/.../SliceManifest.java`,
`checkEnvelopeCompatibility()`]. It versions the generated-code *structure* (factory signatures,
dependency-wiring protocol), not the release version, and only changes when that structure
changes — most releases ship no bump at all.

**Frozen at `1000` until GA** (owner ruling 2026-07-18, #386): pre-GA structural changes to the
generated code ride without a version bump, because the rc series is rebuild-together and the
stamp is treated as a membership-checked compatibility gate, not a structural dispatch. Full
version history, the bump/no-bump rules, and the file-level mechanism are the single source of
truth at [`../contributors/envelope-versioning.md`](../contributors/envelope-versioning.md) — this
page doesn't duplicate that table.

## HTTP API versioning — two independent surfaces

### Slice-facing API versions (#198) — built

A slice can expose multiple versions of its own routes side by side (`v1`, `v2`, ...), selected by
URL path (default) or by request header, with `deprecated`/`sunset` metadata per version that adds
`Deprecation`/`Sunset` response headers a client can observe
[mechanism: `aether/docs/slice-developers/api-versioning-and-media-types.md`; `ApiVersioningDetection`
enum, `aether/aether-config/.../config/ApiVersioningDetection.java`]. This is per-slice and
independent of both the product version and the management API below.

### Management (control-plane) API versioning (#300) — prefix scheme built, rest still landing

The `/api/v1` path-prefix scheme from `aether/docs/specs/management-api-versioning-spec.md` §2.1 is
now built (Commit 1, 2026-08-28): `ManagementRoute`'s canonical constructor prepends the
`API_BASE = "/api/v1"` constant at one site, so every route declared with a plain-string suffix
mounts under `/api/v1/...` [mechanism: `aether/aether-management-api/.../route/ManagementRoute.java`].
The §2.2 carve-outs — health probes (`/health/live`, `/health/ready`) and the artifact-repository
routes (`/repository/**`) — opt out via the enum's distinct `raw(...)` constructor and stay
unversioned, matching the spec's stated design. RBAC, the security-guard layer, the CLI, and the
test consumers were synced to the new prefix in the same change.

**Not yet exercised:** the spec's post-GA dual-serve window (§2.6 — v_{n-1} served in parallel for
≥1 minor release once a `v2` is minted) and the `Deprecation`/`Sunset` header mechanism it depends on
have nothing to exercise them yet, since `/api/v1` is still the only version that has ever existed;
pre-GA the spec's stance is hard-cutover (§2.3), which is what this landing was. Treat the
dual-serve/deprecation machinery as design intent until a `v2` actually ships.

**Also not yet landed:** the spec's stream-surface consolidation (§3.2–§3.3 — folding the stream and
stream-namespace routes into a single catalog) is a separate, still in-flight piece of the same
effort; this page does not describe it until it lands.

## Rolling upgrades and node version skew

The operator-facing procedure exists and is real: `rolling-aether-upgrade.sh` drains a node,
shuts it down, has the operator restart it on the new binary, and canary-watches it before moving
to the next node [mechanism: [`../guides/rolling-upgrade.md`](../guides/rolling-upgrade.md)]. That
guide states the cluster "remains in a valid mixed-version state" during the rollout, and the part
of that claim this page can verify is the slice-loading layer: envelope-format compatibility
(above) is exactly what lets an old-format and new-format slice coexist across nodes mid-rollout.

**What this page cannot verify, because it does not appear to exist:** a version field on the
node-to-node join/handshake protocol, or documented codec-evolution rules for the gossip/consensus
wire format itself — i.e. a design for *node-binary* version skew, as opposed to slice-envelope
version skew. A search of the runtime's membership/handshake code turned up no version field, and
an internal design-completeness review from 2026-06-11 flagged this by name as a real gap with no
tracking issue: *"No node-version-skew design — `Hello` carries no version field; codecs have no
evolution rules; no rolling node-binary-upgrade story, no ticket."* **That gap is now tracked as
#666** (filed 2026-08-27): a deliberately minimal, pre-GA scope — a version field on `Hello` plus a
join-time mismatch policy (refuse-or-degrade, decision pending). Version negotiation, codec
evolution rules, and mixed-node-binary rolling-upgrade support remain explicit non-goals of that
ticket and stay unscheduled.

One consequence: **there is currently no recorded decision on whether node-binary version-skew
safety is a runtime-owned guarantee or an application-owned concern.** This page will not assert
one — #666 scopes exactly that mismatch-policy decision without yet making it, so this remains a
tracked-not-designed boundary rather than an invented one. If you are relying on the
rolling-upgrade procedure across anything other than adjacent rc builds (i.e. skipping rc versions
in one rolling upgrade), treat that as unverified and validate it yourself; the script's built-in
canary-wait step is the best available safety net today. See also
[`known-limitations.md`](known-limitations.md#node-binary-version-skew--no-join-time-version-check-yet).

## Reference material

- [`../contributors/envelope-versioning.md`](../contributors/envelope-versioning.md) — envelope
  format version history and the bump/no-bump rules.
- [`../slice-developers/api-versioning-and-media-types.md`](../slice-developers/api-versioning-and-media-types.md) —
  slice-facing API versioning and the deprecation lifecycle.
- [`../specs/management-api-versioning-spec.md`](../specs/management-api-versioning-spec.md) —
  Draft design for management-API versioning; not yet implemented.
- [`../guides/rolling-upgrade.md`](../guides/rolling-upgrade.md) — the operational rolling-upgrade
  procedure.
- [`known-limitations.md`](known-limitations.md) — the single source of truth for Aether's current
  scope; this page defers to it rather than restating release-maturity boundaries.
- [`../../../SECURITY.md`](../../../SECURITY.md) — trust model and default security posture.
