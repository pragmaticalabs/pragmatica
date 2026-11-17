### Fixed (2026-11-14 — #3190: OperatorWarningsTest.raise_slowPublisher_returnsWithoutWaitingForIt wall-clock bound failed on a loaded host)
- **The hand-off test asserted a 1 s elapsed-time bound**, which a starved carrier thread on a busy host can exceed with no defect.
- The test now blocks the publisher on a latch released only after `raise` has returned and asserts the publisher has not finished at that point; a 10 s deadline only detects a hang. Test change only. `[verified: integrations/utility/src/test/java/org/pragmatica/utility/warning/OperatorWarningsTest.java]`
