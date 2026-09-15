#!/bin/bash
# 02s runs the S19/S20 file that LIVES IN 02-chaos. This is a two-line exec wrapper, not a symlink
# and not a copy:
#   - a copy would drift, and the two suites would disagree silently about what S19 asserts;
#   - a symlink is enumerated by every tree-walking check as a SECOND PATH to the same bytes, so
#     `lint-tests.sh` counted its findings twice and `test-chaos-harness.sh`'s W1 counted six gate
#     call sites where the invariant is five. Two consumers broke on the same cause; there is no
#     reason to think they are the last.
# An exec wrapper is a real file with no findings and no gate calls, so tree-walkers see nothing to
# double-count, while execution still reaches exactly one implementation.
exec "$(cd "$(dirname "${BASH_SOURCE[0]}")/../02-chaos" && pwd)/test-self-drain-quorum-loss.sh" "$@"
