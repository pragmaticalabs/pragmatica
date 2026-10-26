# Management API: how an error becomes an HTTP status

Every failure a management route writes goes through one classification, `ManagementStatus` (`ProblemResponses.writeProblem` for a cause; the Maven protocol routes write their own 504 and 503 + `Retry-After` and send every other cause through it). A cause is classified by the first rule that applies:

1. It states its own status (`HttpStatusAware`): the typed error families (deploy, schema, scale, community, cluster config, `ManagementServerError`, ...).
2. A routing-layer parameter error: 400.
3. A stream engine refusal (`StreamError`): the class status of `StreamErrorStatus`.
4. A composite of several failures: the one status every member agrees on, else 500.
5. The central table for plain causes that state no status (below).

A cause no rule covers answers 500 and is logged once per type at WARN.

Two other mappers are separate, documented tables for failure classes that are not route errors: authentication and authorisation denials (401/403, `ManagementServer.resolveSecurityErrorStatus`) and a forwarded request that could not be delivered (`ForwardError.status()`, else 503, `ManagementServer.managementForwardFailureStatus`).

## Publish keeps its statuses

A publish is not idempotent. On the stream publish route a raw stream engine refusal answers a retry-inviting status (503, 429) only when it is refused before anything is written. Those are the causes the partition owner itself answers a forwarded publish with the retryable response for (`StreamForwardHandler.isRetryable`), so a refusal answers the same 503 whether the publisher is the owner or a forwarder: `StreamConfigNotYetVisible`, `OwnerNotActivated`, `NotOwnerAppend`, `NOT_ENOUGH_REPLICAS`, and the transient capacity refusals `ReshufflePaced`, `MaterializeBudgetExceeded`, `STREAM_MEMORY_EXCEEDED`, `SEALING_BEHIND`, `SEGMENT_TIER_FULL`. Every other raw stream error on publish answers 500, as it did before stream errors were classified. The stream rows below therefore apply to every route except publish. On publish the capacity refusals above moved from 500 to 503.

## Statuses that changed

85 causes answered 500 and now answer the status shown. Everything not listed is unchanged.

### Plain causes (42)

