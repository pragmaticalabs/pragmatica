### Fixed (2026-09-30 — #1564 follow-up: replication-policy refusals and warnings reach the operator precisely)
- **A publish forwarded to the partition owner and refused before the append (`NOT_ENOUGH_REPLICAS`) answered 500,
  as a permanent `RemotePublishFailed`.** The owner now answers that refusal as retryable, like its other pre-append
  refusals; the forwarder bounded-retries, and a single Management-API publish answers 503 on the owner and on a
  forwarding non-owner alike. Nothing is written in either case.
  [mechanism: pinned by `StreamForwardHandlerTest`, `StreamApiRoutesPublishPartitionTest`]
- **A refused `system:cluster-events` registration had no REST-visible surface**: its OperatorWarning event goes into
  the very stream whose registration was refused. It is also a CRITICAL alert on `/api/alerts/active` again, and the
  alert is resolved once a corrected cluster config commits the stream.
  [mechanism: pinned by `SystemStreamRegistrarTest`, `AlertManagerInjectTest`, `OperatorWarningWiringTest`]
- **The LOUD warning (an explicitly declared `replication_factor` below 3) shared one code and level with the others.**
  At activation it is now the CRITICAL OperatorWarning `replication-factor-below-three`; CF == RF and CF == 1 stay
  `replication-policy-warning` (WARNING).
  [mechanism: pinned by `StreamSectionBindingTest`, `PublisherFactoryTest`, `DurableEntityFactoryTest`]
