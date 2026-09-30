### Fixed (2026-09-30 — QUIC: a dial aimed at a recycled address tore down a healthy link and ping-ponged the leader; refs #1390)
- **A ghost dial no longer attaches at the acceptor.** `NetworkMessage.Hello` carries `intendedPeer` (the id the dialer
  meant to reach); an acceptor that is not that node answers with its own Hello, so the dialer's identity check fails the
  dial at once, but never registers the connection. Before, the acceptor attached first and the dialer rejected afterwards,
  and the attach superseded the healthy incumbent link on every dial (cloud run 1: every 5s, leader ping-pong, 30+ terms).
  Wire: pre-GA breaking change to the Hello shape; tag 38 unchanged, `wire-assignment-baseline.txt` updated.
- **The cross-direction supersede has a floor.** A lower-id link displaces a higher-id-initiated incumbent only inside a
  500ms formation window (both ends still converge on the lower-id link, #1390) or when the incumbent's peer has been
  receipt-silent for 3s. The 500ms window is a guess.
  [verified: `QuicMisdirectedDialTest` (real QUIC pair; skipping the refusal reddens it), `PeerStateTest` (removing the
  floor reddens the 1s-old case; window 0 reddens the 100ms case and 18 of 20 `QuicSimultaneousDialLaneTest`
  repetitions), consensus 964 tests and `aether/node` 2075 tests green. `[unverified:` no cloud or multi-node run.`]
