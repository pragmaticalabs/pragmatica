#!/usr/bin/env python3
"""Arm X + arm K orchestrator (#2512, q6 §7.4 P6). Runs on the operator's machine; every host is reached over ssh.

The same code drives the cloud run and the dry run: the inventory (inventory.json) is the only difference. Hetzner calls live in
provision-hetzner.sh and nowhere else, so a dry run exercises everything here.

Counting path: every core JVM carries the m1probe agent (agent/M1ProbeAgent.java, which compiles the M-1 instrument from
aether/ember/src/test/.../M1Instrument.java). Mutations by family, transactions and slots are read from the agent on 127.0.0.1 of the
core's host; decisions, proposals and votes from /api/v1/metrics/comprehensive (RabiaMetricsCollector). The Micrometer counters
aether.consensus.commit / aether.consensus.batches have no caller in src/main and are never read, except by the dead-counter control.
"""
import argparse
import json
import os
import shlex
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
ARMXK = HERE.parent

# The keys whose min/max across reference cores a sample reports (M-1's `spread`).
SPREAD_KEYS = ["put.total", "remove.total", "engine.batches", "engine.slots", "tx.accepted", "tx.refused"]
RABIA_KEYS = ["decisionsCount", "proposalsCount", "voteRound1Count", "voteRound2Count", "syncSuccessCount", "syncFailureCount",
              "applyTimeoutCount"]
COMMUNITY_POLICY_SIZE = {True: 25, False: 5}  # N >= 50 -> 25, else 5 (M-1's communitySize)


