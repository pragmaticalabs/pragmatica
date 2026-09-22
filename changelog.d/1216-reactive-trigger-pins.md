### Fixed (2026-09-21 — #1216: the scheduled half's valid path had no pin, and the publisher's empty-subscriber branch was silent)
- **`TopicPublisher` now WARNs when a publish finds no subscriber, naming the bare topic, the address the
  publisher resolved, and the publishing slice** (`Topic 'orders' published by org.example:order-intake:1.0.0
  has no subscribers at address org.example.orders-app:orders:1.0.0 — delivered to nobody (#1216)`). The
  delivery contract is unchanged: the publish still SUCCEEDS with zero deliveries, because a publisher must
  not fail when its consumers are simply not deployed. What changed is the silence — the #1216 address
  mismatch produced exactly this branch on every publish for a full release with no line anywhere, so an
  operator could not set the publisher's address against the `topic-sub/` keys. `PublisherFactory` supplies
  the topic name and the slice id from the provisioning context (a stated placeholder when provisioned
  outside a deployment). No counter: the module's only metrics seam is per-invocation
  (`InvocationMetricsCollector.record*`) and an undelivered publish makes no invocation; a counter would be
  a new metrics surface, not wiring into an existing one. [verified: `publish_noSubscribers_isObservable`
  reddens `Expected size: 1 but was: 0` with the WARN removed; `publish_withSubscriber_doesNotWarn` is the
  control; `provision_undeliveredPublish_warnsNamingTopicResolvedAddressAndPublishingSlice` pins the
  factory's wiring through the real provisioning path]
- **The scheduled half's VALID path is pinned end to end for the first time.**
  `NodeDeploymentStateScheduledTaskPublishTest` drives the real LOAD → ACTIVE chain with a manifest
  declaring a scheduled binding and a slice composite parsed from the ticketing demo's own section shape
  (`interval = "60s"`, `cron = ""`, `execution_mode = "SINGLE"`), asserts the node submits exactly one
  `scheduled-task/` Put — the bound value, registered by this node, BEFORE the ACTIVE transition — then hands
  that Put to a real `ScheduledTaskRegistry` + `ScheduledTaskManager` on a leader and asserts one timer
  starts. Before this, `NodeDeploymentStateScheduledTaskValidationTest` exercised only invalid strings; the
  hop's PRESENCE was caught only indirectly, by `NodeDeploymentStateManifestReadFailureTest` counting three
  read-failure WARNs on the poisoned-jar path, and nothing observed the Put a valid binding produces.
  [verified: with the `publishScheduledTasks` hop removed from `performActivation`, 1272 run, exactly 2 red —
  this test `Expected size: 1 but was: 0` and the read-failure count `expected: 3L but was: 2L`; the green
  run's own log carries `entering Leading` and `Started scheduled task
  scheduled-task/scheduling.sweep-holds/org.example:sweep-holds:1.0.0/execute with interval 60s`]
- Recorded, not changed: `ScheduleConfig`'s bind fails on a MISSING `cron` key — the binder turns an absent
  `String` component into `sectionNotFound`, so the record's `null → ""` default is never reached — and a
  `[scheduling.x]` section that omits `cron` publishes no task while the slice still reaches ACTIVE. The
  ticketing demo declares all three keys and is not affected. [verified: the same test with `cron = ""`
  deleted from its TOML logs `schedule config binding failed for section scheduling.sweep-holds: Config
  section not found: ScheduleConfig.cron` and submits 0 Puts] [unverified: whether the omitted-key shape
  was ever intended to bind — a ruling, not a fix, is what is missing]
