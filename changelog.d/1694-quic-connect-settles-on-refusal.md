### Fixed (2026-09-30 — #1694: a QUIC connect never settled when the server refused the client certificate)
- **The client's connect promise never resolved** when the server closed the connection before answering the Hello,
  e.g. after refusing the client certificate: nothing failed the dial on close, and the Hello timeout skipped
  channels that were no longer active. Any caller without its own timeout hung. The dial now fails promptly on that
  close ("the peer closed the connection before answering the Hello"), and the Hello timeout settles it in every case.
  [verified: `integrations/consensus/src/test/java/org/pragmatica/consensus/net/quic/QuicClusterAdmissionTest.java`
  — the certificateless rejections, which were an enabled tripwire until this fix]