def utc(ms=None):
    t = time.time() if ms is None else ms / 1000
    return datetime.fromtimestamp(t, timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%fZ")[:-4] + "Z"


def log(msg):
    print(f"[{utc()}] {msg}", flush=True)


class HarnessError(Exception):
    pass


class Run:
    """The inventory, the run directory and the live node table (persisted in run/state.json so a class can be re-entered)."""

    def __init__(self, run_dir, inventory=None):
        self.dir = Path(run_dir)
        self.dir.mkdir(parents=True, exist_ok=True)
        (self.dir / "evidence").mkdir(exist_ok=True)
        inv_path = self.dir / "inventory.json"
        if inventory is not None:
            inv_path.write_text(Path(inventory).read_text())
        self.inv = json.loads(inv_path.read_text())
        self.cfg = self.inv["config"]
        self.root = self.inv["remoteRoot"]
        self.state_path = self.dir / "state.json"
        self.state = json.loads(self.state_path.read_text()) if self.state_path.exists() else {"nodes": [], "slot": 0, "serial": {}}
        self.hosts = {h["name"]: h for h in self.inv["hosts"]}

    def save(self):
        self.state_path.write_text(json.dumps(self.state, indent=1))

    # ---- ssh ---------------------------------------------------------------------------------------------------------------------

    def ssh_args(self, host):
        h = self.hosts[host]
        return ["ssh", "-i", os.path.expanduser(self.inv["sshKey"]), "-o", "BatchMode=yes", "-o", "ConnectTimeout=15",
                "-o", "ServerAliveInterval=20", "-o", "StrictHostKeyChecking=accept-new",
                "-o", f"UserKnownHostsFile={self.dir / 'known_hosts'}",
                "-o", "ControlMaster=auto", "-o", f"ControlPath=/tmp/armxk-%C", "-o", "ControlPersist=900", h["ssh"]]

    def sh(self, host, command, timeout=120, check=True):
        full = f"export PATH={shlex.quote(self.inv.get('javaHome', '/usr/lib/jvm/java-25-openjdk-amd64'))}/bin:$PATH; {command}"
        result = subprocess.run(self.ssh_args(host) + [full], capture_output=True, text=True, timeout=timeout)
        if check and result.returncode != 0:
            raise HarnessError(f"ssh {host}: rc={result.returncode} cmd={command[:160]!r} stderr={result.stderr.strip()[:400]}")
        return result.stdout

    def scp_to(self, host, local, remote):
        h = self.hosts[host]
        args = self.ssh_args(host)
        opts = args[1:-1]
        subprocess.run(["scp", "-q"] + opts + [str(local), f"{h['ssh']}:{remote}"], check=True, timeout=1800)

    def pull(self, host, remote_paths, local_tgz):
        """tar the given paths (relative to the remote root) on the host straight into a local file."""
        paths = " ".join(shlex.quote(p) for p in remote_paths)
        with open(local_tgz, "wb") as out:
            r = subprocess.run(self.ssh_args(host) + [f"cd {shlex.quote(self.root)} && tar czf - --ignore-failed-read {paths} 2>/dev/null"],
                               stdout=out, timeout=1800)
        size = Path(local_tgz).stat().st_size
        if r.returncode not in (0, 1) or size < 64:
            raise HarnessError(f"evidence pull from {host} failed: rc={r.returncode} size={size}")
        return size

    # ---- nodes -------------------------------------------------------------------------------------------------------------------

    def nodes(self, role=None, alive=True):
        return [n for n in self.state["nodes"] if (role is None or n["role"] == role) and (not alive or n["alive"])]

    def node(self, node_id):
        return next(n for n in self.state["nodes"] if n["id"] == node_id)

    def allocate(self, node_id, role, host):
        slot = self.state["slot"]
        self.state["slot"] = slot + 1
        # SWIM listens on the cluster port + 100 (measured: core on 26000 also binds UDP 26100), so cluster ports take the first hundred
        # of every 200-block and their SWIM ports the second; TCP ports (management, app HTTP, agent) take their own band. Everything stays
        # below 32768, the Linux ephemeral floor, so another process's ephemeral socket cannot hold a node's port.
        base = self.cfg["portBase"]
        cport = base + 200 * (slot // 100) + slot % 100
        tcp = base + 4000 + 4 * slot
        if tcp + 3 >= 32768 or cport + 100 >= base + 4000:
            raise HarnessError("port band exhausted")
        n = {"id": node_id, "role": role, "host": host, "addr": self.hosts[host]["addr"], "cport": cport, "mport": tcp,
             "aport": tcp + 1, "agent": tcp + 2 if role == "core" else 0, "alive": False, "startedMs": 0}
        self.state["nodes"].append(n)
        self.save()
        return n

    def peers(self, extra=None):
        cores = self.nodes("core") + ([extra] if extra else [])
        return ",".join(f"{c['id']}:{c['addr']}:{c['cport']}" for c in cores)

    def start(self, n, peers):
        heap = self.cfg["coreHeap"] if n["role"] == "core" else self.cfg["workerHeap"]
        self.sh(n["host"], f"bash {self.root}/lib/node.sh start {self.root} {n['id']} {n['role']} {n['cport']} {n['mport']} {n['aport']} "
                           f"{n['agent']} {shlex.quote(peers)} {n['addr']} {heap}")
        n["alive"] = True
        n["startedMs"] = int(time.time() * 1000)
        self.save()

    def kill(self, n):
        out = self.sh(n["host"], f"bash {self.root}/lib/node.sh kill {self.root} {n['id']}")
        n["alive"] = False
        n["killedMs"] = int(time.time() * 1000)
        self.save()
        return out.strip()

    def stop_all(self):
        for n in self.nodes():
            self.sh(n["host"], f"bash {self.root}/lib/node.sh stop {self.root} {n['id']}", check=False)
            n["alive"] = False
        self.save()

    # ---- readings ----------------------------------------------------------------------------------------------------------------

    def api(self, core, method, path, body=None, content_type="application/json", timeout=30):
        """Management API call made ON the core's host; the key never leaves the host's secrets file."""
        data = f"--data-binary @- -H {shlex.quote('Content-Type: ' + content_type)}" if body is not None else ""
        cmd = (f"curl -s -m {timeout} -w '\\n%{{http_code}}' -X {method} -H \"X-API-Key: $(cut -d: -f1 {self.root}/secrets/api-keys)\" "
               f"{data} http://127.0.0.1:{core['mport']}{path}")
        if body is not None:
            full = f"export PATH=/usr/bin:$PATH; {cmd}"
            r = subprocess.run(self.ssh_args(core["host"]) + [full], input=body, capture_output=True, text=True, timeout=timeout + 30)
            out = r.stdout
        else:
            out = self.sh(core["host"], cmd, timeout=timeout + 30, check=False)
        text, _, code = out.rpartition("\n")
        return int(code) if code.strip().isdigit() else 0, text

    def api_json(self, core, path):
        code, text = self.api(core, "GET", path)
        if code != 200:
            raise HarnessError(f"GET {path} on {core['id']}: HTTP {code} {text[:300]}")
        return json.loads(text)

    def agent(self, core, path):
        if not core["agent"]:
            raise HarnessError(f"node {core['id']} carries no counting agent")
        out = self.sh(core["host"], f"curl -s -m 10 -w '\\n%{{http_code}}' 'http://127.0.0.1:{core['agent']}{path}'", check=False)
        text, _, code = out.rpartition("\n")
        if code.strip() != "200":
            raise HarnessError(f"agent {path} on {core['id']}: HTTP {code.strip()} {text[:200]!r} (a dead counting path is an error, never a zero)")
        return text

    def snapshot(self, core):
        values = {}
        for line in self.agent(core, "/m1/snapshot").splitlines():
            name, _, value = line.partition(" ")
            values[name] = value if name == "node" else int(value)
        if values.get("node") != core["id"]:
            raise HarnessError(f"agent on {core['id']} answered for {values.get('node')}")
        return values

    def last_ops(self, core, since_ms):
        ops = {}
        for line in self.agent(core, f"/m1/ops?since={since_ms}").splitlines():
            name, _, value = line.partition(" ")
            ops[name] = int(value)
        return ops

    def rabia(self, core):
        consensus = self.api_json(core, "/api/v1/metrics/comprehensive").get("consensus")
        if not consensus:
            raise HarnessError(f"no consensus block in /metrics/comprehensive on {core['id']}")
        return consensus

    def leader(self):
        votes = {}
        for c in self.nodes("core"):
            try:
                lid = self.rabia(c).get("leaderId")
            except (HarnessError, subprocess.TimeoutExpired, json.JSONDecodeError):
                continue
            if lid:
                votes[lid] = votes.get(lid, 0) + 1
        if not votes:
            return None
        lid, count = max(votes.items(), key=lambda kv: kv[1])
        live = {c["id"] for c in self.nodes("core")}
        return lid if lid in live and count * 2 > len(live) else None

    def host_sample(self, host):
        return self.sh(host, f"bash {self.root}/lib/node.sh sample {self.root}").strip()

    def emit(self, name, record):
        with open(self.dir / "evidence" / f"{name}.jsonl", "a") as f:
            f.write(json.dumps(record, sort_keys=True) + "\n")


def delta(before, after):
    out = {}
    for k, v in after.items():
        if isinstance(v, int) and k != "now.ms":
            d = v - before.get(k, 0)
            if d:
                out[k] = d
    return out


def await_cond(what, fn, timeout_s, period_s=3):
    deadline = time.time() + timeout_s
    last = None
    while time.time() < deadline:
        try:
            last = fn()
            if last:
                return last
        except (HarnessError, subprocess.TimeoutExpired, json.JSONDecodeError) as e:
            last = e
        time.sleep(period_s)
    raise HarnessError(f"timed out after {timeout_s}s waiting for {what} (last: {last})")


# ---- cluster lifecycle ---------------------------------------------------------------------------------------------------------------


def community_policy_toml(run, workers):
    size = COMMUNITY_POLICY_SIZE[workers >= 50]
    # The validator needs a source whose core sub-table names 5 distinct hosts and a container runtime (CL-07/PF-09/PF-22). The ssh source
    # here only carries the policy: its key path does not exist on any node and the image is unresolvable, so nothing can be launched from it.
    hosts = ", ".join(f'"10.255.0.{i + 1}"' for i in range(5))
    return f'''config_version = "1.0.0"
[cluster]
name = "armxk"
version = "1.0.0"
[cluster.core]
min = 3
max = 5
max_unavailable = 1
[source.static]
type = "ssh"
user = "armxk-no-login"
key = "/nonexistent/armxk-no-key"
[source.static.core]
hosts = [{hosts}]
[runtime.default]
type = "container"
image = "armxk.invalid/never-pulled:none"
[community_policy]
max_size = {size}
viability_floor = 1
allow_below_rf = true
max_communities = 40
dissolve_grace_seconds = 3
growth = true
'''


def disable_auto_heal(run, leader_core):
    code, text = run.api(leader_core, "POST", "/api/v1/cluster/topology/auto-heal/disable", body="")
    if code != 200 or '"enabled":false' not in text:
        raise HarnessError(f"auto-heal disable refused on {leader_core['id']}: {code} {text[:200]}")
    run.emit("lifecycle", {"kind": "auto-heal-disable", "core": leader_core["id"], "utc": utc(), "response": json.loads(text)})


def cores_up(run):
    if run.nodes("core"):
        raise HarnessError("cores already started in this run directory")
    core_hosts = [h["name"] for h in run.inv["hosts"] if h["role"] == "core"]
    if len(core_hosts) != 5:
        raise HarnessError(f"inventory must list exactly 5 core hosts, found {len(core_hosts)}")
    cores = [run.allocate(f"core-{i + 1}", "core", host) for i, host in enumerate(core_hosts)]
    peers = ",".join(f"{c['id']}:{c['addr']}:{c['cport']}" for c in cores)
    for c in cores:
        run.start(c, peers)
    for c in cores:
        await_cond(f"agent installed on {c['id']}", lambda c=c: run.agent(c, "/m1/health").startswith("ok "), 300, 5)
    lid = await_cond("a committed leader", run.leader, 300, 5)
    leader_core = run.node(lid)
    config = run.api_json(leader_core, "/api/v1/cluster/config")
    body = json.dumps({"tomlContent": community_policy_toml(run, run.cfg["workers"]), "expectedVersion": config["configVersion"]})
    code, text = run.api(leader_core, "POST", "/api/v1/cluster/config", body=body)
    if code != 200:
        raise HarnessError(f"community policy refused: {code} {text[:400]}")
    disable_auto_heal(run, leader_core)
    run.emit("lifecycle", {"kind": "cores-up", "utc": utc(), "leader": lid, "cores": [c["id"] for c in cores], "configApply": json.loads(text)})
    log(f"5 cores up, leader {lid}; community policy committed; auto-heal disabled")


def admit(run, node_id, role):
    lid = await_cond("a committed leader", run.leader, 240, 3)
    answer = run.agent(run.node(lid), f"/m1/admit?node={node_id}&role={role}").strip()
    if answer != "accepted":
        raise HarnessError(f"admission of {node_id} refused by {lid}: {answer}")
    return lid


def next_id(run, prefix):
    serial = run.state["serial"].get(prefix, 0) + 1
    run.state["serial"][prefix] = serial
    run.save()
    return f"{prefix}-{serial}"


def communities(run):
    lid = run.leader()
    if lid is None:
        raise HarnessError("no leader")
    return run.api_json(run.node(lid), "/api/v1/cluster/communities").get("communities", [])


def admitted_workers(run):
    return {m for c in communities(run) for m in c.get("members", [])}


def add_worker(run, host):
    n = run.allocate(next_id(run, f"w{host.split('-')[-1]}"), "worker", host)
    admit(run, n["id"], "worker")
    run.start(n, run.peers())
    return n


def workers_up(run):
    hosts = [h["name"] for h in run.inv["hosts"] if h["role"] == "worker"]
    target = run.cfg["workers"]
    for i in range(target - len(run.nodes("worker"))):
        add_worker(run, hosts[i % len(hosts)])
    ids = {w["id"] for w in run.nodes("worker")}
    await_cond(f"{len(ids)} workers in communities", lambda: ids <= admitted_workers(run), 900, 10)
    run.emit("lifecycle", {"kind": "workers-up", "utc": utc(), "workers": sorted(ids), "communities": communities(run)})
    log(f"{len(ids)} workers admitted into {len(communities(run))} communities")


# ---- the measurement window (M-1's measure(), over ssh) ------------------------------------------------------------------------------


def idle_window(run, ref, ms):
    before = run.snapshot(ref)
    sleep_s(ms / 1000)
    after = run.snapshot(ref)
    ops = run.last_ops(ref, before["now.ms"])
    ops.pop("now.ms", None)
    run.state.setdefault("idleOps", [])
    run.state["idleOps"] = sorted(set(run.state["idleOps"]) | set(ops))
    run.save()
    return {"ms": after["now.ms"] - before["now.ms"], "counts": delta(before, after)}


def sleep_s(seconds):
    time.sleep(max(0.0, seconds))


def settle(run, refs, t0, min_ms, quiet_ms, max_ms):
    idle = set(run.state.get("idleOps", []))
    while True:
        sleep_s(2)
        quiet = []
        elapsed = None
        for r in refs:
            ops = run.last_ops(r["core"], t0[r["core"]["id"]])
            now = ops.pop("now.ms")
            last = max([v for k, v in ops.items() if k not in idle] or [t0[r["core"]["id"]]])
            quiet.append(now - last)
            elapsed = now - t0[r["core"]["id"]] if elapsed is None else elapsed
        if elapsed >= min_ms and min(quiet) >= quiet_ms:
            return True, elapsed
        if elapsed >= max_ms:
            return False, elapsed


def measure(run, cls, rep, action, min_ms=None, quiet_ms=None, excluded=()):
    t = run.cfg["timing"]
    min_ms = t["minEventMs"] if min_ms is None else min_ms
    quiet_ms = t["quietMs"] if quiet_ms is None else quiet_ms
    refs = [{"core": c} for c in run.nodes("core") if c["id"] not in excluded]
    if not refs:
        raise HarnessError("no live reference core")
    idle_before = idle_window(run, refs[0]["core"], t["idleWindowMs"])
    hosts = sorted({r["core"]["host"] for r in refs})
    load_start = {h: run.host_sample(h) for h in hosts}
    leader_start = run.leader()
    for r in refs:
        r["before"] = run.snapshot(r["core"])
        r["rabiaBefore"] = run.rabia(r["core"])
    t0 = {r["core"]["id"]: r["before"]["now.ms"] for r in refs}
    start_utc = utc()
    workers_start = len(run.nodes("worker"))
    note = action() or {}
    settled, window_ms = settle(run, refs, t0, min_ms, quiet_ms, t["maxEventMs"])
    for r in refs:
        r["after"] = run.snapshot(r["core"])
        r["rabiaAfter"] = run.rabia(r["core"])
    load_end = {h: run.host_sample(h) for h in hosts}
    idle_after = idle_window(run, refs[0]["core"], t["idleWindowMs"])
    deltas = [delta(r["before"], r["after"]) for r in refs]
    spread = {k: [min(d.get(k, 0) for d in deltas), max(d.get(k, 0) for d in deltas)] for k in SPREAD_KEYS}
    record = {"kind": "sample", "class": cls, "rep": rep, "startUtc": start_utc, "windowMs": window_ms, "settled": settled,
              "workersStart": workers_start, "workersEnd": len(run.nodes("worker")), "cores": len(run.nodes("core")),
              "refCores": [r["core"]["id"] for r in refs], "leaderStart": leader_start, "leaderEnd": run.leader(),
              "raw": deltas[0], "perCore": {r["core"]["id"]: d for r, d in zip(refs, deltas)}, "spread": spread,
              "rabia": {r["core"]["id"]: {k: r["rabiaAfter"].get(k, 0) - r["rabiaBefore"].get(k, 0) for k in RABIA_KEYS} for r in refs},
              "loadStart": load_start, "loadEnd": load_end, "idleBefore": idle_before, "idleAfter": idle_after, "event": note}
    run.emit(cls.split("(")[0], record)
    log(f"{cls} rep {rep}: put.total={deltas[0].get('put.total', 0)} slots={deltas[0].get('engine.slots', 0)} "
        f"tx={deltas[0].get('tx.accepted', 0)} window={window_ms}ms settled={settled}")
    return record


# ---- classes ------------------------------------------------------------------------------------------------------------------------


def config_set(run, core, key, value):
    code, text = run.api(core, "POST", "/api/v1/config", body=json.dumps({"key": key, "value": value}))
    if code != 200 or "config_set" not in text:
        raise HarnessError(f"config set {key} on {core['id']}: {code} {text[:200]}")


def control_class(run):
    """Instrument controls. Positive: one CONFIG_SET must count exactly one put:ConfigKey on EVERY reference core (a dead path reads 0
    and fails here). Dead counter: the aether_consensus_commit / _batches series are recorded (the Micrometer counters have no caller)
    beside three live decision readings (agent engine.slots, Rabia decisionsCount, Prometheus consensus_decisions). Dead path: a
    snapshot from a port with no agent must raise."""
    reps = run.cfg["reps"]
    for rep in range(1, reps + 1):
        core = run.node(run.leader())
        rec = measure(run, "control-put", rep, lambda: config_set(run, core, f"armxk.control.{rep}", f"v{rep}"),
                      min_ms=run.cfg["timing"]["controlMinMs"], quiet_ms=run.cfg["timing"]["controlQuietMs"])
        counts = {cid: d.get("put:ConfigKey", 0) for cid, d in rec["perCore"].items()}
        if set(counts.values()) != {1}:
            raise HarnessError(f"control-put rep {rep}: put:ConfigKey per core {counts}, expected exactly 1 on every core")
    for c in run.nodes("core"):
        code, text = run.api(c, "GET", "/api/v1/metrics/prometheus")
        dead = [line for line in text.splitlines() if line.startswith("aether_consensus_commit") or line.startswith("aether_consensus_batches")]
        prom = {line.split()[0]: float(line.split()[1]) for line in text.splitlines() if line.startswith("consensus_decisions ")}
        live = run.snapshot(c)
        rabia = run.rabia(c)
        run.emit("control-dead-counter", {"core": c["id"], "utc": utc(), "http": code, "deadCounterLines": dead,
                                          "liveEngineSlots": live.get("engine.slots", 0), "livePutTotal": live.get("put.total", 0),
                                          "rabiaDecisions": rabia.get("decisionsCount", 0), "prometheusConsensusDecisions": prom.get("consensus_decisions")})
    # Dead-path control: an agent that does not answer must raise, never read as zero. Port 1 has no listener on any host.
    probe = dict(run.nodes("core")[0], agent=1, id="dead-path-probe")
    try:
        run.snapshot(probe)
        raise HarnessError("dead-path control: a snapshot from a port with no agent did not fail")
    except HarnessError as e:
        if "did not fail" in str(e):
            raise
        run.emit("control-dead-path", {"utc": utc(), "raised": str(e)[:300]})
    log("controls passed: one CONFIG_SET = 1 put:ConfigKey on every core, every rep")


def s0_class(run):
    t = run.cfg["timing"]
    for rep in range(1, run.cfg["reps"] + 1):
        measure(run, "s0-idle", rep, lambda: None, min_ms=t["s0WindowMs"], quiet_ms=t["s0WindowMs"])


def governors(run):
    lid = run.leader()
    return run.api_json(run.node(lid), "/api/v1/cluster/governors").get("governors", [])


def s3_class(run):
    """S3: a governor killed (SIGKILL, the VM stays). The window holds until the victim leaves every community; then a fresh worker id is
    admitted on the same host, outside the window (M-1's cleanupAndRestore)."""
    for rep in range(1, run.cfg["reps"] + 1):
        gov = sorted(governors(run), key=lambda g: g["governorId"])
        if not gov:
            raise HarnessError("no governor to kill")
        victim = run.node(gov[0]["governorId"])

        def action(victim=victim):
            out = run.kill(victim)
            await_cond(f"{victim['id']} gone from every community", lambda: victim["id"] not in admitted_workers(run), 900, 5)
            return {"victim": victim["id"], "host": victim["host"], "community": gov[0]["community"], "kill": out}

        measure(run, "s3-crash-governor", rep, action)
        replacement = add_worker(run, victim["host"])
        await_cond(f"{replacement['id']} in a community", lambda: replacement["id"] in admitted_workers(run), 600, 5)
        sleep_s(run.cfg["timing"]["restoreSettleMs"] / 1000)


def s4_class(run):
    """S4: the core leader killed. The window holds until a successor leader is committed; the victim is never a reference core (it is
    dead). Restore, outside the window: a fresh core id admitted by reservation and started with the live cores as peers."""
    for rep in range(1, run.cfg["reps"] + 1):
        victim = run.node(await_cond("a leader", run.leader, 120, 3))

        def action(victim=victim):
            out = run.kill(victim)
            successor = await_cond("a successor leader", lambda: (lambda l: l if l and l != victim["id"] else None)(run.leader()), 300, 3)
            return {"victim": victim["id"], "successor": successor, "kill": out}

        rec = measure(run, "s4-crash-core-leader", rep, action, excluded=(victim["id"],))
        disable_auto_heal(run, run.node(rec["event"]["successor"]))
        restore_core(run, victim["host"])


def restore_core(run, host):
    n = run.allocate(next_id(run, "core-r"), "core", host)
    admit(run, n["id"], "core")
    run.start(n, run.peers())
    await_cond(f"agent on {n['id']}", lambda: run.agent(n, "/m1/health").startswith("ok "), 300, 5)
    lid = run.leader()
    await_cond(f"{n['id']} counted as a core", lambda: n["id"] in run.api_json(run.node(run.leader()), "/api/v1/cluster/topology").get("coreNodes", []), 600, 5)
    run.emit("lifecycle", {"kind": "core-restored", "utc": utc(), "core": n["id"], "leader": lid})
    sleep_s(run.cfg["timing"]["restoreSettleMs"] / 1000)


def echo_coordinates(run):
    return run.inv["echoSlice"]  # {"group","artifact","version","file"}


def upload_echo(run):
    """Pushes the echo slice built from the run's checkout into the cluster repository (outside every window)."""
    e = echo_coordinates(run)
    core = run.node(run.leader())
    path = f"/repository/{e['group'].replace('.', '/')}/{e['artifact']}/{e['version']}/{e['artifact']}-{e['version']}.jar"
    out = run.sh(core["host"], f"curl -s -m 60 -o /dev/null -w '%{{http_code}}' -X PUT -H \"X-API-Key: $(cut -d: -f1 {run.root}/secrets/api-keys)\" "
                               f"--data-binary @{run.root}/bin/echo-slice.jar http://127.0.0.1:{core['mport']}{path}")
    if out.strip() not in ("200", "201", "204"):
        raise HarnessError(f"echo slice upload: HTTP {out.strip()}")
    run.emit("lifecycle", {"kind": "echo-uploaded", "utc": utc(), "path": path, "sha256": run.inv.get("echoSha256")})


def slice_instances(run, artifact):
    lid = run.leader()
    for entry in run.api_json(run.node(lid), "/api/v1/slices/status").get("slices", []):
        if entry.get("artifact") == artifact:
            return entry.get("instances", [])
    return []


def slice_states(run, artifact):
    """State counts per node role: {"core": {"ACTIVE": 3}, "worker": {...}}, roles from the harness's own node table."""
    roles = {n["id"]: n["role"] for n in run.state["nodes"]}
    out = {}
    for inst in slice_instances(run, artifact):
        role = roles.get(inst.get("nodeId"), "unknown")
        out.setdefault(role, {})
        out[role][inst.get("state")] = out[role].get(inst.get("state"), 0) + 1
    return out


def placed(run, artifact, placement, instances):
    states = slice_states(run, artifact)
    on = "core" if placement == "CORE_ONLY" else "worker"
    off = "worker" if placement == "CORE_ONLY" else "core"
    return states.get(on, {}).get("ACTIVE", 0) >= instances and not states.get(off)


def s6_class(run):
    """S6: one slice deployed to every worker (s6a, WORKERS_ONLY, instances = workers) and the cores-only control (s6c, CORE_ONLY,
    5 instances), whose core count must not grow with N. Each deploy is a blueprint POST then POST /api/v1/scale with the placement
    (a plain Put; M-1 commits the placement by leader transaction — counted inside the window either way). Undeploy is outside the window."""
    upload_echo(run)
    e = echo_coordinates(run)
    artifact = f"{e['group']}:{e['artifact']}:{e['version']}"
    for arm, placement, instances in (("s6a", "WORKERS_ONLY", len(run.nodes("worker"))), ("s6c", "CORE_ONLY", 5)):
        for rep in range(1, run.cfg["reps"] + 1):
            bp = f"armxk.s6:{arm}-r{rep}:1.0.0"

            def action(bp=bp, placement=placement, instances=instances):
                core = run.node(run.leader())
                toml = f'id = "{bp}"\n[[slices]]\nartifact = "{artifact}"\ninstances = {instances}\n'
                code, text = run.api(core, "POST", "/api/v1/blueprints", body=toml, content_type="application/toml")
                if code != 200:
                    raise HarnessError(f"blueprint apply: {code} {text[:300]}")
                if placement != "CORE_ONLY":
                    body = json.dumps({"artifact": artifact, "instances": instances, "placement": placement})
                    code2, text2 = run.api(core, "POST", "/api/v1/scale", body=body)
                    if code2 != 200:
                        raise HarnessError(f"placement {placement}: {code2} {text2[:300]}")
                converged = True
                try:
                    await_cond(f"{instances} ACTIVE {placement}", lambda: placed(run, artifact, placement, instances),
                               run.cfg["timing"]["maxEventMs"] / 1000, 5)
                except HarnessError:
                    converged = False
                return {"blueprint": bp, "placement": placement, "instances": instances, "converged": converged,
                        "states": slice_states(run, artifact), "nodes": slice_instances(run, artifact)}

            measure(run, f"{arm}-deploy", rep, action)
            core = run.node(run.leader())
            code, text = run.api(core, "DELETE", f"/api/v1/blueprints/{bp}")
            if code != 200:
                raise HarnessError(f"blueprint delete {bp}: {code} {text[:300]}")
            await_cond("undeployed", lambda: not slice_instances(run, artifact), 600, 5)
            sleep_s(run.cfg["timing"]["restoreSettleMs"] / 1000)


# ---- arm K ---------------------------------------------------------------------------------------------------------------------------


def core_readings(run):
    out = {}
    for c in run.nodes("core"):
        snap = run.snapshot(c)
        out[c["id"]] = {"rabia": run.rabia(c), "m1": snap, "readMs": int(time.time() * 1000)}
    return out


def readings_delta(before, after):
    out = {}
    for cid in before:
        if cid in after:
            rb, ra = before[cid]["rabia"], after[cid]["rabia"]
            mb, ma = before[cid]["m1"], after[cid]["m1"]
            out[cid] = {"seconds": (after[cid]["readMs"] - before[cid]["readMs"]) / 1000,
                        **{f"d_{k}": ra.get(k, 0) - rb.get(k, 0) for k in RABIA_KEYS},
                        "pendingBatchesEnd": ra.get("pendingBatches"),
                        "d_putConfigKey": ma.get("put:ConfigKey", 0) - mb.get("put:ConfigKey", 0),
                        "d_putTotal": ma.get("put.total", 0) - mb.get("put.total", 0),
                        "d_engineSlots": ma.get("engine.slots", 0) - mb.get("engine.slots", 0),
                        "d_engineBatches": ma.get("engine.batches", 0) - mb.get("engine.batches", 0)}
    return out


def armk(run):
    """Closed-loop CONFIG_SET ladder against the 5 cores, before any worker joins. Driver on a worker host (never a core)."""
    if run.nodes("worker"):
        raise HarnessError("arm K runs before any worker starts")
    k = run.cfg["armK"]
    driver_host = run.inv["driverHost"]
    if run.hosts[driver_host]["role"] == "core":
        raise HarnessError("the arm K driver must not run on a core host")
    target = run.node(run.leader())
    url = f"http://{target['addr']}:{target['mport']}/api/v1/config"
    sampler = (f"nohup bash -c 'while :; do bash {run.root}/lib/node.sh sample {run.root} >> {run.root}/samples-armk.log; sleep 4; done' "
               f">/dev/null 2>&1 & echo $! > {run.root}/sampler.pid")
    core_hosts = sorted({c["host"] for c in run.nodes("core")})
    for h in core_hosts:
        run.sh(h, sampler)
    try:
        ladder = k["concurrency"]
        for p in range(1, k["passes"] + 1):
            order = list(reversed(ladder)) if p == 2 else list(ladder)
            for c in order:
                armk_step(run, p, c, url, target, driver_host)
                armk_control(run, p, c)
    finally:
        for h in core_hosts:
            run.sh(h, f"kill $(cat {run.root}/sampler.pid) 2>/dev/null; rm -f {run.root}/sampler.pid", check=False)
    summarize_armk(run)


def armk_step(run, p, c, url, target, driver_host):
    k = run.cfg["armK"]
    cmd = (f"cd {run.root} && java driver/ArmKDriver.java {url} {run.root}/secrets/api-keys {c} {k['warmupS']} {k['measureS']} "
           f"armk.p{p}.c{c}. > {run.root}/armk-p{p}-c{c}.json 2> {run.root}/armk-p{p}-c{c}.err; echo $? > {run.root}/armk-p{p}-c{c}.rc")
    run.sh(driver_host, f"rm -f {run.root}/armk-p{p}-c{c}.*; nohup bash -c {shlex.quote(cmd)} >/dev/null 2>&1 &")
    sleep_s(k["warmupS"])
    before = core_readings(run)
    sleep_s(k["measureS"])
    after = core_readings(run)
    await_cond(f"driver p{p} c{c} exit", lambda: run.sh(driver_host, f"cat {run.root}/armk-p{p}-c{c}.rc 2>/dev/null", check=False).strip(),
               120, 2)
    rc = run.sh(driver_host, f"cat {run.root}/armk-p{p}-c{c}.rc").strip()
    result = run.sh(driver_host, f"cat {run.root}/armk-p{p}-c{c}.json", check=False).strip()
    if rc != "0" or not result:
        raise HarnessError(f"arm K driver p{p} c{c} rc={rc}: {run.sh(driver_host, f'tail -5 {run.root}/armk-p{p}-c{c}.err', check=False)}")
    driver = json.loads(result)
    loads = {h: run.host_sample(h) for h in sorted({cc["host"] for cc in run.nodes("core")})}
    record = {"kind": "armk-step", "pass": p, "c": c, "target": target["id"], "driverHost": driver_host, "startUtc": utc(driver["measureStartMs"]),
              "endUtc": utc(driver["measureEndMs"]), "driver": driver, "cores": readings_delta(before, after), "coreLoadAfter": loads,
              "readingsUtc": [utc(min(v["readMs"] for v in before.values())), utc(max(v["readMs"] for v in after.values()))]}
    run.emit("armk", record)
    lead = record["cores"].get(target["id"], {})
    log(f"arm K p{p} c={c}: {driver['opsPerSec']} ops/s p50={driver['p50Ms']} p99={driver['p99Ms']} errors={driver['errors']} "
        f"d_decisions={lead.get('d_decisionsCount')} d_putConfigKey={lead.get('d_putConfigKey')}")


def armk_control(run, p, after_c):
    before = core_readings(run)
    sleep_s(run.cfg["armK"]["controlS"])
    after = core_readings(run)
    run.emit("armk", {"kind": "armk-control", "pass": p, "afterC": after_c, "c": 0, "startUtc": utc(min(v["readMs"] for v in before.values())),
                      "cores": readings_delta(before, after)})


def summarize_armk(run):
    rows = [json.loads(l) for l in (run.dir / "evidence" / "armk.jsonl").read_text().splitlines()]
    steps = [r for r in rows if r["kind"] == "armk-step"]
    controls = [r for r in rows if r["kind"] == "armk-control"]
    idle = {}
    for r in controls:
        for cid, d in r["cores"].items():
            idle.setdefault(cid, []).append(d["d_decisionsCount"] / max(d["seconds"], 1e-9))
    summary = {"kind": "armk-summary", "utc": utc(), "passes": {}, "idleDecisionsPerSec": {c: sum(v) / len(v) for c, v in idle.items()}}
    for p in sorted({r["pass"] for r in steps}):
        table = []
        for r in sorted([s for s in steps if s["pass"] == p], key=lambda s: s["c"]):
            lead = r["cores"].get(r["target"], {})
            secs = max(lead.get("seconds", 0), 1e-9)
            ok = r["driver"]["ok"]
            table.append({"c": r["c"], "opsPerSec": r["driver"]["opsPerSec"], "p50Ms": r["driver"]["p50Ms"], "p99Ms": r["driver"]["p99Ms"],
                          "errors": r["driver"]["errors"], "decisionsPerSec": lead.get("d_decisionsCount", 0) / secs,
                          "decisionsPerSecNetOfIdle": lead.get("d_decisionsCount", 0) / secs - summary["idleDecisionsPerSec"].get(r["target"], 0),
                          "commitsPerSec": lead.get("d_putConfigKey", 0) / secs,
                          # a ratio of RATES: the core readings bracket the measured phase from outside, so their window is a few
                          # seconds wider than the driver's; counts over the two windows are not comparable, rates are
                          "commitsPerOp": (lead.get("d_putConfigKey", 0) / secs) / float(r["driver"]["opsPerSec"]) if ok else None})
        plateau = None
        for prev, cur in zip(table, table[1:]):
            grow = (float(cur["opsPerSec"]) - float(prev["opsPerSec"])) / max(float(prev["opsPerSec"]), 1e-9)
            if grow < 0.05 or (cur["p99Ms"] is not None and float(cur["p99Ms"]) > 1000):
                plateau = prev["c"]
                break
        summary["passes"][str(p)] = {"steps": table, "plateauC": plateau}
    run.emit("armk", summary)
    log(f"arm K summary written: plateau per pass {[(p, v['plateauC']) for p, v in summary['passes'].items()]}")


# ---- evidence ------------------------------------------------------------------------------------------------------------------------


def pull_evidence(run, label):
    """Copies every host's node logs and samples to the local run directory NOW (memory pull-cloud-evidence-immediately)."""
    out_dir = run.dir / "evidence" / f"pull-{label}"
    out_dir.mkdir(parents=True, exist_ok=True)
    seen = set()
    total = 0
    for name, h in run.hosts.items():
        if h["ssh"] in seen and run.inv["mode"] == "local":
            continue
        seen.add(h["ssh"])
        total += run.pull(name, ["nodes", "samples-armk.log", "armk-*.json", "armk-*.err", "bin/m1probe.jar.sources.sha256"],
                          out_dir / f"{name}.tgz")
    run.emit("lifecycle", {"kind": "evidence-pulled", "label": label, "utc": utc(), "bytes": total, "dir": str(out_dir)})
    log(f"evidence pulled for {label}: {total} bytes -> {out_dir}")


# ---- spend guard ---------------------------------------------------------------------------------------------------------------------


def spend_check(run, next_minutes):
    """Refuses to start a step whose estimated end crosses the cap. Only meaningful in hetzner mode; the estimate is a lower bound."""
    if run.inv["mode"] != "hetzner":
        return
    started = run.inv["provisionedAtMs"]
    hourly = run.inv["estimate"]["eurPerHour"]
    cap_eur = run.inv["estimate"]["capEur"]
    hours = (time.time() * 1000 - started) / 3_600_000 + next_minutes / 60
    billed = int(hours) + 1  # every started hour is billed per server
    spent = billed * hourly
    run.emit("lifecycle", {"kind": "spend-check", "utc": utc(), "billedHoursIfNextStepRuns": billed, "estimateEur": round(spent, 2), "capEur": cap_eur})
    if spent > cap_eur:
        raise HarnessError(f"spend guard: the next step would reach EUR {spent:.2f} > cap EUR {cap_eur:.2f}; stopping")


STEPS = {"control": (control_class, 6), "s0": (s0_class, 20), "s3": (s3_class, 25), "s4": (s4_class, 30), "s6": (s6_class, 45)}


def run_all(run):
    cores_up(run)
    spend_check(run, 60)
    control_class(run)
    spend_check(run, 40)
    armk(run)
    pull_evidence(run, "armk")
    workers_up(run)
    for name in run.cfg["classes"]:
        fn, minutes = STEPS[name]
        spend_check(run, minutes)
        fn(run)
        pull_evidence(run, name)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("command", choices=["run-all", "cores-up", "workers-up", "control", "armk", "s0", "s3", "s4", "s6", "pull", "stop-all",
                                        "leader"])
    ap.add_argument("--run-dir", required=True)
    ap.add_argument("--inventory")
    args = ap.parse_args()
    run = Run(args.run_dir, args.inventory)
    try:
        if args.command == "run-all":
            run_all(run)
        elif args.command == "cores-up":
            cores_up(run)
        elif args.command == "workers-up":
            workers_up(run)
        elif args.command == "armk":
            armk(run)
        elif args.command in STEPS:
            STEPS[args.command][0](run)
        elif args.command == "pull":
            pull_evidence(run, f"manual-{int(time.time())}")
        elif args.command == "stop-all":
            run.stop_all()
        elif args.command == "leader":
            print(run.leader())
    except HarnessError as e:
        log(f"FAILED: {e}")
        try:
            pull_evidence(run, "on-failure")
        except Exception as pull_error:  # the failure itself is the report; a failed pull is logged beside it
            log(f"evidence pull after failure also failed: {pull_error}")
        sys.exit(1)


if __name__ == "__main__":
    main()
