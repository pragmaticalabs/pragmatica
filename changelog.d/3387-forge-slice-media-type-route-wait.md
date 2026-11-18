### Fixed (2026-11-18 — #3387: SliceMediaTypeTest answered 404 because its readiness wait did not cover the route)
- **`SliceMediaTypeTest` asked for `/csv/widget` before the route existed.** The wait passed as soon as the slice list
  named `echo-slice`, but that list shows a slice in any state (a slice still loading is listed), and a node publishes
  a slice's HTTP routes inside its activation chain, before it records ACTIVE. The test now waits, with a deadline, for
  both routes it asserts to answer 200 on the app HTTP port it then uses, and fails naming the route, the port and the
  last status. The other forge tests that call a slice route already wait on the route itself. Test-only change; no
  product behaviour changed.
