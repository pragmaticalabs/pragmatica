### Fixed (2026-11-14 — #3206: H3 anonymousUpload test flaked when the probe request stream was not flushed)
- **The HTTP/3 test probe left its request stream unflushed after the writer returned**, so the upload body could stay buffered and the test timed out intermittently.
- The probe now flushes the request stream after its writer returns. Test change only.
