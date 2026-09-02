# Getting Started with Aether

This is the front door: install the tools, scaffold a project, run it on your machine,
then ship it to a real cluster in the cloud. Four stages, in order:

1. **Install** — get the `jbct` and `aether` command-line tools
2. **Create** — scaffold a working slice project with `jbct init`
3. **Run locally** — build it and serve real traffic from Forge, Aether's local
   development simulator
4. **Deploy to the cloud** — provision an actual multi-node cluster and ship your
   slice to it

Each step below is something you run and something you should see — not a
description of intended behavior. Where the current release still has a rough
edge, this tutorial says so rather than skip it.

> **Looking to go deep on the code?** This tutorial gets you from nothing to a
> deployed cluster; it treats the generated `HelloWorld` slice as a black box.
> For a line-by-line walkthrough of the JBCT patterns — `Result`/`Promise`,
> sealed `Cause` errors, resource qualifiers, routing — see
> [My First Aether Slice](slice-developers/getting-started.md).

## What you need first

| Tool | Version | Why |
|------|---------|-----|
| JDK | 25+ | `jbct`'s installer requires a local JDK (no bundled-JRE path exists for it yet); your project is built with Maven |
| Maven | 3.8+ | Builds the generated project |
| curl | any | Fetches the installers and release archives |

You do **not** need a cloud account or credentials for stages 1–3 — those are
entirely local. Stage 4 needs a Hetzner Cloud account and API token (other
providers are supported too; see [Cloud Integration](reference/cloud-integration.md)).

---

## 1. Install

Aether ships two command-line tools from the same repository:

| Tool | Purpose |
|------|---------|
| `jbct` | Scaffolds projects, formats/lints code, drives the Maven build gate |
| `aether` / `aether-node` / `aether-forge` | Cluster management CLI, cluster node runtime, local dev simulator |

Install both with one command:

```bash
curl -fsSL https://raw.githubusercontent.com/pragmaticalabs/pragmatica/main/install.sh | sh
```

This installs `jbct` to `~/.jbct/bin` and the three `aether*` binaries to
`~/.aether/bin`, adding both to your `PATH` (it edits `~/.zshrc` or `~/.bashrc`
if it finds one).

**A genuinely nice property of the `aether` installer**: it tries a
self-contained platform archive first — a tarball with a bundled JRE, so the
`aether`/`aether-node`/`aether-forge` binaries themselves run without a
separately installed JDK. It only falls back to a JAR + JDK-dependent wrapper
if no archive is published for your OS/architecture. `jbct`'s installer has no
such fallback — it always requires a JDK — so in practice, for this tutorial,
install a JDK 25 first regardless; you'll need it to build your project in
Step 2 either way.

### Pinning a version

```bash
curl -fsSL https://raw.githubusercontent.com/pragmaticalabs/pragmatica/main/install.sh | sh -s -- --version 1.0.0-rc2
```

`--version` pins both tools to the same release. JBCT and Aether are
versioned independently, though, so use `--jbct-version` / `--aether-version`
if you need them to diverge (each overrides `--version` for its own tool):

```bash
curl -fsSL https://raw.githubusercontent.com/pragmaticalabs/pragmatica/main/install.sh | sh -s -- --jbct-version 1.0.0-rc2 --aether-version 1.0.0-rc3
```

Unpinned installs resolve to the newest **published** release, correctly
ranking GA over `rc-N` over `beta` over `alpha` and always excluding the
moving `*-candidate` tag — an explicit version fails loudly with a link to the
releases page rather than silently downloading nothing. (Confirmed
end-to-end: with an rc3 candidate tag present, an unpinned install still
resolves to `1.0.0-rc2`, the newest actually-published release.)

> **Pinning to a `-candidate` tag** — used internally to validate a release
> before it's tagged GA, most readers won't need this — works the same way
> as pinning any other version (`--version 1.0.0-rc3-candidate`), but has one
> sharp edge: a `jbct` binary *built from* a `-candidate` version bakes that
> exact version string into the `pragmatica-lite.version` / `aether.version`
> / `jbct.version` properties of every `pom.xml` it generates. Candidate
> versions are deliberately never published to Maven Central, so
> `mvn clean verify` on a project scaffolded by a candidate-pinned `jbct`
> fails immediately with `Plugin
> org.pragmatica-lite:jbct-maven-plugin:1.0.0-rc3-candidate or one of its
> dependencies could not be resolved`. Work around it by pointing `jbct init`
> at the underlying numbered version explicitly:
>
> ```bash
> jbct init -g org.example hello \
>   --jbct-version 1.0.0-rc3 --aether-version 1.0.0-rc3 --pragmatica-version 1.0.0-rc3
> ```

