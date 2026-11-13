#!/usr/bin/env bash
# Pre-pull the Testcontainers images a CI job needs, with bounded retry and backoff (#3124).
#
# A pull from the registry mirror fails transiently, and a failed pull inside a test ejects a green PR from the merge
# queue. The images are in tools/ci-test-images.txt, which tools/check-ci-test-images.py keeps equal to what the tests
# start. Testcontainers finds a pulled image in the local Docker cache, so a test that starts after this step does not pull.
#
#   ci-prepull-images.sh [MODULES_FILE]
#
# MODULES_FILE: one module path per line (the test group's plan); only images used by those modules are pulled. Without
# it, every non-excluded image is pulled. Docker Hub images are pulled through $TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX
# (the same prefix Testcontainers applies), other registries as named. Every image gets ATTEMPTS tries with
# BACKOFF_BASE * 2^(n-1) seconds between them (10, 20, 40 s); one image still failing fails the step.
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LIST="${CI_TEST_IMAGES_LIST:-$ROOT/tools/ci-test-images.txt}"
ATTEMPTS="${CI_PREPULL_ATTEMPTS:-4}"
BACKOFF_BASE="${CI_PREPULL_BACKOFF_BASE:-10}"
PREFIX="${TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX:-}"
DOCKER="${CI_PREPULL_DOCKER:-docker}"
SLEEP="${CI_PREPULL_SLEEP:-sleep}" # injectable so a test records the backoff instead of waiting
MODULES_FILE="${1:-}"

if [ -n "$MODULES_FILE" ] && [ ! -r "$MODULES_FILE" ]; then
  echo "::error::modules file $MODULES_FILE is not readable"
  exit 2
fi

# A Docker Hub image has no registry host: its first path component holds no '.' or ':' and is not localhost.
qualified() {
  local first="${1%%/*}"
  if [ "$first" = "$1" ] || { [[ "$first" != *.* ]] && [[ "$first" != *:* ]] && [ "$first" != localhost ]; }; then
    echo "${PREFIX}$1"
  else
    echo "$1"
  fi
}

# Wanted when any listed module is not excluded (no `!`) and is in the plan; `*` is always in the plan.
wanted() {
  local module list="${1//,/ }"
  set -f # `*` must stay literal in the word split below
  for module in $list; do
    [[ "$module" == *'!' ]] && continue
    if [ "$module" = '*' ] || [ -z "$MODULES_FILE" ] || grep -qxF "$module" "$MODULES_FILE"; then
      set +f
      return 0
    fi
  done
  set +f
  return 1
}

pull() {
  local ref="$1" attempt=1 delay
  while :; do
    if "$DOCKER" pull "$ref"; then
      echo "prepull: pulled $ref (attempt $attempt of $ATTEMPTS)"
      return 0
    fi
    if [ "$attempt" -ge "$ATTEMPTS" ]; then
      echo "::error::could not pull $ref after $ATTEMPTS attempts"
      return 1
    fi
    delay=$((BACKOFF_BASE * (1 << (attempt - 1))))
    echo "prepull: pull of $ref failed (attempt $attempt of $ATTEMPTS); retrying in ${delay}s"
    "$SLEEP" "$delay"
    attempt=$((attempt + 1))
  done
}

failed=0
pulled=0
while read -r image modules _; do
  case "$image" in ''|'#'*) continue ;; esac
  wanted "$modules" || continue
  pull "$(qualified "$image")" || failed=$((failed + 1))
  pulled=$((pulled + 1))
done < "$LIST"

echo "prepull: $pulled image(s) attempted, $failed failed"
if [ "$pulled" -eq 0 ]; then
  echo "::error::no image selected: a run that pulled nothing proves nothing (empty plan or empty list)"
  exit 1
fi
[ "$failed" -eq 0 ]
