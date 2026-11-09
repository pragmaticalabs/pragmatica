### Fixed (2026-11-08 — #2927: `AwsLocalStackContractTest` LocalStack container startup flake)
- The LocalStack contract suite now starts its container with up to 3 attempts, a fresh container each, and a log consumer
  attached before start. When every attempt fails, the failure carries each attempt's cause chain (including the container
  exit code) and container output, so the unseen "exited with code 126" cause is captured on the next occurrence.
  Test-support change only; no product code touched.
