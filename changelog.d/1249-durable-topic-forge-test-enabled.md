### Fixed (2026-09-19 — #1249: DurableTopicDeliveryForgeTest was @Disabled and had never been observed green)
- **The composed durable pub/sub forge test gated nothing**: it was disabled, its setUp waited on a
  warm-up event that is never delivered on this branch, three arms counted each other's events, and
  the group-isolation arm could pass without the probe reaching the healthy group.
- The test is enabled. Every arm now publishes under its own ids and counts only those, per id, from
  the fixture's per-group records. Counts are taken once per slice instance, not once per HTTP port:
  app HTTP forwards when a node has no active local instance, and one run counted a 5-attempt event
  as 10. The isolation arm now requires the probe to reach the failing group, the healthy group to
  handle it exactly once, and the failing group's budget to stay at 5.
- Two arms were blocked by #1238 (PR #1285): the pre-attach backlog and serial dispatch under
  late acks. Until #1285 merged each was kept `@Disabled` beside an enabled tripwire asserting the
  pre-fix behaviour (the warm-up stays stranded; ten late-acked events come back 10, 9, … 1 times).
  With #1285 in rc4 both tripwires went red as designed, and both real arms are now enabled and pass.
  The class is `@Tag("Heavy")`, so `ci.yml`'s forge-tests job never runs it; only the `run-heavy`
  label or a `heavy-forge` dispatch does.
  [verified: aether/forge/forge-tests/src/test/java/org/pragmatica/aether/forge/DurableTopicDeliveryForgeTest.java]
- The fixture is pinned to every node (`instances = minAvailable = maxInstances = 5`) and setUp waits
  for all five ACTIVE on distinct nodes before any arm publishes. Left autoscalable, the slice was
  descaled 5 → 4 → 3 mid-run and no consumer was attached anywhere for 5.5 minutes (#1389), and a
  late `forceActivatingToActive` moved the consumer inside an arm's window.
- The pre-attach arm asserts on one id, never a count: the order-events warm-up that is definitely
  in the log before the attach — its publish returned success, or its outcome came back unknown (5 s
  replication timeout, #1236) and the partition owner's head offset (`GET
  /api/v1/streams/{name}/{partition}/replicas-local`, the one stream read route that takes the raw
  `topic:` engine key) advanced by exactly one across the attempt — with `attachedSubscriptions`
  reading 0 on every node afterwards. Warm-ups that neither resolved nor could be observed are
  excluded and retried under a fresh id; a retry delivered by the listener after the attach used to
  satisfy the old `>= 1`. A run in which no warm-up can be placed in the log before the attach skips
  the arm with the readings in the message, since nothing in that run can speak about the backlog
  read. The deterministic form of this arm is #739.
- Publish outcomes (#1236) and pre-durability visibility (#1235) have no arm. This harness cannot
  drive either without losing quorum or failing over the owner. [unverified: no arm reaches them]
