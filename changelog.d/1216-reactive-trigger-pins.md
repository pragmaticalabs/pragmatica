### Fixed (2026-09-21 — #1216: the scheduled half's valid path had no pin, and the publisher's empty-subscriber branch was silent)
- **`TopicPublisher` now reports a publish that finds no subscriber, instead of returning success in
  silence.** Every undelivered publish increments the counter `aether.topic.publish.undelivered` (tags
  `topic`, `address`, `slice`) on the node's `MeterRegistry`, which reaches the publisher through the same
  provisioning-context extension `MetricsInterceptorFactory` reads (#278); outside a deployment there is no
  registry and only the log line remains. The log line is a WARN naming the bare topic, the address the
  publisher resolved, and the publishing slice — `Topic 'orders' published by org.example:order-intake:1.0.0
  has no subscribers at address org.example.orders-app:orders:1.0.0 — delivered to nobody; 0 more undelivered
  since the previous line (#1216)` — **rate-limited to one per publisher per 60 s** by core's lock-free
  `RateLimiter`, with the publishes the window suppressed counted into the next line (a 10,000-publish flood
  used to write 10,000 identical lines in 366 ms). The delivery contract is unchanged: the publish still
  SUCCEEDS with zero deliveries, because a publisher must not fail when its consumers are simply not
  deployed. What changed is the silence — the #1216 address mismatch produced exactly this branch on every
  publish for a full release with no line anywhere, so an operator could not set the publisher's address
  against the `topic-sub/` keys. [verified: `publish_noSubscribers_isObservable` reddens `Expected size: 1
  but was: 0` with the WARN removed and with it demoted to DEBUG; `publish_repeatedUndelivered_warnsOncePerPeriod_andReportsTheSuppressedCount`
  drives a hand-advanced clock — 3 publishes → 1 line, +59.999 s → still 1, +60 s → 2 with `3 more
  undelivered`; `publish_noSubscribers_incrementsTheUndeliveredCounter` reads 3.0 off a `SimpleMeterRegistry`;
  `publish_withSubscriber_doesNotWarn` / `…_doesNotCount` are the controls;
  `provision_undeliveredPublish_warnsNamingTopicResolvedAddressAndPublishingSlice` and
  `…_countsOnTheContextsMeterRegistry` pin the factory's wiring through the real provisioning path]
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
- Recorded, not changed (#1427, #1438): `ScheduleConfig`'s bind fails on a MISSING `cron` or `interval` key
  — the binder turns an absent `String` component into `sectionNotFound` unless the record declares a
  `public static final DEFAULT` (`ProviderBasedConfigService.lookupDefaultField`), which `TopicConfig` does
  and `ScheduleConfig` does not, so its compact-constructor `null → ""` defaults are unreachable — and a
  `[scheduling.x]` section written either way the documentation shows it (`resource-reference.md`:
  `[scheduling.cleanup]` with `interval` only, `[scheduling.report]` with `cron` only) publishes no task
  while the slice still reaches ACTIVE. Only the ticketing demo's `resources.toml` ("All three keys are
  required (even if empty)") gets it right today, and it is not the documentation. [verified: the same test
  with `cron = ""` deleted from its TOML logs `schedule config binding failed for section
  scheduling.sweep-holds: Config section not found: ScheduleConfig.cron` and submits 0 Puts; rev1421 MY8
  measured the cron-only shape failing on `ScheduleConfig.interval` the same way]