### Verify

```bash
jbct --version
aether --version
jbct init --help
```

Both top-level `--version` invocations print a version and exit 0.
`jbct init --help` is worth checking too: `InitCommand`'s own `--version`
option used to collide with picocli's auto-added `-V/--version`, breaking
`init --help` entirely — that's fixed now, and the output no longer
advertises a dead `--slice` flag (the scaffold has been slice-by-default all
along; `--slice` never did anything).

---

## 2. Create your project

```bash
jbct init -g org.example hello
cd hello
```

`-g` sets the Maven `groupId` (default `com.example`); the directory name
becomes the `artifactId`. **By default `jbct init` scaffolds a full Aether
slice project** — a working slice, its config, and a local dev-server script —
not a bare Maven skeleton. (If you want the bare skeleton instead — 3 files,
no Aether wiring — pass `--no-slice`.)

You'll get:

```
hello/
├── pom.xml                      # Maven build (Java 25, JBCT plugin)
├── jbct.toml                    # JBCT formatter/linter config
├── README.md                    # Generated project README
├── .gitignore
├── forge.toml                   # Local Forge cluster config (5 simulated nodes)
├── aether.toml                  # Runtime resource config
├── run-forge.sh                 # Build + start local Forge
├── start-postgres.sh            # Optional: Postgres via Docker
├── stop-postgres.sh
├── deploy-forge.sh              # mvn install to local ~/.m2
├── deploy-test.sh                # Push to a test cluster
├── deploy-prod.sh                # Push to a production cluster
├── schema/init.sql
└── src/
    ├── main/
    │   ├── java/org/example/hello/helloworld/
    │   │   └── HelloWorld.java  # Generated slice: interface + impl in one file
    │   └── resources/
    │       ├── slices/HelloWorld.toml
    │       ├── org/example/hello/helloworld/routes.toml
    │       └── META-INF/dependencies/...
    └── test/
        ├── java/.../HelloWorldTest.java
        └── resources/log4j2-test.xml
```

`jbct init` finishes by printing next steps — this is close to being the rest
of this tutorial:

```
Slice project initialized successfully!

Next steps:
  1. cd hello
  2. ./run-forge.sh
  3. curl http://localhost:8070/api/hello/World
  4. Dashboard: http://localhost:8888
```

### Build it

```bash
mvn clean verify
```

Use `verify` (or `install`), not bare `package`. JBCT's format/lint gate is
bound to the `install`/`verify` phases of the Maven lifecycle, not `package` —
`mvn package` will report success even when formatting is broken, because it
never runs the check. `run-forge.sh` (next step) runs `mvn clean install`
internally for exactly this reason: it needs the real gate, not the
optimistic one.

A fresh, untouched scaffold passes this cleanly: `BUILD SUCCESS`,
format-check reports "All files are properly formatted," lint reports "0
error(s), 0 warning(s), 0 info(s)." You will see two unrelated **compiler**
warnings on every fresh build (an unmapped `Cause` enum constant, and a
`routes.toml` error pattern that never matches anything) — they come from
the generated template itself, not from your code, and don't fail the
build.

---

## 3. Run it locally with Forge

Forge is Aether's local development simulator: it runs a full cluster
topology — by default 5 simulated nodes — **inside a single JVM**. That's
genuinely useful for fast iteration and chaos testing, but it is a simulation
of distribution, not a distributed deployment; every node shares the same
process, memory, and failure domain. Don't read "5 nodes" in the dashboard as
"5 machines." (Stage 4 gives you real, separate nodes.)

```bash
./run-forge.sh
```

This builds your project (`mvn clean install -DskipTests`), locates the
`aether-forge` binary, and starts it against `forge.toml`, printing:

