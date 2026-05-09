<!-- SPDX-License-Identifier: BUSL-1.1 -->
<!-- Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko -->

# Pre-pulled VM snapshots

Aether's cloud bootstrap drives every fresh VM through a cloud-init script that
installs Docker (or a JDK), then either pulls the `aether-node` container image
or downloads the `aether-node.jar`. Both steps happen on the critical path —
roughly **60–120 seconds per VM** depending on cloud capacity — and they run
identically on every VM in the cluster.

A *pre-pulled snapshot* shifts that work from per-VM cloud-init into a one-shot
operator action. The snapshot is a Hetzner image (or AWS AMI / GCP image / Azure
managed image) prepared by the operator, baked with the runtime payload already
in place. New VMs boot from that image, and the cloud-init logic — already
idempotent — skips the prep steps it would otherwise re-run.

Result: **30–60 seconds saved per VM provision**, multiplied by every cluster
bootstrap, scale-out, and CTM auto-heal cycle for the lifetime of the snapshot.

## When this matters

| Situation | Without snapshot | With snapshot |
|---|---|---|
| Initial 5-node bootstrap | 5 × 60–120s prep on critical path | 5 × 0–10s |
| CTM auto-heals a failed node | 60–120s degraded-capacity window | 5–15s |
| Scale-out under load | 60–120s per added node | 5–15s |
| Test cycle (chaos suites) | 4–5 × 60–120s per kill+replace | 4–5 × 5–15s |

## What the cloud-init does today

The cloud-init template (`UserDataTemplate.java`) is already idempotent for the
prep steps:

- Docker install — `if ! command -v docker`
- `docker pull` — `if ! docker image inspect "${AETHER_IMAGE}"`
- JDK install — `if ! command -v java || ! java -version | grep '"25'`
- JAR download — `if [ ! -s /opt/aether/aether-node.jar ]`

So a snapshot that has Docker pre-installed and the image pre-pulled simply
short-circuits all four guards. The cloud-init still writes the per-node
configuration TOML, the per-node `docker run`, and the readiness signal — those
are intentionally re-run because they encode per-node state.

## Building a snapshot

Use `tools/build-aether-vm-snapshot.sh`. It provisions a temporary VM, installs
the runtime payload, snapshots the VM, and deletes it.

```bash
# Container runtime, default Aether version (read from pom.xml)
tools/build-aether-vm-snapshot.sh build

# JVM runtime, explicit version override
tools/build-aether-vm-snapshot.sh build --runtime jvm --version 1.0.0

# What snapshots already exist for this version + runtime?
tools/build-aether-vm-snapshot.sh list

# Get the most recent snapshot id (suitable for scripting)
SNAP=$(tools/build-aether-vm-snapshot.sh latest --runtime container)
```

Each snapshot is tagged with three labels:

- `aether-snapshot=true`
- `aether-version=<version>`
- `aether-runtime=container|jvm`

The `latest` and `list` subcommands filter on those labels.

## Using a snapshot

In your `[source.<provider>.<role>]` block, replace the OS image name with the
snapshot id:

```toml
[source.hetzner-eu.core]
# Was: image = "ubuntu-22.04"
image = "<snapshot-id-from-build>"   # e.g. 174523891
```

The Hetzner API accepts either a name (`ubuntu-22.04`) or an integer image id
through the same `image` field; same shape for AWS `amiId`, GCP `sourceImage`,
Azure `image`. No code change is required.

For tests, the easier path is the env-var override exposed by
`run-tests.sh` — see the test-framework section below.

## Snapshot lifecycle

A snapshot is **pinned to one Aether version**. Refresh whenever:

- You upgrade Aether (new image tag / JAR build)
- You change the Aether runtime payload structure (e.g. switch `--restart` policy)
- The base OS image security baseline drifts (Hetzner refreshes ubuntu-22.04
  periodically)

To garbage-collect old snapshots:

```bash
# Keep the 3 newest per (version, runtime); destroy older.
tools/build-aether-vm-snapshot.sh prune-old --keep 3
tools/build-aether-vm-snapshot.sh prune-old --keep 3 --runtime jvm

# Or destroy a specific snapshot.
tools/build-aether-vm-snapshot.sh destroy --id 174523891
```

Snapshots cost a small amount per GB-month at Hetzner. The aether-node image is
~200 MB; a snapshot retains the full root volume (typically 20-40 GB
provisioned). Budget accordingly.

## What snapshots do NOT include

A snapshot contains:

- The base OS
- The runtime payload (Docker + image, or JDK + JAR)
- A marker file `/opt/aether/.snapshot-prepared` that records the version and
  build timestamp

A snapshot does NOT contain:

- The per-node `aether.toml` (composed at bootstrap time, includes cluster
  secret, peer list, slot id)
- The running container or JVM process (the snapshot is taken with the runtime
  payload pulled but **not** running — there's no aether-node process to capture)
- Any cluster state, KV-Store data, or persistent volumes

In other words: the snapshot is a *shorter cold start*, not a *warm node*. The
node is still bootstrapped from scratch on every provision; only the prep steps
are skipped.

## Test-framework integration

Set `AETHER_VM_SNAPSHOT_ID` (and optionally `AETHER_VM_SNAPSHOT_ID_JVM`) before
running `aether/tests/integration/run-tests.sh`. The runner rewrites the
`image = "ubuntu-22.04"` lines in the cloud TOMLs at session start.

```bash
# Build the snapshot once
SNAP=$(tools/build-aether-vm-snapshot.sh build --runtime container)

# Use it for cloud Container tests
export AETHER_VM_SNAPSHOT_ID="$SNAP"
cd aether/tests/integration && ./run-tests.sh --env cloud
```

## Other cloud providers

The `image` / `amiId` / `sourceImage` field exists in every cloud provider's
config record (`HetznerEnvironmentConfig.image`, `AwsEnvironmentConfig.amiId`,
`GcpEnvironmentConfig.sourceImage`, `AzureEnvironmentConfig.image`), and each is
passed through to the provider's create-server API. The mechanism is identical;
only the prep tooling differs:

- **AWS:** `aws ec2 create-image` from a prepared instance, or build with
  Packer. Reference the resulting AMI id via `amiId`.
- **GCP:** `gcloud compute images create --source-disk` from a prepared
  instance. Reference via `sourceImage`.
- **Azure:** Capture a managed image from a prepared VM. Reference via
  `image` (URN form).

`tools/build-aether-vm-snapshot.sh` only automates the Hetzner path today —
contributions for the others are welcome. The cloud-init template is shared
across providers, so the idempotency guards already work everywhere.
