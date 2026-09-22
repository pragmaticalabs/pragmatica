# Source-aware provider routing

Scope: normative target for runtime PR #1390; present-tense requirements do not assert that
`release-1.0.0-rc4` implements them. Baseline observations are explicitly labelled below.

Status: implementation contract for the fresh-cluster hierarchy batch. This specification complements
`hierarchical-cluster-contract-spec.md`; it does not define a rolling upgrade or migration path.

## Identity and routing

A source name identifies a configured provider/account/location binding. It is not a label applied
to an arbitrary shared provider. Provisioning resolves the source in the committed cluster config
and constructs that source's integration using `EnvironmentIntegrationFactory`. The original
`ProvisionContext`, target node identity and operation identity pass through unchanged.

Node-specific inventory, restart and termination resolve the source from committed placement or
an explicit durable provisioning operation. They must not probe other accounts to find a node or
fall back to the leader's own boot provider. Unknown nodes, absent config, unsupported source types
and missing provider capabilities produce typed failures before provider side effects.

Intentional fleet inventory is different: a cluster-scoped query without a source enumerates all
matching configured provisionable sources, preserving the original role filter and adding each
source filter. Each source contributes one inventory operation (plus provider-required pagination).
Any failed source query fails the fleet view; a partial view must not masquerade as absence.

## Credentials and immutable bindings

Persisted cluster configuration can contain unresolved references. Runtime source resolution uses
the existing `${env:NAME}` and `${secrets:name}` grammar. The latter maps to `AETHER_NAME`, with
uppercase and hyphens replaced by underscores, matching bootstrap `ConfigReferenceResolver` and runtime `ConfigReferenceValues`. This is not the
`EnvSecretsProvider` convention (`AETHER_SECRET_<PATH>`), which is a separate secret-provider boundary. Missing,
blank, nested or unknown references fail before provider construction. Neither credentials nor
resolved values appear in these failures.

Every core that can become leader must have bindings for every source it may manage. A bootstrap
recipe is:

1. Give each source its own reference, such as `${env:EU_CLOUD_TOKEN}` and `${env:US_CLOUD_TOKEN}`.
2. Bootstrap resolves the source references and places protected `cloud.sources.<source>` bindings
   in every core configuration. Replacement cores inherit all these bindings; workers receive only
   their selected provider configuration. Provider SSH/firewall resource IDs are source-scoped.
3. Keep each source's provider-specific `node_config.cloud` configuration attached to that source.
   Multi-field credentials retain the existing provider factory's field names. The source scalar
   credential follows bootstrap's `api_token`/`access_key`/`credentials_file` mapping; it does not
   invent missing AWS/GCP/Azure credential fields.
4. Verify each source can resolve before relying on automatic replacement. A missing binding is
   operator-visible failure, never permission to reuse another source's credentials.

The runtime adapter first uses these protected source bindings and does not issue Secret Manager
network reads. An operator process without a protected core configuration can resolve raw references
from its environment; unresolved references fail closed. A core's own positional `[cloud.credentials]` value is not
proof of another source's account identity.

Durable operations retain a stable source binding digest. The registry exposes SHA-256 of canonical,
length-prefixed provider/type/credential references/region/zone/zone-list and sorted provider-specific
credential/compute/security fields. Counts, instance sizes and image/user-data defaults do not alter
that profile identity. The runtime operation digest also includes the resolved protected account
credentials and provider configuration. Bound provision, lookup and termination resolve and verify
that digest from one immutable configuration snapshot before selecting a provider, preventing a
check/use account change. Digests contain no plaintext credentials.
[limit: source-binding-secret-commitment] These deterministic unsalted digests are commitments,
not encryption; weak credentials can be guessed offline. Restrict access to binding metadata. Runtime source identity
changes must be refused while existing placements or operations reference the old binding; [unverified: source-mutation-management] the management route must enforce this constraint;
its current source-mutation rejection has not been established by a named test. Use a new source name
for account or provider movement. Secret rotation must preserve the account denoted by the source;
changing the referenced environment's account behind the same name violates this contract.

Global fleet admission includes live instances and unresolved durable reservations across sources.
A provider-account count is only a secondary guard; it is not a global cap. Retiring instances still
consume capacity until absence is confirmed. Unknown provider outcomes keep their reservation and
must not cause a fresh create with a new identity.

## GCP resource identity and inventory

GCP resources use `zone/instance-name` as the provider instance identifier. Status, deletion, reset
and label updates route to that zone even when it differs from the client's default. Inventory uses
project-wide `instances.aggregatedList`, follows every page and rejects unreachable-zone responses.
All requested pages must succeed before the result can authorize absence-based recovery.

A create uses a stable VM name derived from the cluster/source/target-node identity and a stable UUID
`requestId`. A timeout does not authorize moving the same uncertain operation to another zone.
`instances.insert` returns an operation acknowledgment, not an instance; readiness polling observes
the actual instance before publishing its location. Final readiness preserves provider-observed
identity, addresses and zone rather than returning the original intent.

The API contracts are documented in Google's [insert reference](https://docs.cloud.google.com/compute/docs/reference/rest/v1/instances/insert)
and [aggregated inventory reference](https://docs.cloud.google.com/compute/docs/reference/rest/v1/instances/aggregatedList).
No live cloud benchmark or account-level integration result is implied by unit/transport tests.

## Acceptance criteria

- Two sources with different credentials or regions select different integrations; unknown sources
  invoke no provider factory or cloud operation.
- A create retains the same provision context and invokes only its selected provider.
- Exact-source termination never probes other accounts; fleet queries include every matching source.
- Unresolved credentials fail without exposing values; binding digests are deterministic across map order.
- GCP alternate-zone status/delete/restart/labels address the correct URL; create reuses its name and
  request ID; multi-page inventory preserves zones and refuses partial results.
- Source identity changes cannot redirect outstanding operations after restart or leader handoff.
