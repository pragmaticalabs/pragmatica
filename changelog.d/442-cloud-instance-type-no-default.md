### Fixed (2026-09-10 — `aether cluster init` produced configs that could not bootstrap, and silently swallowed input it ignored)

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

- **Examples in live docs and configs are marked as examples**, because a concrete value in a doc is
  a snapshot of someone else's catalogue — or someone else's jurisdiction — and rots the same way.
  Two independently greppable caveats: **51** for instance types (15 files) and **38** for regions
  (14 files). `aether/docs/specs/cluster-management-spec.md` still shows `cx21`/`cx11`; the values
  are left in place and the caveat states plainly that Hetzner has retired them, rather than
  substituting a fresh snapshot that will rot in turn. History is untouched
  (`aether/docs/.internal/`, `CHANGELOG.md`, `specs/future/`, `specs/archive/`).

- **`aether/docs/specs/cluster-init-wizard-spec.md` was corrected, not merely annotated.** It showed
  `Region [fsn1]:` and `Instance type [cx22]:` — defaults that no longer exist — and its CLI example
  invoked `--firewall-cidr`, a flag the command does not declare. Both fixed, and the example now
  passes `--ssh-public-key`, so it is runnable rather than merely illustrative.
