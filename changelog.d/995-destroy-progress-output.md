### Fixed (2026-09-11 — #995: cluster destroy emitted nothing for minutes while paid servers kept running)

- **Diagnosed, then fixed.** Against a healthy 3-node cloud cluster, `cluster destroy --cluster <name>
  --yes` produced zero output for ~2.5 minutes and deleted nothing, and was killed. Reading the path:
  `performDestruction`'s **first** statement is `fetchNodeIds()`, a single management request bounded by
  `ClusterHttpClient.REQUEST_TIMEOUT` — **130 seconds** by default — with nothing printed before it; its
  failure was then discarded by `.or(List.of())`, and with an empty node list the drain and shutdown loops
  print nothing either. So the command's entire observable output could begin more than two minutes in,
  and an unreachable management endpoint was indistinguishable from a cluster with no nodes.
  [mechanism: `JdkHttpOperations.send` resolves its Promise on both completion paths, so the per-request
  ceiling is the 130s `HttpRequest.timeout()` rather than an unbounded wait]
- **Every phase announces itself before it blocks**, in the same `[Phase n/m: NAME]` shape bootstrap uses,
  so one operator reading both transcripts reads one format: `ENUMERATE_NODES`, `DRAIN_NODES`,
  `SHUTDOWN_NODES`, `CLOUD_CLEANUP`, `REGISTRY`.
- **Waits name what they are waiting for and their ceiling**, read from the constants that enforce them
  rather than restated — the node-list request names its endpoint and timeout, each drain names the
  `DECOMMISSIONED` state it polls for with its 120s budget and 2s interval, and the cleanup phase names the
  firewall retry budget. An announced timeout that does not match the one in force is the same class of
  false diagnostic as #994's *"servers are still detaching"*.
- **A failed node enumeration is reported with its consequence**, not swallowed: an empty node list means
  drain and shutdown are skipped, so nodes are deleted **without a graceful drain** while cloud cleanup
  still proceeds from the bootstrap ledger and the command can still exit 0.
- **The command says up front that it can take minutes**, with the figures that bound it, before the first
  wait begins.
- Pinned by `ClusterDestroyCommandTest.DestroyProgressOutput`, including a positional assertion that the
  phase line is the *first* output rather than a retrospective note, an assertion that the announced
  timeout equals `ClusterHttpClient.REQUEST_TIMEOUT` so the two cannot drift apart, and a succeeding-request
  positive control so "stderr contains the warning" is a real signal rather than an always-on line.
- **[unverified: the residual silence]** — the 130s ceiling accounts for the great majority of the observed
  ~2.5 minutes but not all of it, and the run was killed rather than allowed to complete, so **why** the
  request blocked (rather than being refused) is not established. The operator's IP had to match the
  firewall's `--admin-cidr` /32 for the endpoint to be reachable at all, which would produce exactly this
  shape if it had changed, but that is a hypothesis and is not claimed here. What is fixed is that the
  silence can no longer hide any of it.
