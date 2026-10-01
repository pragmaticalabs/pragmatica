### Fixed (2026-10-01 — integration harness: S19 tier 2 trusted a cached address without proving which VM answered)
- **S19 tier 2 reads the survivor's exit state at the pre-kill cached address, and cloud IPs are recycled**, so a stranger's
  `failed/2` or `exit code 2` could be scored as the survivor's. The read now proves identity on both runtimes: JVM prints
  `AETHER_NODE_ID` from `/etc/aether/node.env`; container adds the `aether-node-id` label of the `aether-node` container to the
  same `docker inspect`. A different id is an identity-violation FAIL, a missing id an indeterminate FAIL.
  [verified: `aether/tests/integration/test/test-chaos-harness.sh` TI1-TI4; dropping the check reddens them. No cloud run.]
