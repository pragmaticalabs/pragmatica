# Release steps

Steps a release needs that no build enforces. Add a step here when a release depends on something outside this repository.

1. **Tag `ai-tools-v<version>` in `siy/coding-technology` before the release is cut** (#2363). `jbct init` and `jbct update` fetch the
   AI tools (Claude Code skills and agents) at the tag `ai-tools-v<jbct version>` of the running build and never from `main`.
   If the tag does not exist when users run the release, `jbct init` exits 1 and `jbct update` fails, each naming the tag it looked for.
   No `ai-tools-v*` tag exists today (`git ls-remote --tags`, 2026-11-25), so a build of the current branch (version `1.0.0-rc5`)
   refuses until its tag is created. `jbct init --no-ai` is not affected.