| Cause | Before | After |
|---|---|---|
| `AlertForwarder$AlertForwarderError$WebhookError` | Internal Server Error | Bad Gateway |
| `AlertManager$InjectionError#INVALID_SEVERITY` | Internal Server Error | Bad Request |
| `AlertManager$InjectionError#MESSAGE_REQUIRED` | Internal Server Error | Bad Request |
| `AlertManager$InjectionError#NAME_REQUIRED` | Internal Server Error | Bad Request |
| `ClusterEventAggregator$PublishError#PUBLISHER_NOT_BOUND` | Internal Server Error | Service Unavailable |
| `AlertRoutes$AlertError#METRIC_REQUIRED` | Internal Server Error | Bad Request |
| `AlertRoutes$AlertError#MISSING_FIELDS` | Internal Server Error | Bad Request |
| `CertificateRoutes$CertConfigureError#DEV_MODE_DISABLED` | Internal Server Error | Forbidden |
| `CertificateRoutes$CertConfigureError#MISSING_BODY` | Internal Server Error | Bad Request |
| `CertificateRoutes$CertConfigureError#SCHEDULER_NOT_CONFIGURED` | Internal Server Error | Service Unavailable |
| `CertificateRoutes$CertConfigureError#VALIDITY_TOO_HIGH` | Internal Server Error | Bad Request |
| `CertificateRoutes$CertConfigureError#VALIDITY_TOO_LOW` | Internal Server Error | Bad Request |
| `ClusterConfigRoutes$UpgradeError$AlreadyAtVersion` | Internal Server Error | Conflict |
| `ClusterTopologyRoutes$OwnershipError$UnknownDomain` | Internal Server Error | Bad Request |
| `DhtRoutes$DhtError#DEV_MODE_DISABLED` | Internal Server Error | Forbidden |
| `DhtRoutes$DhtError#DHT_UNAVAILABLE` | Internal Server Error | Service Unavailable |
| `DhtRoutes$DhtError#MISSING_BODY` | Internal Server Error | Bad Request |
| `DhtRoutes$DhtError#MISSING_HLC` | Internal Server Error | Bad Request |
| `DhtRoutes$DhtError#MISSING_KEY` | Internal Server Error | Bad Request |
| `DhtRoutes$DhtError#MISSING_VALUE` | Internal Server Error | Bad Request |
| `GossipKeyRoutes$GossipKeyRotationError$Superseded` | Internal Server Error | Conflict |
| `MetricsRoutes$BackfillError#DEV_MODE_DISABLED` | Internal Server Error | Forbidden |
| `MetricsRoutes$BackfillError#INVALID_INTERVAL` | Internal Server Error | Bad Request |
| `MetricsRoutes$BackfillError#INVALID_WINDOW` | Internal Server Error | Bad Request |
| `MetricsRoutes$BackfillError#MISSING_BODY` | Internal Server Error | Bad Request |
| `MetricsRoutes$BackfillError#MISSING_METRIC` | Internal Server Error | Bad Request |
| `NodeLifecycleRoutes$PromoteError#IMMUTABLE_ROLE` | Internal Server Error | Conflict |
| `NodeLifecycleRoutes$PromoteError#MISSING_TARGET_ROLE` | Internal Server Error | Bad Request |
| `NodeLifecycleRoutes$PromoteError#UNKNOWN_NODE_ROLE` | Internal Server Error | Conflict |
| `NodeLifecycleRoutes$PromoteError#UNSUPPORTED_TARGET_ROLE` | Internal Server Error | Bad Request |
| `ObservabilityRoutes$ObservabilityError#CONFIG_KEY_REQUIRED` | Internal Server Error | Bad Request |
| `ObservabilityRoutes$ObservabilityError#CONFIG_MISSING_FIELDS` | Internal Server Error | Bad Request |
| `ObservabilityRoutes$ObservabilityError#INVALID_DEPTH` | Internal Server Error | Bad Request |
| `ObservabilityRoutes$ObservabilityError#KEY_REQUIRED` | Internal Server Error | Bad Request |
| `ObservabilityRoutes$ObservabilityError#MISSING_FIELDS` | Internal Server Error | Bad Request |
| `ObservabilityRoutes$ObservabilityError#REQUEST_ID_REQUIRED` | Internal Server Error | Bad Request |
| `ScheduledTaskRoutes$InjectError$General#DEV_MODE_DISABLED` | Internal Server Error | Forbidden |
| `ScheduledTaskRoutes$InjectError$General#MISSING_ARTIFACT` | Internal Server Error | Bad Request |
| `ScheduledTaskRoutes$InjectError$General#MISSING_BODY` | Internal Server Error | Bad Request |
| `ScheduledTaskRoutes$InjectError$General#MISSING_METHOD` | Internal Server Error | Bad Request |
| `ScheduledTaskRoutes$InjectError$General#MISSING_SECTION` | Internal Server Error | Bad Request |
| `ScheduledTaskRoutes$InjectError$SliceNotLocal` | Internal Server Error | Conflict |

### Consumer-group coordinator (1)

| Cause | Before | After |
|---|---|---|
| `ConsumerGroupCoordinator$CoordinatorError#NOT_LEADER` | Internal Server Error | Conflict |

### Raw stream engine errors (42), every route except publish

