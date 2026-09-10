### Fixed (2026-09-10 — `aether cluster init` produced configs that could not bootstrap, and silently swallowed input it ignored)

**Owner ruling:** the cloud placement parameters — **zone and instance type — are mandatory.** They
are not defaultable, and the reason is that the two fail in opposite and equally unacceptable ways:
a defaulted **instance type fails late**, at the provider API, after the operator has waited; a
defaulted **region or zone succeeds wrongly**, provisioning perfectly into a jurisdiction nobody
chose. Neither is acceptable for a parameter that decides where infrastructure physically lives.
This is the ruling, not an inference from the `cx21` breakage below — that breakage is an
illustration of the first failure mode, not the argument.

**The unifying defect:** `cluster init` must emit a config that `cluster bootstrap` can actually
use, and must never accept a flag or an answer it then discards. It did neither. Five faults, all
of the same shape — a decision the tool made silently, or an input it dropped without saying so —
plus a sixth, reported and not fixed, that explains why the first one was so hard to see.

- **The instance-type default named a server type Hetzner had DELETED.** The wizard offered
  `Instance type [cx21]`, so pressing Enter produced a config that could not provision in any
  region. `ClusterConfigWizard.defaultInstanceFor` is removed, along with the AWS (`t3.medium`),
  GCP (`e2-medium`) and Azure (`Standard_B2s`) defaults in the same switch. Both the wizard and
  `--non-interactive` now raise `ClusterInitError.InstanceTypeRequired`, naming `--instance-type`
  and the provider whose catalogue governs the value. **The defect is latent, not observed**: only
  the interactive wizard could reach the default, because `--non-interactive` already refused a
  missing `--instance-type`.
  [verified: `ClusterConfigWizardTest$InstanceTypeHasNoDefault`]

- **Correction to this entry's own original framing.** An earlier draft attributed three
  `422 (invalid_input): unsupported location for server type` failures of 2026-09-10 to the `cx21`
  default. That was wrong — those runs passed `cpx11` explicitly, and **their cause is still
  undiagnosed**. The fix stands on its own merits; that incident does not evidence it. Recorded
  rather than quietly dropped, because a wrong causal claim in a changelog outlives everyone who
  could correct it.

- **A provisioning failure now names the instance type and location that were requested.**
  `EnvironmentError.ProvisionFailed` rendered only `"Node provisioning failed: " + cause.getMessage()`,
  so against Hetzner an operator saw `422 (invalid_input): unsupported location for server type` and
  nothing about what had been asked for — naming neither the offending value nor the alternatives.
  It now carries both as `Option<String>`, omitting the clause entirely rather than printing a blank
  when a path has no requested spec (a spot rejection, an unresolved cluster name, a status lookup),
  and saying so per field when only one is known. The four cloud providers thread the pair from
  `createFrom`; Docker deliberately does not, having no instance-type catalogue to point at. No
  valid-alternatives list is rendered: that would need either a network call on the failure path or
  a catalogue baked into this repository, which is the rot this change removes.
  [verified: `EnvironmentErrorTest$RequestedSpecRendering` — calibrated both ways, a sentinel value
  proven to reach the message before the realistic `cpx11`/`hel1` case is asserted, and the absent
  case pinned to the exact prior string so the positive assertions cannot pass on boilerplate;
  `HetznerComputeProviderTest#provision_unsupportedLocationForServerType_namesRequestedTypeAndLocation`
  drives the real 422 through the provider]

- **Why that data belongs in the error and not in a log: the shipped CLI is bound to slf4j-NOP.**
  `aether.jar` ships `META-INF/services/org.slf4j.spi.SLF4JServiceProvider` =
  `org.slf4j.nop.NOPServiceProvider`, so **every log statement the CLI makes is discarded** —
  including `HetznerComputeProvider.logCreateRequest`, which already assembles the exact
  `serverType`, ssh-key count, firewall count and labels that were sent, and throws them away. The
  node jar binds log4j2 (`org.apache.logging.slf4j.SLF4JServiceProvider`), so the two disagree.
  **Reported, deliberately NOT fixed here** — adding a binding is a packaging decision affecting
  every CLI command, and it is the owner's call whether to ship one behind `--verbose` or to accept
  a log-silent CLI and require every operator-relevant fact to reach stdout.
  [verified: a probe run against each jar — the CLI reports `NOPLoggerFactory` with
  `isErrorEnabled=false` and emits nothing between markers; the same probe on `aether-node.jar`
  reports `Log4jLoggerFactory` and emits all three lines, which is the positive control proving the
  probe can see output at all]

