# Deployment & Recovery — Aether Owns the Recovery Layer

**Audience:** operators deploying Aether on Docker, Kubernetes, Nomad, systemd, or any process supervisor.

**TL;DR:** Disable container/orchestrator restart policies on `aether-node`. Aether's Cluster Topology Manager (CTM) is the recovery layer. Two restart layers competing produce flapping nodes, masked failures, and incoherent failure semantics.

---

## 1 · The rule

When deploying `aether-node`:

| Platform | Setting | Rationale |
|----------|---------|-----------|
| Docker (`docker run`) | `--restart no` | Don't auto-restart on exit |
| Docker Compose | `restart: "no"` | Same |
| Kubernetes | `restartPolicy: Never` (Pod) — but see §5 for the proper k8s pattern | Don't auto-restart |
| Nomad | `restart { attempts = 0 }` or `mode = "fail"` | Don't auto-restart |
| systemd | `Restart=no` | Don't auto-restart |

**This is not optional.** It is required for cluster correctness, not a tuning preference.

---

## 2 · Why container/orchestrator restart is the wrong model

### 2.1 The layer-violation

A clustered system has two competing resilience layers when an `aether-node` process exits:

| Layer | Action on node exit |
|-------|---------------------|
| Container/orchestrator restart | Re-launch the same process on the same host within ~1s |
| Aether CTM auto-heal | Observe the node leave the presence-derived membership view → provision a fresh VM/host with a new node-id |

These actions are mutually exclusive. They cannot both run on the same failure event.

If both are enabled, the orchestrator wins the race (sub-second restart) before CTM's failure detector (SWIM ping interval × suspicion timeout = ~5–15s) ever observes the loss. Three concrete consequences:

#### Failures are masked from CTM

CTM's circuit breaker, SLA dashboards, alerting hooks, and disruption-budget calculator all consume `NODE_FAILED` / `NODE_LEFT` events. With Docker silently respawning the process, these events never fire. The cluster's view: "everything's fine." The actual reality: "we lost a node and got it back, but we don't know."

A node that crashes once every five minutes and is silently restarted by Docker looks indistinguishable from a node that has been up for 24 hours. Operators lose the failure signal.

#### Decommissioned nodes resurrect and flap

Once a node has departed and been replaced, the cluster has redistributed its slices, dropped it from the presence-derived membership view, and moved on. A departed node-id is not re-admitted as a present member just because a channel reopens — presence must be re-established through SWIM/QUIC, and re-admission is gated to prevent a dead node-id resurrecting. None of this involves a node-state KV record.

When a Docker-restarted aether-node tries to rejoin with the same node-id, the cluster rejects it. The container exits. Docker restarts it. The cluster rejects it. The container exits. **This is a flap loop.** The orchestrator generates churn the cluster has no productive use for.

This was the root cause of a multi-hour Hetzner cloud chaos test stall: kill the leader, container immediately respawns, leader rejoin rejected, container respawn-loops. Cluster waits 12+ minutes for a `NODE_FAILED` event that the orchestrator's restart prevents from ever firing.

#### Chaos engineering is structurally impossible

A chaos test that kills a process to verify recovery cannot run when the orchestrator silently undoes the kill. `docker kill` produces SIGKILL (exit 137). `unless-stopped` and `on-failure` both interpret 137 as a transient crash and restart. `docker stop` (SIGTERM) is the only signal `unless-stopped` honours as "intentional" — but production failures are rarely graceful. Tests that exercise only graceful shutdown miss the failure modes that matter.

### 2.2 The architectural intent

Aether is designed around a specific failure model — **terminal removal**:

> **A dead NodeId NEVER returns under the same identity.** When a node leaves the
> presence-derived membership view, the cluster terminally removes that NodeId and never
> re-admits it. Recovery is *always* a brand-new node with a new ULID NodeId, minted by
> CTM auto-heal on a fresh host. Each VM (or pod, or systemd-managed host) hosts exactly
> one aether-node; if that node dies, the host is dead from the cluster's perspective.

