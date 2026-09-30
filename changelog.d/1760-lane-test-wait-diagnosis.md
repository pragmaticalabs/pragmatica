### Changed (2026-09-30 — #1727: every timed-out wait in the lane-delivery tests is attributable, and the loss test's relay no longer stalls silently)
- A timeout on any wait in `QuicLaneOwnershipTest` or `QuicLaneFinishUnderLossTest` now reports:
  - both ends' QUIC counters at the timeout and 5 s later;
  - the running burst's verdict;
  - datagrams counted at each end's datagram pipeline head (inbound before the QUIC codec);
  - both datagram channels' isActive/isWritable;
  - on Linux, each socket's `/proc/net/udp` rx_queue and drops (the relay's sockets included), the host-wide `/proc/net/snmp` Udp RcvbufErrors, SndbufErrors and InErrors deltas, and the sockets bound to the acceptor's port.
  Previously only the final delivery check carried a diagnosis; an earlier wait failed with just its own name. A new NOTHING-UNREAD verdict replaces "RECEIVER-SIDE … >= 0 B" when no acknowledged write is missing. [verified: integrations/consensus/src/test/java/org/pragmatica/consensus/net/quic/QuicLaneFinishUnderLossTest.java — forcing the pivot wait to time out yields every field in 10/10 messages, with real /proc values on Linux]
- The test relay `UdpGate` survives a transient send or receive failure (counted, logged; the datagram is lost). Before, it ended that relay direction silently, stalling it for the rest of the test: a plausible cause of intermittent `QuicLaneFinishUnderLossTest` timeouts under host load. It stops only on teardown and records why. [verified: integrations/consensus/src/test/java/org/pragmatica/consensus/net/quic/UdpGateTest.java — restoring the silent exit reddens the test for that direction]
