### Fixed (2026-11-07 — #2753: stream replication, Role.OWNER opens the replica ack gate before the copy is verified for the committed epoch)
- **A stream copy placed OWNER no longer acknowledges records it has not compared with the committed owner of the current
  epoch.** The ack gate (`StreamPartitionManager.replicaVerified`) used to open for the HRW placement role
  (`ReplicaSetController::roleFor`), which is not the committed ownership record. While the committed owner was still sending at
  epoch E2, such a copy acked a live batch at once, and a stale-epoch verification released its deferred ack, though its copy
  could hold an older lineage at and above E2's start. The gate now opens only on an `EpochTrust` verification for the committed
  epoch (or when no epoch is committed). `[verified: aether/aether-stream/src/test/java/org/pragmatica/aether/stream/replication/ReplicaAckGateTest.java,
  ownerRole_staleEpochVerification_doesNotReleaseTheDeferral and ownerRole_liveBatchOnUnverifiedCopy_isDeferred]`
- Such a copy still reaches verification: the backfill sources from the committed owner, its redrive treats the copy as not
  verified for the current epoch (`QuarantineView.verifiedForCurrentEpoch` reads the same gate), and the compare verifies it and
  releases the held ack. `[verified: ReplicaAckGateTest.ownerRole_unverifiedCopy_isVerifiedByTheCommittedOwnersCompare_andThenAcks,
  unit level with a stubbed network]` The time to that release is the redrive period plus one window fetch, as for any
  unverified replica. `[unverified: not measured]`
- Where the state arises: `roleFor` places the committed owner first when an ownership record exists
  (`ReplicaSetController.effectivePlacement`), so a copy placed OWNER while another node is the committed owner is a skew
  between two reads of the ownership state, not a steady state. Once it clears, the copy is a REPLICA and verifies as before.
  `[mechanism: traced, not probed]`
- Limits: the read path is unchanged. `unverifiedFrom` still exempts the placement owner, whose reads go through the owner
  serve gate. A node that is the committed owner receives no batch of its own epoch, and one of a newer epoch (admitted while its
  view lags) now waits for verification like any other copy's instead of being acked on the role.
  `[mechanism: ReplicationReceiveHandler.senderMatches admits a batch of the committed epoch only from the committed owner]`
