### Fixed (2026-10-08 — #2016: node boot tests reserved a TCP port and bound UDP on it)
- **19 `aether/node` boot tests picked their cluster port with `new ServerSocket(0)`, closed it, and let the node bind QUIC and SWIM (UDP) on it.**
  A free TCP port says nothing about the UDP port or about the derived SWIM port (`port + 100`), and under the `-T 1C` reactor another
  module took it in between: `AetherNodeDhtMarkerPostFormationBootTest` failed once with `BindException` from the SWIM UDP transport.
  They now use `ClusterTestPorts.freeClusterPort()`, which draws its candidate from a `DatagramSocket(0)` and probes cluster UDP, cluster TCP
  and SWIM UDP. The window between probe and bind is narrowed, not closed; a test tripwire fails if a node test reserves with `ServerSocket(0)` again.
