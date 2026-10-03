# Floating-IP Load Balancing Runbook

> **Planned (1.0.0-rc5, #1867 — not implemented).** This runbook describes `load_balancer =
> "floating_ip"` as specified in the
> [floating-IP load-balancing spec](../../specs/floating-ip-load-balancing-spec.md). The commands
> `aether ingress …`, the `[source.X.floating_ip]` table and the assignment table do not exist in
> rc4. Numbers marked *estimate* are replaced with measured values by the Hetzner acceptance run
> (spec §14) before this runbook ships.

## What this mode gives you, and what it does not

You publish a fixed set of public IPs in DNS. The cluster keeps each IP attached to one healthy
eligible node and moves it when that node dies, becomes unready or is drained. The cluster has no
DNS integration and does not change your records.

| You get | You do not get |
|---|---|
| Failover with no DNS change and no wait for resolver caches | Zero-loss failover: clients using a failed node's IPs see errors for the failover window |
| Make-before-break for planned drains and upgrades | Preserved connections: TCP connections to a moved IP are reset |
| One public endpoint set for the whole cluster, served by regular nodes | Health-based DNS steering or even traffic spread across IPs |
| An assignment table you can inspect and verify against the provider console | Cross-region failover: pool IPs are bound to a zone/region |

Failover window per IP = **detection + departure commit + one provider move**. *Estimate*:
12–20 s for a dead node with default SWIM timeouts (suspect timeout 10 s); for an unready node,
`readiness_grace` (5 s) plus one move. During that window, roughly *(IPs held by the failed node) /
(pool size)* of new connections fail — more or less, because DNS spread is uneven.

## 1. Capacity planning

**Pool size.** Choose the pool size `P` from the share of clients you accept losing to one node
failure, then check it against eligible nodes `E`:

| Want | Choose |
|---|---|
| One failure affects ≤ 1/3 of clients | `P ≥ 3`, and `E ≥ P` so each node holds ≤ 1 IP |
| Even load across ingress nodes | `P` a multiple of `E`. With `rebalance = "on_imbalance"` the cluster moves one IP at a time, paced, only when two nodes differ by 2 or more IPs; zone binding and `manual` mode can leave it less even (spec §8.4) |
| Survive `k` node failures with all IPs served | `E − k ≥ 1`, and `(E − k) × maxIpsPerNode ≥ P` |

`maxIpsPerNode` by provider: Hetzner no documented per-server limit (unverified); AWS, GCP and Azure **1** without guest network
setup (spec §10.2). On GCP a node holding a pool IP loses its own ephemeral external IP.

**Per-node capacity.** An ingress node terminates TLS and forwards to the slice's hosting node
when the slice is not local. Size eligible nodes for:

- `peak RPS × (IPs it may hold after k failures) / P`, times a skew factor for uneven DNS spread
  (start with 1.5 and adjust from measurements);
- TLS handshakes (full handshakes spike right after a failover, because every client of the moved
  IP reconnects at once);
- one extra internal hop for non-local slices.

**Eligibility.** The default is `eligible_roles = ["worker"]`, which keeps TLS and forwarding load
off the consensus cores. Add `"core"` only for small clusters without workers. Spot nodes are
refused (a spot reclaim gives no make-before-break).

**Provider quotas and cost.** Floating IPs are billed per IP per month and limited per project.
Check the project's quota in the provider console before choosing `P`. Leave headroom of one IP for
swaps (see §6).

**API budget.** Steady state is one pool listing per `verify_interval` (default 30 s, about 120
calls/hour per source) plus rare moves. The same API token also serves auto-heal provisioning and
firewall management. If you raise the verify rate, check the provider's hourly limit.

## 2. Provisioning order

Order matters because DNS must never point at an IP that the cluster cannot serve yet.

1. **Allocate the pool IPs** in the provider, in the zone/location of the source's nodes:
   ```bash
   hcloud floating-ip create --type ipv4 --home-location fsn1 --name app-pool-1 \
       --label aether-pool=<cluster-name>
   hcloud floating-ip create --type ipv6 --home-location fsn1 --name app-pool-v6 \
       --label aether-pool=<cluster-name>
   ```
   For IPv6 Hetzner allocates a /64. Pick one address inside it (for example `<prefix>::10`); that
   address goes in the pool and in DNS.

   > **Do not label pool IPs with `aether-cluster` or `aether-node-id`.** `tools/cloud-reaper.sh
   > --destroy` unassigns and **deletes** every floating IP carrying either label. A deleted floating
   > IP's address is gone, may be allocated to another customer, and your DNS would then send your
   > users to them. Use a separate label such as `aether-pool`.

2. **Declare the pool** in the cluster config:
   ```toml
   [source.eu-workers]
   type = "cloud"
   provider = "hetzner"
   load_balancer = "floating_ip"

   [source.eu-workers.floating_ip]
   pool = ["203.0.113.10", "203.0.113.11", "2001:db8:1::10"]
   eligible_roles = ["worker"]
   ```
3. **Set up TLS** for the app listener on every eligible node (§4) and the **firewall** (§5).
4. **Bootstrap** (`aether cluster bootstrap`). Preflight refuses IPs the account does not own or
   whose zone matches none of the source's zones.
5. **Wait for convergence**: every IP shows `CONVERGED` in `aether ingress floating-ips`.
6. **Test each IP directly** before DNS exists:
   ```bash
   for ip in 203.0.113.10 203.0.113.11; do
     curl -sS --resolve app.example.com:443:$ip https://app.example.com/<a-route> -o /dev/null -w "$ip %{http_code}\n"
   done
   curl -sS -g --resolve 'app.example.com:443:[2001:db8:1::10]' https://app.example.com/<a-route> -o /dev/null -w "%{http_code}\n"
   ```
   (Port 443 assumes your `app_http` port or a port mapping in front of it; substitute the actual
   port otherwise.)
7. **Publish DNS** (§3).

## 3. DNS records

```
app.example.com.  3600  IN  A     203.0.113.10
app.example.com.  3600  IN  A     203.0.113.11
app.example.com.  3600  IN  AAAA  2001:db8:1::10
```

- **One record per pool IP, all under one name.** Clients get the full set and can retry another
  address when one fails.
- **TTL.** The cluster moves IPs without touching DNS, so the TTL only governs how fast a pool *change* reaches
  clients. 300–3600 s is reasonable. Lower it a day ahead of a planned pool change (§6), raise it
  back afterwards.
- **No DNS health checks that remove records.** A provider health check that pulls a record during
  the failover window does not speed anything up (the IP will be served again within the window)
  and its removal then outlives the window by the TTL. If your DNS provider forces health checks,
  set their failure threshold above the documented failover window.
- **Mixed families.** Only publish AAAA if the pool has a served IPv6 address. Clients that prefer
  IPv6 will use it first (RFC 6724 / Happy Eyeballs), so an IPv6 address with fewer holders gets a
  larger share of dual-stack clients. Balance the families separately (the cluster already does).
- **Uneven spread is normal.** Some resolvers and clients always take the first record or cache one
  answer. Do not size capacity from `RPS / P`.

## 4. TLS on every ingress node

Every eligible node terminates TLS for every pool IP, because any of them can become the holder.

1. Obtain a certificate covering the DNS name(s) clients use (`app.example.com`). Do not put the
   pool IPs in the certificate unless clients connect by IP.
2. Deliver it to every eligible node at the same path, and configure the app listener:
   ```toml
   [app-http.tls]
   cert_path = "/etc/aether/tls/app.crt"
   key_path  = "/etc/aether/tls/app.key"
   ```
   Deliver it through `[source.<name>.node_config]` plus your secret distribution so that nodes
   provisioned later by auto-heal receive it too. A node without `[app-http.tls]` falls back to the
   cluster-CA certificate (CN = its node id), which public clients reject, and HTTP/3 falls back to a
   self-signed one. Nothing in the cluster checks that nodes carry the same certificate, so the
   loop below is the only check.
3. **Renewal**: renew on every eligible node before expiry. Check every node, not only current
   holders:
   ```bash
   for n in <eligible node addresses>; do
     echo | openssl s_client -connect $n:<app_http> -servername app.example.com 2>/dev/null \
       | openssl x509 -noout -enddate | sed "s/^/$n /"
   done
   ```

## 5. Firewall

- Admit the `app_http` port (TCP, plus UDP if you serve HTTP/3) to the pool addresses on every
  eligible node, not only on current holders. On Hetzner, the Aether-managed firewall opens
  `app_http` for `floating_ip` sources when no `[source.X.firewall]` is declared; if you declare one,
  include the app port yourself.
- On AWS, the Aether-managed ingress opens it the same way as on Hetzner. On GCP/Azure open it
  yourself (VPC firewall rule / NSG); Aether manages no ingress there and warns at bootstrap.
- Never expose the management port or the cluster port on pool IPs. They are reached on each
  node's primary address.
- **HTTP/3 caveat** (*unverified*, spec §10.3): on Hetzner the pool address is an alias. If HTTP/3
  to a pool IP fails while HTTP/2 works, stop advertising HTTP/3 (Alt-Svc) for that name until it is
  fixed; TCP traffic is unaffected.

## 6. Adding and removing pool IPs

> rc4's `aether cluster apply` rejects source-field changes; the pool change path is an open owner
> decision (spec Q3). The procedure below assumes `apply` accepts pool changes, and on Hetzner a
> rolling replacement of eligible nodes to install the new aliases.

**Add an IP** (DNS goes last):

1. Allocate the IP in the right location, labelled `aether-pool=<cluster>` (§2 step 1).
2. Add it to `pool` and apply the config. The table shows the new IP `UNSERVED` until a node is
   eligible for it.
3. Hetzner only: existing nodes do not have the alias. Replace eligible nodes one at a time (each
   replacement gets the new pool in its cloud-init): drain each through
   `POST /api/v1/nodes/drain/<id>` (make-before-break applies) and let auto-heal replace it. Do not
   use the CLI's rolling restart / drain-destroy until #1868 is fixed: it waits for a state the
   server never emits and ends in `DrainTimeout`. On AWS/GCP/Azure skip this step.
4. Wait for `CONVERGED`, test the IP directly (§2 step 6).
5. Add the DNS record.

**Remove an IP** (DNS goes first):

1. Remove the DNS record.
2. Wait at least **2 × the record's TTL**, then watch the IP's traffic fall to near zero (some
   clients ignore TTLs; decide how long a tail you accept).
3. Remove it from `pool` and apply the config. Its assignment key is deleted; it stays attached to
   its last holder until you unassign it.
4. Unassign and release it in the provider:
   ```bash
   hcloud floating-ip unassign <id>
   hcloud floating-ip delete <id>
   ```
   Deleting an IP gives the address back to the provider pool permanently. Make sure no DNS record,
   allow-list or client config anywhere still points at it.

**Swap an IP** (for example, to change zone): add the new one fully, then remove the old one. Never
remove first.

## 7. Client guidance

Give these to the teams that call the service:

- **Resolve all records and retry across them.** On connect failure or reset, retry another
  address. Most HTTP clients do this only if they receive multiple addresses; do not pin one IP.
- **Short connect timeouts** (1–3 s). A dead VM drops packets, and the client otherwise waits its
  default connect timeout (often 30 s or more) before trying the next address.
- **Expect connection resets** during failovers, drains and rebalances. Long-lived connections
  (WebSocket, gRPC streams, HTTP/2) must reconnect with backoff and jitter.
- **Retry only what is safe to repeat.** A request whose connection reset may or may not have been
  processed. Inside the cluster an ingress node also retries a forward whose target died
  mid-flight, so a non-idempotent request may already have been started once (unverified; spec B14). Retry idempotent requests; give non-idempotent ones an idempotency key.
- **Respect DNS TTLs** so that pool changes (§6) reach you.

## 8. Failover drill

Run after bootstrap, after any pool change, and periodically (quarterly). Schedule it; it causes
real client errors for one IP for the failover window.

1. **Baseline.** `aether ingress floating-ips`: all `CONVERGED`. Note the holder of the IP you will
   test, `<ip>` → `<node>`.
2. **Start a probe** against that IP from outside the cluster, recording failures with timestamps:
   ```bash
   while true; do
     printf '%s ' "$(python3 -c 'import datetime;print(datetime.datetime.utcnow().strftime("%H:%M:%S.%f")[:12])')"   # date +%N is GNU-only
     curl -sS -m 2 --resolve app.example.com:443:<ip> https://app.example.com/<a-route> \
          -o /dev/null -w '%{http_code}\n' 2>&1 | tail -1
     sleep 0.2
   done | tee drill.log
   ```
3. **Planned move first** (lower risk): drain `<node>` through the management API
   (`POST /api/v1/nodes/drain/<node>`). Expected: `<ip>` moves before the node reports DRAINING;
   the probe shows at most the connection resets of the move, no run of failures.
4. **Unplanned failure**: on another holder, stop the node process hard (`kill -9` on the JVM, or
   `docker kill aether-node`). Expected: failures for the failover window, then 200s from the new
   holder.
5. **Measure**: in `drill.log`, the first and last failure around the event give the observed
   window. Compare with the documented window; record both in your ops log.
6. **Verify the table** shows the new holder `CONVERGED`, and the provider agrees
   (`hcloud floating-ip describe <id>` → `server`).
7. Let auto-heal replace the killed node, or replace it yourself; it does not get its old IP back
   unless the pool is imbalanced by two or more.

Count what must NOT happen, not what should: a drill passes when the probe shows no failure outside
the windows above, not when it shows some successes.

## 9. Troubleshooting

### Health checks for ingress nodes

- `/health/live` and `/health/ready` are on the **management** port (8080), not the app port, and
  are per node.
- **#1869:** `/health/ready` stays `UP` while a node drains, even though its app port answers 503
  for up to the 30 s drain grace. Until #1869 is fixed, do not use `/health/ready` to decide whether a
  node can take traffic. Use the leader's view instead: `GET /api/v1/nodes/lifecycle`
  (SYNCING / READY / DRAINING). That view is the one the cluster itself uses to move IPs.
- An external monitor probing a pool IP measures the holder of that moment. Pair it with the
  assignment table to tell which node failed.

### Read the assignment table

```bash
aether ingress floating-ips            # table
aether ingress floating-ips -o json    # for scripts
```

| Status | Meaning | Do |
|---|---|---|
| `CONVERGED` | Provider holder = committed holder | nothing |
| `MOVING` | A move is committed and the provider has not confirmed it yet | wait one `verify_interval`; if stuck, see "move does not complete" |
| `DIVERGED` | Provider holder ≠ committed holder after a verify pass | the next pass re-assigns; if it persists, check provider errors (`lastError`) and console changes |
| `UNSERVED` | No eligible node for this IP | see "IP unserved" |
| `UNVERIFIED` | The leader has not read the provider since it became leader, or the read failed | check API reachability and the token |

### Check against the provider

```bash
hcloud floating-ip list                       # every IP, its server and home location
hcloud floating-ip describe <id>              # server, home_location, labels
hcloud server describe <server-id>            # which Aether node it is (aether-node-id label)
```

The committed holder in the table is an Aether node id; map it to a server with the node's
`aether-node-id` label. Someone moving an IP in the console shows up as `DIVERGED` and is reverted
by the next pass. To move an IP deliberately, drain the holder or use rebalance; console moves do
not last.

### IP unserved

Work through the eligibility predicate (spec §7.2) for the IP's source:

1. Are there live nodes with an eligible role in that source? (`aether cluster topology`)
2. Are they READY? (`GET /api/v1/nodes/lifecycle` on the leader)
3. Is any of them being drained?
4. Zone: does `hcloud floating-ip describe <id>` show a home location / network zone that matches
   the nodes' locations?
5. Hetzner: does the node have the alias? On the node:
   ```bash
   ip -br addr show | grep -F '<ip>'
   ```
   Missing: the node booted before the IP was added, or its cloud-init failed
   (`cloud-init status --long`, `/var/log/cloud-init-output.log`). Replace the node.
6. Is every eligible node already at its provider's per-node IP limit (AWS/GCP/Azure: 1)?

### Move does not complete

- `lastError` mentions 429 / rate limit: the provider budget is exhausted. Find other consumers of
  the token; moves resume with backoff.
- `lastError` mentions not found: the IP was deleted or is in another project/account. Check the
  token's project.
- Provider holder flips between two servers: two writers. Look for an operator script or a second
  cluster using the same IP and token. The cluster corrects divergence every `verify_interval`;
  persistent flipping means something outside the cluster keeps moving it back.

### Traffic reaches the holder but fails

- TLS errors: the holder lacks the certificate or has an old one (§4).
- Connection refused on the holder: the app port is not listening; check node readiness and logs.
- Works by primary address, fails by pool IP: firewall rule missing for the pool address (§5), or
  (Hetzner) the alias is missing on that node.
- HTTP/3 only fails: see §5's caveat.

### After a leader change

Nothing should move (spec FIP-06, design intent, pinned by spec test T3). If IPs moved right after
a leader change, record the event sequence (`aether events`) and file an issue.

## 10. Reference

| Key | Default | Meaning |
|---|---|---|
| `load_balancer` | `none` | `none` \| `external` \| `floating_ip` |
| `floating_ip.pool` | — | Pool addresses (IPv4 and/or IPv6) |
| `floating_ip.eligible_roles` | `["worker"]` | Roles that may hold pool IPs |
| `floating_ip.rebalance` | `on_imbalance` | `on_imbalance` \| `manual` |
| `floating_ip.rebalance_pacing` | `5m` | Minimum gap between automatic rebalance moves |
| `floating_ip.make_before_break_timeout` | `60s` | Max wait for moves before a drain proceeds |
| `floating_ip.verify_interval` | `30s` | Provider verification period |
| `floating_ip.readiness_grace` | `5s` | Non-READY duration before a move |

Events: `FloatingIpMoved`, `FloatingIpMoveFailed`, `FloatingIpUnserved`,
`FloatingIpMakeBeforeBreakExpired`. Metrics: `aether_floating_ip_moves_total`,
`aether_floating_ip_unserved`, `aether_floating_ip_diverged`,
`aether_floating_ip_provider_call_seconds`. Alert on `unserved > 0` and on `diverged > 0` lasting
longer than two verify intervals.
