### Fixed
- **81 single-call `LockSupport.parkNanos` sleeps in tests woke early and made timing flakes (#3386):** they now call `org.pragmatica.testing.Pause`, which parks to its deadline and keeps the interrupt flag, and a never-allow-listed `PARK_SLEEP` FlakeScan gate refuses a new one.