This is why container/process auto-restart must be **disabled**: a runtime that auto-restarts
a crashed node container under the SAME identity resurrects a NodeId the cluster has already
terminally removed — corrupting membership, because the model assumes a crashed node is gone
for good and that a fresh-ULID replacement has been (or will be) minted in its place.

This is not the same as "make the process highly available with automatic restart." Restart-on-failure is the right pattern for stateless services where any pod can serve any request. Aether nodes carry distributed state — slice ownership, partition assignments, consensus state, peer connections. Restarting a process and rejoining is meaningfully different from reprovisioning a node:

- **Restart** assumes the node-id and host are recoverable; just re-bind the same identity.
- **Reprovision** assumes the failure is bad enough that we're better off forgetting that identity entirely and onboarding a clean replacement.

Aether takes the second view. Empirically, the second view is right for distributed clusters, because:

1. Most "transient" crashes (OOM-kill, file-system errors, network stack hangs) re-occur on the same host.
2. Cluster state divergence after a crash is harder to detect than to avoid; provisioning a fresh node-id sidesteps the problem.
3. Resource exhaustion (disk full, log accumulation, leaked file descriptors) accumulates on the host; replacement clears it.

### 2.3 What about Docker daemon restart?

The single argument for `--restart unless-stopped` is "if the Docker daemon restarts (host reboot), the container should come back automatically."

Aether's answer: that's a host-level concern, handled at host level. If the host reboots, CTM observes the node missing and provisions a replacement. If the operator wants the same VM to come back, they provision a systemd unit at host level — but that systemd unit must also have `Restart=no`. The job of "make the host bring up an aether-node on boot" is distinct from "auto-restart the process if it exits."

In practice, most production deployments use immutable VMs/pods: hosts are cattle, not pets. A host that needs to be rebooted to restore a service is replaced, not nursed.

---

## 3 · What does Aether do instead

Aether's recovery flow:

1. **Failure detection** — SWIM gossip detects a peer is unreachable and QUIC transport confirms the disconnect; the node drops out of the presence-derived membership view (SWIM/QUIC via NTT). Node membership is never stored in or committed to the KV-Store. See `aether/docs/specs/archive/membership-architecture-v2-spec.md`.
2. **Departure** — once presence is lost, the node is removed from the cluster's membership view. There is no node-state KV write; the node-id simply ceases to be a present member.
3. **CTM reaction** — CTM observes the membership change, computes `actual = present-and-ready core count, desired = configured`, sees a deficit, and calls `provisionSingleNode()`.
4. **Replacement provisioning** — for cloud providers, this issues a `CreateServer` API call; for `manual` provisioning, this is a no-op and operator intervention is expected; for `docker` runtime (test fixtures), this runs `docker run` against the local daemon.
5. **New node onboarding** — the new VM/pod cloud-inits, downloads aether-node, joins via SWIM with the finalized PEERS list, becomes a present member, and reports `READY` on its heartbeat once synced.
6. **State convergence** — slices migrate, partitions rebalance, generation snapshot publishes the new topology.

The whole loop is observable, gated by configurable policies (disruption budget, circuit breaker, retry backoff), and produces structured events (`NODE_FAILED`, `NODE_LEFT`, `NODE_JOINED`, `GenerationChanged`) that operators can subscribe to.

A Docker `--restart unless-stopped` short-circuits steps 1–3, prevents step 4, and silences step 6.

---

## 4 · Operational guidance

### 4.1 Bootstrap

`aether cluster bootstrap` produces:
- For Hetzner / AWS / GCP / Azure — VMs whose cloud-init runs `docker run --restart no aether-node ...` (current implementation as of `1.0.0-rc1`).
- For docker / docker-compose test fixtures — `restart: "no"` on the aether-node service.
- For JVM mode (`type = "jvm"`) — no container at all; cloud-init runs `nohup java -jar aether-node.jar` directly. Process supervision is via `pkill` for restart, not a supervisor.

