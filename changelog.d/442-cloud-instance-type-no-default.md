### Fixed (2026-09-10 — cloud instance type: no default, and a provisioning failure that explains itself)

- **`aether cluster init` shipped `cx21` as Hetzner's instance-type default, and Hetzner has deleted
  that server type.** Any operator who pressed Enter through the wizard's `Instance type [cx21]`
  prompt got a config that could not provision in ANY region: three consecutive
  `aether cluster bootstrap` runs on 2026-09-10, in two different regions, died on
  `422 (invalid_input): unsupported location for server type` — a message naming neither the
  offending value nor the alternatives. Owner ruling: cloud providers retire instance types often,
  so ship no default at all.
- **`ClusterConfigWizard.defaultInstanceFor` is removed**, along with the AWS (`t3.medium`), GCP
  (`e2-medium`) and Azure (`Standard_B2s`) defaults in the same switch — identical rot. The
  interactive prompt now requires an answer and names the provider whose catalogue governs it; an
  empty or blank answer re-prompts instead of falling back. The `--non-interactive` path already
  refused a missing `--instance-type` and now raises the same cause as the wizard. Both render
  `ClusterInitError.InstanceTypeRequired`, which names the flag, the provider, and the reason
  (types are retired and availability varies by location).
  [verified: `ClusterConfigWizardTest$InstanceTypeHasNoDefault` — `run_cloudEmptyInstanceType_reprompts_andAcceptsTheNextAnswer`
  pins the absence positionally (a restored default would consume the next typed line as the
  credential env var), `run_cloudBlankInstanceType_reprompts_andAcceptsTheNextAnswer`,
  `message_namesTheFlagAndTheProviderCatalogue`]
- **The REGION default (`hel1`, `us-east-1`, …) is deliberately kept.** A provider's location set is
  small and effectively never retired, so it does not carry the same rot; the asymmetry is recorded
  at `ClusterConfigWizard.defaultRegionFor`.
- **`EnvironmentError.ProvisionFailed` now carries the requested instance type and location.** It
  previously rendered only `"Node provisioning failed: " + cause.getMessage()`, so the operator saw
  the provider's verbatim complaint and nothing about what was asked for — and Hetzner's complaint
  names neither field. Both are `Option<String>`: a refusal raised before the request is assembled
  (spot rejection, unresolved cluster name) and a status lookup on an existing instance have no
  requested spec, and the clause is omitted entirely rather than printed blank or guessed; a
  partially-known spec says so per field. The four cloud providers thread the pair from
  `createFrom`; Docker deliberately does not (no instance-type catalogue exists to consult).
  No valid-alternatives list is rendered: that would need either a network call on the failure path
  or a catalogue baked into this repository, which is the rot this change removes.
  [verified: `EnvironmentErrorTest$RequestedSpecRendering` (6 tests, calibrated both ways — a
  sentinel value is proven to reach the message before the realistic `cx21`/`hel1` case is
  asserted, and the absent case is pinned to the exact old string so the assertions cannot pass on
  boilerplate); `HetznerComputeProviderTest#provision_unsupportedLocationForServerType_namesRequestedTypeAndLocation`
  drives the real 422 through the provider]
- **Every concrete instance type in live docs and example configs is marked as an example.** An
  instance type in a doc is a snapshot of someone else's catalogue and rots the same way: 15 files,
  69 occurrences, 51 caveats added (one per copy unit — config sample line, table row, or prose
  record). `aether/docs/specs/cluster-management-spec.md` still shows `cx21`/`cx11`, which Hetzner
  has retired; the values are left in place and the caveat says plainly that they are retired,
  rather than substituting another snapshot. Historical records (`aether/docs/.internal/progress/`,
  `CHANGELOG.md`, `aether/docs/specs/future/`) are untouched — correct live specs, annotate history.
- **`aether/docs/specs/cluster-init-wizard-spec.md` was corrected, not just annotated**: its
  transcript showed `Instance type [cx22]:` — a default that no longer exists — and its validation
  table said only "Non-empty for cloud". Both now state that there is no default.