```
Starting Aether Forge...
  Dashboard:  http://localhost:8888
  App HTTP:   http://localhost:8070
  Management: http://localhost:5150

Test: curl http://localhost:8070/api/hello/World
```

Forge deploys your slice from a **blueprint**, resolved by artifact
coordinates — `groupId:artifactId:version:blueprint` — from the local Maven
repository the build step above installed to, *not* from a file path.
That's what the `mvn clean install` inside `run-forge.sh` is actually for.
Forge itself is invoked like this:

```bash
aether-forge --config forge.toml --blueprint org.example:hello:1.0.0-SNAPSHOT:blueprint
```

`run-forge.sh` runs exactly this command for you — confirmed by reading the
generated script directly and by a live run resolving and serving traffic.

Within roughly 30 seconds of a clean start, the cluster is up and your slice
is serving:

```bash
curl http://localhost:8070/api/hello/World
# {"greeting":"Hello, World!"}
```

### Look at the cluster

Point the `aether` CLI at Forge's management port:

```bash
aether -c localhost:5150 status
```

This returns the cluster's live state as clean JSON — node roster, leader,
generation. It's the same command you'll run against a real cloud cluster in
Stage 4, just against a different `-c host:port`.

Open the dashboard at `http://localhost:8888` for the visual view: node
roster, per-slice instance counts, request metrics.

### A note on security

By default, Forge runs your slice's `app-http` server with no
authentication (`security_mode` `NONE`) — no scaffold file sets this, it's
Forge's own compiled-in default for local dev. That's correct for local
iteration and this tutorial, and wrong for anything reachable outside your
laptop. Security-mode values are parsed case-insensitively, so `none`,
`NONE`, and `None` are all the same setting — you'll see both cases used
across this tutorial and the reference docs; it doesn't matter which you
write. See [Per-Route Security](slice-developers/getting-started.md#per-route-security)
for `api-key`/`jwt` modes before you expose a slice to real traffic.

---

## 4. Deploy to the cloud

`aether cluster bootstrap` provisions real infrastructure — VMs, networking,
and the Aether runtime on top — from a single TOML file. It's a real
operation with real cost; nothing about it is simulated the way Forge is.

```bash
aether cluster bootstrap my-cluster.toml --wait
```

This asks for a typed `Continue? [y/N]` confirmation before it spends any
money — expected when you're running it by hand. Scripting it (CI, a piped
shell) needs `--yes` to skip the prompt; without it the prompt reads EOF and
the run aborts, exiting non-zero so a scripted caller notices rather than
sailing on believing a cluster exists.

Bootstrap runs seven phases (validate → upload SSH keys → provision → collect
addresses → deploy runtime → cluster formation → post-bootstrap), persisting
state to `~/.aether/clusters/<name>/bootstrap-state.json` after each one so a
failed run can `--resume`. By default, if any phase fails, everything it
provisioned is torn down automatically; pass `--keep-on-failure` if you want
to SSH in and look before it's gone.

Live-run confirmed (2026-07-24, 5×cpx32 across fsn1/nbg1/hel1, ~12-15 minutes
wall clock end-to-end, ~€0.10 total): all seven phases green, quorum 3/5, all
nodes READY, an API key auto-minted and persisted to
`~/.aether/clusters/<name>/api-key`.

### The config file

A bootstrap TOML has four kinds of section: `[cluster]` and `[cluster.core]`
(cluster identity and initial node count), one `[source.<name>]` block per
provisioning source (cloud provider + its compute/network settings),
`[operations.*]` (rollout behavior), and `[runtime.*]` (how the node process
itself runs on each VM — `container` with a Docker image, or `jvm` with a
downloaded jar).

This tutorial deliberately doesn't inline a full example here — copy-pasting
a cloud config that drifts from what the CLI actually validates is exactly
the trap this project is trying to close. Use the schema reference and
minimal working Hetzner example at
[Bootstrap Configuration Reference](reference/bootstrap-config.md) for the
full picture — it's field-verified against the parser
(`ClusterBootstrapConfigParser.java`) and explicit that the bootstrap-config
TOML is a *different* schema from a running node's own `aether.toml`: you
never hand-write the `[cloud.*]` blocks documented in
[Configuration Reference](reference/configuration.md) — bootstrap generates
them for you, once, when it provisions each node. Two things worth knowing
before you open it, because they're the two ways a bootstrap silently fails
validation rather than teaching you the fix:

