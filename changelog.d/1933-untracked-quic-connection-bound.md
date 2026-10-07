### Fixed (2026-10-04 — #1933 item 2: a QUIC connection that never completes its Hello was never closed)
- **The Hello timeout now bounds the CONNECTION, not just the stream.** The QUIC idle timeout is disabled (cluster connections are persistent),
  so nothing reaped a connection the cluster network does not track. On the acceptor, a connection that opened no stream, or whose silent stream
  the 15 s Hello timeout closed while the connection stayed open, lived for the life of the process; on the dialer, a dial that failed on
  the Hello timeout left its connection "active". Now an acceptor closes a connection that has not completed its Hello within the Hello bound (15 s)
  and a dialer closes the connection of a dial that timed out waiting for the Hello answer. A connection that completed its Hello is never touched:
  there is still no global idle timeout.
  [mechanism: `QuicUntrackedConnectionBoundTest` (real QUIC endpoints; the Hello bound shortened through a test seam): no-stream and silent-stream connections
  are closed, a mute acceptor's connection is closed by the dialer, a Hello'd connection idles past the bound untouched.]
