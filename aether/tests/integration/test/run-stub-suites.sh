#!/bin/bash
# run-stub-suites.sh — runs every test-*.sh stub suite in this directory; the CI `stub-suites` job
# (#1060) calls it on every PR and push. The suites stub ssh/hcloud/curl/api_get, so no cluster,
# cloud credential or docker is involved.
#
#   bash aether/tests/integration/test/run-stub-suites.sh
#
# A suite counts as passed only when it exits 0 AND its own closing summary says `failed: 0` with
# `passed:` above zero. Exit status alone is not enough: a sourced helper that calls `exit 0` ends a
# suite before any assertion runs, and that exit is indistinguishable from a clean pass. An empty glob
# exits 2, because a run that found no suites examined nothing and has no verdict to report.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

suites=()
for s in "$SCRIPT_DIR"/test-*.sh; do
    [ -f "$s" ] && suites+=("$s")
done
if [ ${#suites[@]} -eq 0 ]; then
    echo "run-stub-suites: no test-*.sh under ${SCRIPT_DIR}, so this run EXAMINED NOTHING; refusing to report success" >&2
    exit 2
fi

results=()
bad=0
for s in "${suites[@]}"; do
    name="$(basename "$s")"
    out="$(mktemp)"
    echo "=== ${name}"
    bash "$s" < /dev/null 2>&1 | tee "$out"
    rc=${PIPESTATUS[0]}
    passed="$(sed -n 's/^  passed: \([0-9][0-9]*\)$/\1/p' "$out" | tail -1)"
    failed="$(sed -n 's/^  failed: \([0-9][0-9]*\)$/\1/p' "$out" | tail -1)"
    rm -f "$out"
    if [ "$rc" -ne 0 ]; then
        verdict="FAIL (exit ${rc})"
    elif [ -z "$passed" ] || [ -z "$failed" ]; then
        verdict="FAIL (exit 0 but no passed/failed summary: the suite ended before reporting)"
    elif [ "$failed" -ne 0 ]; then
        verdict="FAIL (exit 0 but its summary reports failed: ${failed})"
    elif [ "$passed" -eq 0 ]; then
        verdict="FAIL (exit 0 but passed: 0, so it asserted nothing)"
    else
        verdict="ok"
    fi
    [ "$verdict" = "ok" ] || bad=$((bad + 1))
    results+=("${name}  exit=${rc} passed=${passed:-?} failed=${failed:-?}  ${verdict}")
done

echo ""
echo "=== stub suites: ${#suites[@]} run, ${bad} not passed"
printf '  %s\n' "${results[@]}"
[ "$bad" -eq 0 ]