- **`zone` and `region` are distinct concepts here, and no separate guard is needed for `zone` — but
  the mechanism differs PER PROVIDER, and the one traced first is the weakest.** `SourceProfile`
  carries three separate fields: `region` (provider-level default location) and `zone`/`zones`
  (per-source placement that overrides it, and what the bootstrap rotates through on capacity
  exhaustion). An absent zone produces no placement hint at all —
  `CloudProviderSupport.withZonePlacement` treats both empty and the literal `"default"` as "no
  hint" — so placement falls through to `ProviderDefaults.zone()`. What that resolves to is **not
  the same for all four providers**:

  | provider | `ProviderDefaults.zone()` | defence below `cluster init` |
  |---|---|---|
  | AWS | `""` | **yes** — `AWS_REGION` is a required credential field; blank fails factory construction with `CredentialsMissing` |
  | GCP | `""` | **yes** — same, `GCP_ZONE` |
  | Azure | `""` | **yes** — same, `AZURE_LOCATION` |
  | Hetzner | `config.region()` | **NO** — the factory validates only `api_token` |

  **The guarantee is enforced at `cluster init`, and on Hetzner there is no defence below it.** A
  Hetzner config built by any route other than `cluster init` — hand-written, generated by another
  tool, produced by an older Aether — can carry a blank region, and it reaches the wire as
  `"location": ""`. What Hetzner does with that was not established; it needs a live API call nobody
  has made. So the honest statement is that `cluster init` now refuses to *create* such a config,
  not that one cannot exist. The three providers that look weaker on the table above are in fact the
  ones with a second line of defence.

- **The region default silently chose a jurisdiction.** `defaultRegionFor` is removed for a
  different and stronger reason than catalogue rot: a defaulted region decides where the operator's
  data physically lives — a residency, sovereignty, latency and egress question. Note which failure
  mode is the dangerous one: a wrong instance type **fails loud** at the provider API, while a wrong
  region **provisions perfectly** and is found by an auditor. "We put your cluster in Helsinki
  because you did not say" is worse precisely because it succeeds.
  [verified: `ClusterConfigWizardTest$RegionHasNoDefault`]

- **The credential env var KEEPS its default**, and the distinction is the point. `HCLOUD_TOKEN` /
  `AWS_ACCESS_KEY_ID` are provider-defined conventional names, not catalogue entries, and the
  default names only *where a secret is read from* — it decides nothing about the deployed system.
  A wrong or unset var fails loud: the placeholder is left unresolved with a WARN
  (`PlaceholderConfigResolver.warnUnresolved`) and the provider then rejects the credential. The
  reasoning is recorded at `defaultCredentialEnvVarFor` so it is not re-litigated.

- **`--admin-cidr` was honoured for RESTRICTIVE only, so the DEFAULT preset emitted no admin rules
  at all.** `FirewallPresets.addAdminScoped` opens port 22 (bootstrap SSH) and the management port,
  and is reached from `standardRules` as well as `restrictiveRules` — the library was correct and
  the plumbing dropped the value. With `--firewall=standard` neither rule was emitted, which is a
  config whose bootstrap cannot reach the nodes it provisions: bootstrap deploys over SSH and its
  Phase 7 gate polls the management API on each node's public address. **Both paths were affected** —
  the wizard's STANDARD arm never prompted either. Now both collect it, and a cloud target refuses
  without one (`ClusterInitError.AdminCidrRequired`). It is **not auto-detected**: the batch path
  used to silently substitute `IpDetector.suggestAdminCidr()`, and a detected address is simply
  wrong behind NAT, on a dynamic IP, or when the cluster is administered from elsewhere — the same
  silent-decision failure as a defaulted region.
  [verified: `ClusterConfigWizardTest$StandardPresetCollectsAdminCidr`, which asserts ports 22 and
  8080 are present in the rules the chosen preset actually generates]

- **`--ssh-key` was accepted and ignored for cloud targets, and cloud configs carried no SSH key at
  all.** `init` wrote no SSH reference anywhere, while `SshKeyResolver.resolveOrFailIfCloud` refuses
  any cloud cluster whose key it cannot resolve — so `init` printed "Next: run bootstrap" for a file
  bootstrap then rejected. Each half was correct; their composition was not. A cloud target now
  requires `--ssh-public-key` (wizard: a required prompt) and the generator emits
  `[infrastructure.ssh] public_key_file`, the exact section and key `SshKeyResolver.collectSshKeyFiles`
  reads. `--ssh-key` — the PRIVATE key, meaningful only for existing SSH hosts — is now **refused**
  for a cloud target rather than silently swallowed (`ClusterInitError.FlagNotApplicable`).
  [verified: `ClusterInitBootstrapPairTest` runs the real resolver against a freshly generated
  config, with the `AETHER_SSH_KEY` env fallback stubbed out so the generated config is the only
  possible source of the key; a companion test strips the emitted section and asserts the refusal
  returns, proving the assertion bites]