- **`security_mode = "NONE"`** is required under each source's
  `node_config.app-http` block for a cluster you're standing up without a
  pre-existing auth setup — omit it and the bootstrap's cluster-config write
  is rejected with 401/403, with no pointer back to this setting as the
  cause.
- **`jar_url`** under `[runtime.jvm]` needs to be hand-pinned whenever the
  cluster version you're bootstrapping doesn't match a real published release
  tag (for example, testing against a release candidate).

Runtime modes, for reference: `container` VMs install Docker and run
`ghcr.io/.../aether-node:<tag>`; `jvm` VMs install Eclipse Temurin 25
themselves via cloud-init and run the downloaded jar directly. Either way,
**you don't hand-install a JDK on the cloud nodes** — bootstrap provisions
the runtime as part of the "Deploy Runtime" phase.

### Check it

```bash
aether -c <a-node-ip>:8080 status
```

Same command as Stage 3, pointed at a real node's management port instead of
`localhost:5150`. Port 8080 is `PortsConfig.DEFAULT_MANAGEMENT_PORT` —
live-confirmed against a real bootstrapped node (2026-07-24, 5×cpx32 across
fsn1/nbg1/hel1): `status` returns clean JSON, all 5 nodes READY, a leader,
and `quorate: true`.

### Ship a slice to it

The scaffold's `deploy-prod.sh` works out of the box now (the broken
`aether artifact push --env prod` call — [#515](https://github.com/pragmaticalabs/pragmatica/issues/515)
— is gone). It builds and verifies, asks for a typed `yes` before doing
anything (this is a real deploy), then pushes and deploys through the
`aether` CLI. It doesn't hardcode a cluster address — point it at yours
first, either per-invocation with `-c` or for the session with
`AETHER_ENDPOINT`:

```bash
export AETHER_ENDPOINT=<a-node-ip>:8080
./deploy-prod.sh
```

Under the hood that's two CLI calls, confirmed from the generated script,
which you can also run by hand:

```bash
aether -c <a-node-ip>:8080 artifacts push org.example:hello:1.0.0-SNAPSHOT
aether -c <a-node-ip>:8080 blueprints deploy org.example:hello:1.0.0-SNAPSHOT --wait
```

`artifacts push` reads the blueprint jar from your local `~/.m2/repository`,
discovers every slice artifact it references, and pushes all of them to the
cluster's artifact repository in one shot.

Then call it, exactly like you did against Forge — only the address changes:

```bash
curl http://<a-node-ip>:8070/api/hello/World
# {"greeting":"Hello, World!"}
```

That is the same slice, the same code, and the same request you ran on your
laptop in Stage 3, now answered by a five-node cluster in a German datacenter.
Nothing about the slice changed to get here.