If you write your own deployment manifests (Kubernetes Pod spec, Nomad job, ECS task definition), apply the equivalent setting. See §5 for k8s.

### 4.2 Verification

After deploying, verify the restart policy on a live host:
```bash
docker inspect aether-node --format '{{.HostConfig.RestartPolicy.Name}}'
# Expected: no
```

If you see `unless-stopped`, `always`, or `on-failure`, your deployment will have the failure-masking problem described above.

### 4.3 What CTM expects of the platform

CTM assumes the platform provides:
- **Compute API** (`CreateServer`, `DeleteServer`, list-by-label) — used for provisioning.
- **Single-process supervision** — the host runs aether-node as PID 1 of its process group; when aether-node exits, the host can be observed as down.
- **No restart** — see §1.

CTM does NOT use:
- Health checks at the orchestrator level (Aether has its own).
- Liveness probes (likewise).
- Restart-on-failure semantics (likewise).
- Auto-scaling triggered by orchestrator metrics (CTM owns scaling).

You can configure platform-level health checks for observability (alert when a node is missing), but they must not trigger restart actions.

### 4.4 What about systemd / init?

If you run aether-node directly under systemd (no container), the unit file must include:
```ini
[Service]
Restart=no
# DO NOT use Restart=on-failure or Restart=always
```

The operator-supplied systemd may be appropriate for launching aether-node on host boot (same role as cloud-init's `docker run`), but it must not respawn on exit.

---

## 5 · Kubernetes pattern

Kubernetes operators reading this will note that `restartPolicy: Never` on a Pod is unusual.

The right k8s pattern is:

- **Don't deploy aether-node as a `Deployment` or `StatefulSet`.** These controllers exist precisely to maintain replicas via pod-restart. They will fight CTM.
- **Deploy each aether-node as a single `Pod` with `restartPolicy: Never`** OR equivalent — a `Job` with `backoffLimit: 0`, or a custom resource managed by a CTM-aware operator.
- **CTM's "provision a new VM" maps to "create a new Pod"** — your CTM compute provider implementation calls the k8s API to create a pod, not to update a Deployment's replica count.
- **The Pod hosts a single aether-node container** with `restart: no`-equivalent semantics (which is implicit when `restartPolicy: Never` is set on the Pod).

A first-class Aether-on-k8s operator (a Kubernetes Operator / CRD that wraps CTM's compute provider) is on the RC2/post-RC1 roadmap. Until then, k8s deployments require slightly more glue than VM deployments.

---

## 6 · Reading list

- `aether/docs/specs/cluster-generation-spec.md` — generation snapshot semantics, what CTM publishes after recovery
- `aether/docs/.internal/audits/membership-state-tracker-audit-2026-05-07.md` — single-source-of-truth design that requires the recovery layer to be Aether, not the orchestrator
- `ClusterTopologyManagerRecord` — CTM implementation, including the circuit breaker that prevents runaway provisioning
- `BootstrapPhaseDeploy.buildRestartCommand` — the cloud-init / SSH command that uses `--restart no`
- `UserDataTemplate.appendContainerRun` — the cloud-init template for container mode (emits `--restart no`)
- `SystemdUnitTemplate.generate` — the systemd unit template for JVM-on-host mode (emits `Restart=no`)

---

## 7 · Future direction

The user-facing model is: **"deploy Aether processes; Aether handles the rest."** Container orchestration is the historical bridge — useful for now because it provides a uniform compute API across hosting environments. Over time, expect Aether's native lifecycle management to take over more of what orchestrators currently provide:

- Native compute-provider abstraction already present (`HetznerComputeProvider`, `AwsComputeProvider`, etc.) — operators don't need k8s for VM provisioning.
- Native auto-heal already replaces orchestrator restart-on-failure.
- Native disruption budget replaces PodDisruptionBudget.
- Native cluster-formation replaces StatefulSet ordinal management.

What remains is host bootstrap (start the process on host boot) and image distribution. These can be handled by minimal cloud-init / systemd glue — no orchestrator required.

The principle stands: **one recovery layer, owned by the cluster.**