- **Regression introduced and fixed in this change: a truncated stdin crashed the wizard.** Making
  every cloud prompt required means an empty answer re-asks — and at EOF each re-ask reads `""`
  again, so `aether cluster init < truncated-file` recursed until `StackOverflowError`. `Prompt`
  now distinguishes EOF from an empty line (`isInputExhausted`) and the wizard stops with
  `ClusterInitError.InputExhausted`, which points at `--non-interactive` and the required flags.
  Completing from defaults instead would have been the very failure this change removes.
  [verified: `ClusterConfigWizardTest$ExhaustedInputAborts`]
  Two smaller fixes came with it: a rejected region re-asks only the region (it used to re-enter the
  whole cloud step, re-asking the provider), and `StepResult.Abort` now carries its cause so an
  exhausted input is not reported as "aborted by operator".

- **Examples in live docs, configs and provisioning scripts are marked as examples**, because a
  concrete value is a snapshot of someone else's catalogue — or someone else's jurisdiction — and
  rots the same way. Two greppable markers, each counted over a stated space:

  | marker | lines | files |
  |---|---|---|
  | `EXAMPLE instance type` | 48 | 19 |
  | `EXAMPLE region` | 40 | 18 |

  **Space:** all tracked files except `changelog.d/`. **Unit:** matching lines, verified equal to
  occurrences (no line carries two markers). `*.java` was also stated as excluded in an earlier
  draft; measured, that exclusion removes **zero** lines for either marker, so it is dropped rather
  than kept as a discriminator that discriminates nothing.

  A further **13** caveats use prose wording — table cells, blockquotes, inline notes — and neither
  marker reaches them. They are enumerated file-and-line in the branch report; an earlier draft
  claimed they were enumerated and they were not, which made the figure unverifiable. Deriving them
  needs one correction that is easy to miss: a raw search returns **16**, of which **three are false
  positives** — the bare word `EXAMPLES` used as a help-text section heading in
  `tools/build-aether-vm-snapshot.sh`, `tools/provision-test-pg.sh` and `tools/cloud-reaper.sh` (the
  last of which this branch never touched).

  The four shell scripts that provision **real, billable** servers (`deploy-cloud.sh`,
  `driver-hetzner.sh`, `tools/build-aether-vm-snapshot.sh`, `tools/provision-test-pg.sh`) are
  included: they are the only places in the tree where a stale literal costs money rather than time.
  `driver-hetzner.sh` additionally carries a hardcoded **price** catalogue whose keys and rates are
  both snapshots, and which has already rotted once.

  `aether/docs/specs/cluster-management-spec.md` still shows `cx21`/`cx11`; the values are left in
  place and the caveat states plainly that Hetzner has retired them, rather than substituting a fresh
  snapshot that will rot in turn. History is untouched — `aether/docs/.internal/`, `CHANGELOG.md`,
  `specs/future/`, `specs/archive/`, and the two RFCs under `docs/rfc/`, which are design records of
  what was proposed at a point in time; correcting one would falsify the record. Neither RFC names a
  retired type (`cax21`, `cx22`, `cx23`, `cpx22`, `cpx32` are all current), so nothing there is
  copy-hazardous.

- **`getting-started.md` is annotated once, in prose, and deliberately not per-occurrence.** All of
  its instance-type mentions sit inside live-run confirmation records ("Live-run confirmed
  (2026-07-24, 5×cpx32 across fsn1/nbg1/hel1)"). Those are measurements of what was actually run, not
  examples to copy — annotating "check your catalogue" onto them would falsify a record. The
  correct-live-specs / annotate-history rule applied *within* a document rather than to whole files.

- **`aether/docs/specs/cluster-init-wizard-spec.md` was corrected, not merely annotated.** It showed
  `Region [fsn1]:` and `Instance type [cx22]:` — defaults that no longer exist — and its CLI example
  invoked `--firewall-cidr`, a flag the command does not declare. Both fixed, and the example now
  passes `--ssh-public-key`, so it is runnable rather than merely illustrative.
