### Fixed (2026-09-30 — #1564 follow-up: replication-policy refusals and warnings reach the operator precisely)
- **A publish forwarded to the partition owner and refused before the append (`NOT_ENOUGH_REPLICAS`) answered 500,
  as a permanent `RemotePublishFailed`.** The owner now answers that refusal as retryable, like its other pre-append
  refusals; the forwarder bounded-retries, and a single Management-API publish answers 503 on the owner and on a
  forwarding non-owner alike. Nothing is written in either case.
  [mechanism: pinned by `StreamForwardHandlerTest`, `StreamApiRoutesPublishPartitionTest`]
- **A refused `system:cluster-events` registration had no REST-visible surface**: its OperatorWarning event goes into
  the very stream whose registration was refused. It is also a CRITICAL alert on the leader's `/api/alerts/active`
  again — local to the leader, never replicated to the cluster log, so the clear that runs once a corrected cluster
  config commits the stream removes it completely.
  [mechanism: pinned by `SystemStreamRegistrarTest`, `AlertManagerInjectTest` (with a bound cluster-events source),
  `OperatorWarningWiringTest`]
- **The LOUD warning (an explicitly declared `replication_factor` below 3) shared one code and level with the others.**
  It is now the CRITICAL OperatorWarning `replication-factor-below-three` at blueprint publish and at activation;
  CF == RF and CF == 1 stay `deploy-warning` / `replication-policy-warning` (WARNING).
  [mechanism: pinned by `StreamSectionBindingTest`, `PublisherFactoryTest`, `DurableEntityFactoryTest`,
  `BlueprintServiceTest$DeployWarningEvents`]
- **The recovery claim "replace the lost core; placement refills the replica set" is refuted on Ember**: a replacement
  that joins under a fresh identity is not placed into an existing partition's replica set, so a CF == RF stream stays
  write-refused after one core loss (#1732). `guarantees.md` §4/§4a now say so; the Forge acceptance for the recovery half
  is an enabled tripwire beside the disabled real assertion.
  [refuted on Ember: `aether/forge/forge-tests/src/test/java/org/pragmatica/aether/forge/StreamConfirmationEqualsFactorAvailabilityTest.java`]
- **Stale #1550 owner-failover statements in `failure-almanac.md` are corrected**: since #1555 ownership moves to a
  surviving replica and the new owner serves every replicated event; what remains open is refilling the lost replica
  slot (#1732).
  [verified: `aether/forge/forge-tests/src/test/java/org/pragmatica/aether/forge/StreamDefaultRfOwnerReplacementTest.java`, 2/2 on cloudbb-2]
