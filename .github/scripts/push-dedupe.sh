#!/usr/bin/env bash
# The decision of ci.yml's push-dedupe job (T18, #3715). Env: EVENT BRANCH REPO OWNER SHA GH_TOKEN, GITHUB_OUTPUT (a file).
# Writes two outputs: skip (every job but forge-tests is skipped) and queue_tested (forge-tests still runs: it saves the
# Maven cache on push, and caches are scoped by ref). Any failure to decide means skip=false: a duplicate run is cheaper
# than a missing one.
#   push to main or release-*: skip=true only when a CI run of event merge_group exists for the SAME head sha with conclusion
#     success. The queue builds the commit, tests it and fast-forwards the branch to that sha, so the push run repeats it.
#     A direct push, a failed or still-running queue run, a lookup error or an unreadable reply runs everything.
#   push to another branch: skip=true when it has an open PR into a base the pull_request trigger covers (the PR run gates).
#   any other event: skip=false.
set -u
: "${GITHUB_OUTPUT:?GITHUB_OUTPUT is not set}"

skip=false
queue_tested=false

# Prints the id of a successful merge_group run of ci.yml whose head sha is $1, or nothing (exit 1). Exit 9: the lookup failed.
# Every filter is applied again here, not left to the query: the reply is not trusted to have been filtered.
queue_run_id() {
  local sha=$1 reply
  reply=$(gh api -X GET "repos/$REPO/actions/workflows/ci.yml/runs" -f event=merge_group -f "head_sha=$sha" -f per_page=100) || return 9
  jq -er --arg sha "$sha" \
    '[.workflow_runs[] | select(.event == "merge_group" and .head_sha == $sha and .conclusion == "success") | .id] | first | select(. != null) | tostring' \
    <<<"$reply" 2>/dev/null || return 1
}

case "$EVENT:$BRANCH" in
  push:main|push:release-*)
    if [[ ! "${SHA:-}" =~ ^[0-9a-f]{40}$ ]]; then
      echo "running CI: head sha '${SHA:-}' is not a full commit sha"
    else
      rc=0
      id=$(queue_run_id "$SHA") || rc=$?
      if [ "$rc" -eq 9 ]; then
        echo "::warning::merge_group run lookup failed, running CI"
      elif [ "$rc" -ne 0 ] || [[ ! "$id" =~ ^[0-9]+$ ]]; then
        echo "running CI: no successful merge_group run of ci.yml on $SHA (direct push, failed or unfinished queue run, or unreadable reply)"
      else
        skip=true
        queue_tested=true
        echo "skipping duplicate push run: merge_group run $id of ci.yml succeeded on $SHA (forge-tests still runs: it saves the Maven cache on push)"
      fi
    fi ;;
  push:*)
    if bases=$(gh api -X GET "repos/$REPO/pulls" -f state=open -f "head=$OWNER:$BRANCH" --paginate --jq '.[] | select(.head.repo.full_name == "'"$REPO"'") | .base.ref'); then
      echo "open PR base(s) for $BRANCH: ${bases:-none}"
      for base in $bases; do
        case "$base" in
          main|release-*|overhaul/*|rewrite/*|feature/*) skip=true ;;
        esac
      done
    else
      echo "::warning::PR lookup failed, running CI"
    fi ;;
esac
echo "skip=$skip" >> "$GITHUB_OUTPUT"
echo "queue_tested=$queue_tested" >> "$GITHUB_OUTPUT"
echo "skip=$skip queue_tested=$queue_tested"