> **If you are on an rc3 build older than the #522 fix**
> ([#522](https://github.com/pragmaticalabs/pragmatica/issues/522)):
> `blueprints deploy --wait` kept reporting `PENDING` and then declared a 300s
> timeout even when the deployment had in fact completed — it matched the
> blueprint coordinates against the slice list, which only ever carries the
> derived slice artifacts, so it could never observe completion. If you hit
> that, don't assume the deploy failed — check
> `aether -c <a-node-ip>:8080 slices status`; if the slice reads `ACTIVE` /
> `HEALTHY`, it deployed and the `curl` above will answer.

For zero-downtime updates to an already-running slice, see `aether deploy`
(canary/blue-green/rolling) in the [CLI reference](reference/cli.md#deploy).

### Tear it down

This cluster costs money for every hour it exists — destroy it when you're
done with it:

```bash
aether cluster destroy --cluster=<name> --yes
```

It drains and shuts down the nodes, terminates the cloud resources, and
removes the local registry entry, printing a summary that names each VM it
cleaned up. If cloud cleanup *does* fail, the command now says so, exits
non-zero, and deliberately **keeps** the registry entry so you can simply
re-run it — a summary line reading `Registry entry: KEPT` means resources may
still be billing. From a repo checkout,
`tools/cloud-reaper.sh --cluster <name>` (add `--destroy` to delete) is a
label-driven safety net that finds anything a failed teardown left behind.

---

## Where to go next

- **[My First Aether Slice](slice-developers/getting-started.md)** — the code
  deep-dive: JBCT patterns, HTTP routing, resource qualifiers, testing
- **[Forge Guide](slice-developers/forge-guide.md)** — dashboard, load
  testing, chaos injection
- **[Bootstrap Configuration Reference](reference/bootstrap-config.md)** —
  full TOML schema and provider examples
- **[Cloud Integration](reference/cloud-integration.md)** — provider
  credential model, auto-scaling, multi-cloud
- **[CLI Reference](reference/cli.md)** — every `aether`/`jbct` command
- **[Troubleshooting](slice-developers/troubleshooting.md)** — common errors

<!--
CHECKPOINT (gs-writer, phase 2 COMPLETE incl. leg 4, 2026-07-24) — not part
of the published tutorial.

STATUS: All 8 VERIFY-P2 markers dispositioned, all removed from the body.
Legs 1-3 (gs-writer, sandboxed) + leg 4 (team-lead, live Hetzner) both
executed for real. Tutorial ready for the wrap-up commit batch.

FINAL MARKER TABLE:
1. Top-level jbct/aether --version + jbct init --help unaffected by the
   picocli collision — CLEARED (exit 0, collision confirmed fixed, --slice
   no longer advertised).
2. #511 scaffold format-check — CLEARED (BUILD SUCCESS, format-check clean,
   lint 0/0/0; 2 non-fatal compiler warnings noted as template-quality
   observation, not a build failure).
3. #513 run-forge.sh blueprint coordinates — CLEARED
   (org.example:hello:1.0.0-SNAPSHOT:blueprint confirmed via source read +
   live successful run).
4. Root install.sh --version/--jbct-version/--aether-version passthrough —
   CLEARED (unpinned resolves 1.0.0-rc2; pinned-to-candidate installs both
   tools; exactly 1 PATH block across 2 runs).
5. #515 deploy-prod.sh replacement — CLEARED (regenerated script confirmed:
   artifacts push + blueprints deploy --wait, -c/AETHER_ENDPOINT-driven, no
   hardcoded address).
6. security_mode default/casing — CLEARED (case-insensitive parser;
   Forge/Ember hardcodes NONE; scaffold sets nothing — no discrepancy).
7. Stage-4 management port 8080 — CLEARED (was code-confirmed only; now
   live-confirmed by team-lead's leg-4 run against a real bootstrapped node,
   2026-07-24, 5×cpx32 fsn1/nbg1/hel1: status returns clean JSON, quorate:
   true).
8. Leg-4 live Hetzner run — CLEARED (team-lead, 2026-07-24, full 7-phase
   bootstrap green, quorum 3/5, all nodes READY, ~12-15 min wall clock,
   ~€0.10 total; cleaned up via tools/cloud-reaper.sh).

NEW FINDINGS FROM THE LIVE LEG-4 RUN — both were filed, FIXED, and re-verified
live the same day (2026-07-24); the tutorial text now describes the fixed
behavior, and this note records the history:
- #520 (filed, FIXED, CLOSED): under security_mode = "NONE" (this tutorial's
  own default cluster config), `artifacts push` 401'd — NONE ignores API keys
  entirely (whoami with a valid key still comes back anonymous/VIEWER/
  authenticated:false) while artifact publication hard-required OPERATOR/ADMIN,
  a role structurally unholdable in that mode. Root cause: two half-overlapping
  dev switches (security_mode vs AETHER_INSECURE_DEV_MODE); the publication
  gate consulted only the latter. Fixed by unifying them — NONE now implies the
  dev-mode posture for that gate, loudly (a WARN per unauthenticated publish).
  RE-VERIFIED LIVE on cluster gs-revalidate: push succeeded, slice went
  ACTIVE/HEALTHY on 3 nodes, and `curl /api/hello/World` returned
  {"greeting":"Hello, World!"} — so the "call it" step in Stage 4 is now a real
  executed command, not a reconstruction.