| Cause | Before | After |
|---|---|---|
| `org.pragmatica.aether.stream.StreamError$CursorExpired` | Internal Server Error | Gone |
| `org.pragmatica.aether.stream.StreamError$EpochDiverged` | Internal Server Error | Conflict |
| `org.pragmatica.aether.stream.StreamError$EventTooLarge` | Internal Server Error | Bad Request |
| `org.pragmatica.aether.stream.StreamError$General#AHSE_REQUIRED_FOR_STRONG` | Internal Server Error | Bad Request |
| `org.pragmatica.aether.stream.StreamError$General#BUFFER_CLOSED` | Internal Server Error | Service Unavailable |
| `org.pragmatica.aether.stream.StreamError$General#BUFFER_FULL` | Internal Server Error | Service Unavailable |
| `org.pragmatica.aether.stream.StreamError$General#CONSENSUS_PATH_UNAVAILABLE` | Internal Server Error | Service Unavailable |
| `org.pragmatica.aether.stream.StreamError$General#CONSUMER_ALREADY_SUBSCRIBED` | Internal Server Error | Conflict |
| `org.pragmatica.aether.stream.StreamError$General#CONSUMER_NOT_FOUND` | Internal Server Error | Not Found |
| `org.pragmatica.aether.stream.StreamError$General#CONSUMER_RUNTIME_CLOSED` | Internal Server Error | Service Unavailable |
| `org.pragmatica.aether.stream.StreamError$General#CONSUMER_STALLED` | Internal Server Error | Service Unavailable |
| `org.pragmatica.aether.stream.StreamError$General#EVENT_DROPPED` | Internal Server Error | Bad Request |
| `org.pragmatica.aether.stream.StreamError$General#PARTITION_NOT_LOCAL` | Internal Server Error | Service Unavailable |
| `org.pragmatica.aether.stream.StreamError$General#SEALING_BEHIND` | Internal Server Error | Service Unavailable |
| `org.pragmatica.aether.stream.StreamError$General#SEGMENT_TIER_FULL` | Internal Server Error | Service Unavailable |
| `org.pragmatica.aether.stream.StreamError$General#STREAM_ALREADY_EXISTS` | Internal Server Error | Conflict |
| `org.pragmatica.aether.stream.StreamError$General#STREAM_CLOSED` | Internal Server Error | Service Unavailable |
| `org.pragmatica.aether.stream.StreamError$General#STREAM_MEMORY_EXCEEDED` | Internal Server Error | Service Unavailable |
| `org.pragmatica.aether.stream.StreamError$LinearizableRoundTimeout` | Internal Server Error | Service Unavailable |
| `org.pragmatica.aether.stream.StreamError$MaterializeBudgetExceeded` | Internal Server Error | Service Unavailable |
| `org.pragmatica.aether.stream.StreamError$NotCurrentOwner` | Internal Server Error | Service Unavailable |
| `org.pragmatica.aether.stream.StreamError$NotOwnerAppend` | Internal Server Error | Service Unavailable |
| `org.pragmatica.aether.stream.StreamError$OwnerCatchupPending` | Internal Server Error | Service Unavailable |
| `org.pragmatica.aether.stream.StreamError$OwnerNotActivated` | Internal Server Error | Service Unavailable |
| `org.pragmatica.aether.stream.StreamError$PartitionCapExceeded` | Internal Server Error | Bad Request |
| `org.pragmatica.aether.stream.StreamError$PartitionCeilingExceeded` | Internal Server Error | Bad Request |
| `org.pragmatica.aether.stream.StreamError$PartitionHeldNotMaterialized` | Internal Server Error | Service Unavailable |
| `org.pragmatica.aether.stream.StreamError$PartitionOutOfRange` | Internal Server Error | Bad Request |
| `org.pragmatica.aether.stream.StreamError$RepairNotAuthorized` | Internal Server Error | Service Unavailable |
| `org.pragmatica.aether.stream.StreamError$RepairPreserveFailed` | Internal Server Error | Service Unavailable |
| `org.pragmatica.aether.stream.StreamError$RepairWitnessFailed` | Internal Server Error | Service Unavailable |
| `org.pragmatica.aether.stream.StreamError$ReplicaNotVerified` | Internal Server Error | Service Unavailable |
| `org.pragmatica.aether.stream.StreamError$ReplicaQuarantined` | Internal Server Error | Service Unavailable |
| `org.pragmatica.aether.stream.StreamError$ReplicationRefused` | Internal Server Error | Conflict |
| `org.pragmatica.aether.stream.StreamError$ReshufflePaced` | Internal Server Error | Service Unavailable |
| `org.pragmatica.aether.stream.StreamError$RetentionBoundInvalid` | Internal Server Error | Bad Request |
| `org.pragmatica.aether.stream.StreamError$RetentionCountUnindexable` | Internal Server Error | Bad Request |
| `org.pragmatica.aether.stream.StreamError$StaleEpochAppend` | Internal Server Error | Service Unavailable |
| `org.pragmatica.aether.stream.StreamError$StaleEpochRead` | Internal Server Error | Service Unavailable |
| `org.pragmatica.aether.stream.StreamError$StreamConfigNotYetVisible` | Internal Server Error | Service Unavailable |
| `org.pragmatica.aether.stream.StreamError$StreamNotFound` | Internal Server Error | Not Found |
| `org.pragmatica.aether.stream.StreamError$SystemStreamWriteRefused` | Internal Server Error | Method Not Allowed |
