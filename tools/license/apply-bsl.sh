#!/usr/bin/env bash
# apply-bsl.sh - kept as the historical entry point; the work is done by relicense.py (#1989).
#
# The BSL modules are listed in tools/license/bsl-modules.txt; everything else is Apache-2.0. relicense.py puts the BSL header on every
# .java file inside them, replaces a BUSL-1.1 header outside them with the Apache one, and keeps the poms and LICENSE files in step.
#
# Usage: tools/license/apply-bsl.sh [--dry-run] [--check] [--list] [--report FILE]
exec python3 "$(dirname "$0")/relicense.py" "$@"
