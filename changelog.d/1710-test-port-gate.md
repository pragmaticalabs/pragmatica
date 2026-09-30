### Added (CI)

- A test-port gate (`tools/check-test-ports.py`) runs in `build-and-test` at the pull request's merge ref. It fails when two `TEST_PORT_ALLOCATION.md` rows overlap. Ranges are compared per protocol: cluster/QUIC and SWIM (cluster + 100) are UDP; management and app-http are TCP. This catches the collision two sibling PRs cannot see from their own branches. Fixed ports in `*/src/test` that no row covers are listed, as a warning only, until they are registered (#1710).