- #521 (filed, FIXED, CLOSED): `aether cluster destroy` failed VM cleanup while
  exiting 0 and dropping the registry entry. Root cause: the CLI mined the
  bootstrap TOML for a PLURAL `[sources.X]` header while the canonical section
  is SINGULAR `[source.X]`, so every persisted cleanup handle carried an empty
  credential map (all five clusters on the dev machine, back to 2026-07-11).
  Fixed at three levels; RE-VERIFIED LIVE: fresh state file carries
  "api_token": "HCLOUD_TOKEN", destroy exited 0 having actually terminated all
  5 VMs, registry entry removed, zero fallback warnings.
- Bootstrap's interactive `Continue? [y/N]` abort also exited 0 (same #521
  batch) — now exits non-zero; the tutorial's bootstrap note reflects that.
- #522 (filed, FIXED — live re-verification still pending): `blueprints deploy
  --wait` reported a 300s PENDING timeout while the deployment had actually
  completed (slice ACTIVE/HEALTHY, DEPLOYMENT_COMPLETED events, endpoint
  serving). Root cause: the wait gate never queried a deployment-status
  surface at all — it fetched the slice list and substring-matched the
  BLUEPRINT coordinates against it, which cannot match the derived SLICE
  artifacts, so it emitted a hardcoded "PENDING" forever. Fixed by polling
  `GET /api/blueprints/status/{id}` (`overallStatus`), which the node derives
  from the same replicated DeploymentMap that backs `slices status`. Covered
  by unit tests in both directions; NOT yet re-run against a live cluster, so
  Stage 4 keeps the caveat scoped to pre-fix builds.
- #523 (filed, OPEN): `aether artifacts list` returns HTTP 400; not referenced
  in the tutorial since the tutorial never needs it.

WHAT WAS ACTUALLY RUN, LEGS 1-3 (gs-writer, sandbox: scratchpad/p2-home,
HOME override; real ~/.m2 shared on purpose — rc3 artifacts aren't on Maven
Central yet, same accepted scope as the phase-1 dry-run):
- Install, unpinned: resolved 1.0.0-rc2 (correct — candidates excluded).
- Install, pinned --version 1.0.0-rc3-candidate (both tools, via a locally
  patched copy of root install.sh pointed at origin/release-1.0.0-rc3
  instead of /main, since the fix branch hadn't merged to main this
  session — disclosed test-methodology substitution, not a product change):
  exactly one PATH block added across two installer runs (dedup guard
  confirmed).
- `jbct init -g org.example hello` then `mvn clean verify`: FAILED on first
  try with the candidate-pinned CLI — `Plugin
  org.pragmatica-lite:jbct-maven-plugin:1.0.0-rc3-candidate ... could not be
  resolved` (candidate versions are never published to Central; documented
  gotcha-ledger edge, not a regression). Re-ran with
  --jbct-version/--aether-version/--pragmatica-version 1.0.0-rc3 → BUILD
  SUCCESS. Added a callout for this in the tutorial's pinning section.
- Real file listing captured twice (jbct init's own console output +
  `find .`): no `generate-blueprint.sh` (tutorial previously listed one
  that doesn't exist — fixed), scaffold does include `README.md` and
  `.gitignore` (previously missing from the tutorial's tree — fixed).
- ./run-forge.sh (after fixing my own sandbox's PATH — HOME override alone
  doesn't isolate PATH, and `command -v aether-forge` picked up a stale
  real install on the dev machine until PATH was explicitly prepended with
  the sandbox bin dirs; test-rig mistake, not a product bug): hello 200,
  status clean JSON, dashboard 200. Confirmed forge data lands under
  $AETHER_HOME/forge-data with the new crash-durable log line (not the old
  WARN wall) — not written into the tutorial body since it wasn't a prior
  claim there. The optional "broken --blueprint gives a loud non-zero exit"
  arm was NOT attempted — judged non-essential given time/cost.

CARRIED FORWARD FROM PHASE 1 (still accurate, not re-derived here):
placement decision (top-level, not under slice-developers/), BSL-header
omission rationale, the cloud-bootstrap-TOML vs. running-node-aether.toml
schema distinction (#514), ports 8888/8070/5150 for Forge.
-->
